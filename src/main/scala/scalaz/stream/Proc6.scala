package scalaz.stream

import scalaz.{\/, ~>}
import scalaz.\/.{left,right}
/*

data Stream f o
  = forall s. Stream s (s -> Step f o s)

data Step f o s
  = Halt Err
  | Emit [o] s
  | forall z. Await (f z) (Either Err z -> s)

*/

sealed trait Proc6[+F[_],+O] {
  import Proc6._
  import Step._

  def onHalt[G[_],O2>:O](tl: Throwable => Proc6[G,O2])(implicit E: Sub1[F,G]):
    Proc6[G,O2]

  def ++[G[_],O2>:O](tl: => Proc6[G,O2])(implicit E: Sub1[F,G]): Proc6[G,O2] =
    E.subprocess(this).onHalt {
      case End => tl
      case err => fail(err)
    }

  def onComplete[G[_],O2>:O](tl: => Proc6[G,O2])(implicit E: Sub1[F,G]): Proc6[G,O2] =
    E.subprocess(this).onHalt {
      case End => tl
      case err => tl.onHalt { err2 => fail(CausedBy(err2, err)) }
    }

  def map[O2](f: O => O2): Proc6[F,O2]

  def step: Step[F,Proc6[F,O],O]

  def flatMap[G[_],O2](f: O => Proc6[G,O2])(implicit E: Sub1[F,G]): Proc6[G,O2] = {
    val p: Proc6[G,Proc6[G,O2]] = E.subprocess(this).map(f)
    unfold(p -> (halt: Proc6[G,O2])) { p =>
      val outer = p._1; val inner = p._2
      inner.step.fold(
        { case End => outer.step.fold(
            erro => Halt(erro),
            (h,t) => if (h.isEmpty) Emit(Vector.empty, (t -> halt))
                     else Emit(Vector.empty, cons(h.tail, t) -> h.head),
            (req,recv) => Await(req, recv andThen (_ -> halt))
          )
          case erri => Halt(erri)
        },
        (head, tail) => Emit(head, outer -> tail),
        (req,recv) => Await(req, recv andThen (outer -> _))
      )
    }
  }
}

object Proc6 {
  import Step._

  type OnHaltS[F[_],+S,S2,O] = S \/ (S2, S2 => Step[F,S2,O])
  type JoinS[F[_],+S,O] = (S, Proc6[F,O])

  case class Unfold[+F[_],S,+O](seed: S, next: S => Step[F,S,O]) extends Proc6[F,O] {
    def onHaltU[G[_],S2,O2>:O](tl: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[F,G]):
    Unfold[G,OnHaltS[G,S,S2,O2],O2] = {
      Unfold(left(seed), e => Step.safe { e.fold(
        s0 => E.substep(next(s0)).onHalt(tl),
        { p => p._2(p._1).mapS(s2 => right(s2 -> p._2)) }
      )})
    }
    def onHalt[G[_],O2>:O](tl: Throwable => Proc6[G,O2])(implicit E: Sub1[F,G]):
      Proc6[G,O2] = onHaltU(tl.asInstanceOf[Throwable => Unfold[G,Any,O2]])

    def map[O2](f: O => O2): Unfold[F,S,O2] = Unfold[F,S,O2](seed, next andThen (_.map(f)))

    def flatMapU[G[_],O2>:O](tl: O => Proc6[G,O2])(implicit E: Sub1[F,G]): Unfold[G,JoinS[G,S,O2],O2] =
      ???

    def step: Step[F,Proc6[F,O],O] = Step.safe {
      next(seed) match {
        case h@Halt(_) => h
        case Await(req,recv) => Await(req, recv andThen (seed2 => unfold(seed2)(next)))
        case Emit(hd,tl) => Emit(hd, unfold(tl)(next))
      }
    }
  }

  sealed trait Step[+F[_],+S,+O] {
    def map[O2](f: O => O2): Step[F,S,O2] = this match {
      case h@Halt(_) => h
      case Emit(h,t) => safe { Emit(h.map(f), t) }
      case a@Await(_,_) => a
    }
    def mapS[S2](f: S => S2): Step[F,S2,O] = this match {
      case h@Halt(_) => h
      case Emit(h, t) => safe { Emit(h, f(t)) }
      case Await(req,recv) => Await(req, recv andThen f)
    }
    def onHalt[G[_],S2,O2>:O](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[F,G]):
      Step[G,OnHaltS[G,S,S2,O2],O2]
    def flatMap[G[_],O2](f: O => Proc6[G,O2])(implicit E: Sub1[F,G]):
      Step[G,JoinS[G,S,O2],O2]
    def fold[R](halt: Throwable => R,
                emit: (Seq[O], S) => R,
                await: (F[Any], (Throwable \/ Any) => S) => R): R
  }
  object Step {
    def safe[F[_],S,O](s: => Step[F,S,O]): Step[F,S,O] =
      try s
      catch { case t: Throwable => Halt(t) }

    case class Halt(cause: Throwable) extends Step[Nothing,Nothing,Nothing] {
      def onHalt[G[_],S2,O2>:Nothing](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[Nothing,G]):
        Step[G,OnHaltS[G,Nothing,S2,O2],O2] = Step.safe {
          val tl = f(cause)
          Emit(Seq(), right(tl.seed -> tl.next))
        }
      def flatMap[G[_],O2](f: Nothing => Proc6[G,O2])(implicit E: Sub1[Nothing,G]): Step[G,JoinS[G,Nothing,O2],O2] =
        this
      def fold[R](halt: Throwable => R,
                  emit: (Seq[Nothing], Nothing) => R,
                  await: (Nothing, (Throwable \/ Any) => Nothing) => R): R = halt(cause)
    }
    case class Emit[S,+O](head: Seq[O], tail: S) extends Step[Nothing,S,O] {
      def onHalt[G[_],S2,O2>:O](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[Nothing,G]):
        Step[G,OnHaltS[G,S,S2,O2],O2] =
          Emit(head, left(tail))
      def flatMap[G[_],O2](f: O => Proc6[G,O2])(implicit E: Sub1[Nothing,G]): Step[G,JoinS[G,S,O2],O2] =
        Step.safe {
          val hds: Proc6[G,O2] = head.map(f).reverse.foldLeft(halt: Proc6[G,O2])((tl,hd) => hd ++ tl)
          ???
          // unfold(tail -> hds)
        }
      def fold[R](halt: Throwable => R,
                  emit: (Seq[O], S) => R,
                  await: (Nothing, (Throwable \/ Any) => S) => R): R = emit(head, tail)
    }
    case class Await[F[_],Z,S](req: F[Z], recv: Throwable \/ Z => S) extends Step[F,S,Nothing] {
      def onHalt[G[_],S2,O2>:Nothing](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[F,G]):
        Step[G,OnHaltS[G,S,S2,O2],O2] = Await(E(req), recv andThen left)
      def flatMap[G[_],O2](f: Nothing => Proc6[G,O2])(implicit E: Sub1[F,G]): Step[G,JoinS[G,S,O2],O2] =
        E.substep(this.mapS(s => (s, halt)))
      def fold[R](halt: Throwable => R,
                  emit: (Seq[Nothing], S) => R,
                  await: (F[Any], (Throwable \/ Any) => S) => R): R =
        await(req.asInstanceOf[F[Any]], recv.asInstanceOf[Throwable \/ Any => S])
    }
  }

  def emitAll[O](o: Seq[O]): Proc6[Nothing,O] =
    unfold(false) {
      case false => Emit(o, true)
      case true => Halt(End)
    }

  def emit[O](o: O): Proc6[Nothing,O] =
    emitAll(Vector(o))

  def cons[F[_],O](head: Seq[O], tail: Proc6[F,O]): Proc6[F,O] =
    emitAll(head) ++ tail

  def unfold[F[_],S,O](seed: S)(f: S => Step[F,S,O]): Proc6[F,O] =
    Unfold(seed, f)

  def fail(cause: Throwable): Proc6[Nothing,Nothing] =
    unfold(())(_ => Halt(cause))

  val halt = fail(End)

  private[stream] val kill = fail(Kill)

  def suspend[F[_],O](p: => Proc6[F,O]): Proc6[F,O] =
    halt ++ p

  def lazily[F[_],O](p: => Proc6[F,O]): Proc6[F,O] = {
    lazy val pmemo = p
    suspend(pmemo)
  }

  /**
   * Special exception indicating normal termination due to
   * input ('upstream') termination. An `Await` may respond to an `End`
   * by switching to reads from a secondary source.
   */
  case object End extends Exception {
    override def fillInStackTrace = this
  }

  /**
   * Special exception indicating downstream termination.
   * An `Await` should respond to a `Kill` by performing
   * necessary cleanup actions, then halting.
   */
  case object Kill extends Exception {
    override def fillInStackTrace = this
  }

  class CausedBy(top: Throwable, cause: Throwable) extends Throwable {
    override def fillInStackTrace = this
  }
  object CausedBy {
    def apply(top: Throwable, cause: Throwable): Throwable = top match {
      case End => cause
      case _ => cause match {
        case End => top
        case _ => new CausedBy(top, cause)
      }
    }
  }

  sealed trait Sub1[-F[_],+G[_]] {
    def apply[A](f: F[A]): G[A]
    def subprocess[A](p: Proc6[F,A]): Proc6[G,A] =
      Sub1.subst[F, G, ({type f[g[_]] = Proc6[g,A]})#f](p)(this)
    def substep[S,A](p: Step[F,S,A]): Step[G,S,A] =
      Sub1.subst[F, G, ({type f[g[_]] = Step[g,S,A]})#f](p)(this)
  }
  object Sub1 {
    implicit def refl[F[_]]: Sub1[F,F] = new Sub1[F,F] {
      def apply[A](f: F[A]): F[A] = f
    }
    implicit def nothing[F[_]]: Sub1[Nothing,F] = new Sub1[Nothing,F] {
      def apply[A](f: Nothing): F[A] = f
    }
    def subst[F[_],G[_],Ctx[_[_]]](ctx: Ctx[F])(implicit E: Sub1[F,G]): Ctx[G] =
      ctx.asInstanceOf[Ctx[G]]
  }
}

