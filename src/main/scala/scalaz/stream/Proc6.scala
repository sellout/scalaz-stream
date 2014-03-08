package scalaz.stream

import scalaz.{\/, ~>, Catchable, Monoid, Monad}
import scalaz.\/.{left,right}
import scalaz.concurrent.Task
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

  def onHalt[G[x]>:F[x],O2>:O](tl: Throwable => Proc6[G,O2]): Proc6[G,O2] =
    unfoldT((this: Proc6[G,O2]) -> false) { p => Trampoline.suspend {
      val cur = p._1; val onTail = p._2
      cur.stepFold(
        err => if (onTail) Halt(err) else Emit(Vector.empty, tl(err) -> true),
        (h,t) => Emit(h, t -> onTail),
        (req,recv) => Await(req, recv andThen ((_, onTail)))
      )
    }}

  def ++[G[x]>:F[x],O2>:O](tl: => Proc6[G,O2]): Proc6[G,O2] =
    this.onHalt {
      case End => tl
      case err => fail(err)
    }

  def onComplete[G[x]>:F[x],O2>:O](tl: => Proc6[G,O2]): Proc6[G,O2] =
    this.onHalt {
      case End => tl
      case err => tl.onHalt { err2 => fail(CausedBy(err2, err)) }
    }

  def map[O2](f: O => O2): Proc6[F,O2]

  def step: Trampoline[Step[F,Proc6[F,O],O]]

  def stepFold[R](halt: Throwable => R,
                  emit: (Seq[O], Proc6[F,O]) => R,
                  await: (F[Any], (Throwable \/ Any) => Proc6[F,O]) => R): Trampoline[R] =
    step.map(_.fold(halt, emit, await))

  def flatMap[G[x]>:F[x],O2](f: O => Proc6[G,O2]): Proc6[G,O2] = {
    val p: Proc6[G,Proc6[G,O2]] = this.map(f)
    unfoldT(p -> (Nil: Seq[Proc6[G,O2]])) { p =>
      val outer = p._1; val inner = p._2
      inner match {
        case x if x.isEmpty => outer.stepFold(
          erro => Halt(erro),
          (h,t) => if (h.isEmpty) Emit(Vector.empty, (t -> Nil))
                   else Emit(Vector.empty, t -> h),
          (req,recv) => Await(req, recv andThen (_ -> Nil))
        )
        case curt => val h = curt.head; val t = curt.tail; h.stepFold(
          { case End => Emit(Vector.empty, outer -> t)
            case err => Halt(err)
          },
          (curHd, curTl) => Emit(curHd, outer -> (curTl +: t)),
          (req,recv) => Await(req, recv andThen (h2 => outer -> (h2 +: t)))
        )
      }
    }
  }

  def runFoldMap[G[x]>:F[x],O2](f: O => O2)(implicit M: Monoid[O2], G: Monad[G], C: Catchable[G]): G[O2] = {
    def go(acc: O2, cur: Proc6[G,O2]): G[O2] = suspendF {
      cur.step.run.fold(
        { case End => G.point(acc); case err: Throwable => C.fail(err) },
        (hd,tl) => go(hd.foldLeft(M.zero)(M.append(_,_)), tl),
        (req,recv) => G.bind(C.attempt(req))(recv andThen (next => go(acc, next)))
      )
    }
    go(M.zero, this.map(f))
  }
}

object Proc6 {
  import Step._

  type Trampoline[+A] = Task[A]
  val Trampoline = Task

  def suspendF[F[_],A](fa: => F[A])(implicit F: Monad[F]): F[A] =
    F.bind(F.point(()))(_ => fa)


  type OnHaltS[F[_],+S,S2,O] = S \/ (S2, S2 => Trampoline[Step[F,S2,O]])

  case class Unfold[+F[_],S,+O](seed: S, next: S => Trampoline[Step[F,S,O]]) extends Proc6[F,O] {
    def onHaltU[G[x]>:F[x],S2,O2>:O](tl: Throwable => Unfold[G,S2,O2]):
    Unfold[G,OnHaltS[G,S,S2,O2],O2] = {
      Unfold(left(seed), e => Task.suspend { e.fold(
        s0 => next(s0).map(_.onHalt(tl)),
        { p => val s2 = p._1
               val next2 = p._2
               next2(s2).map(_.mapS(s2 => right(s2 -> next2))) }
      )})
    }
    //def onHalt[G[x]>:F[x],O2>:O](tl: Throwable => Proc6[G,O2]):
    //  Proc6[G,O2] = onHaltU(tl.asInstanceOf[Throwable => Unfold[G,Any,O2]])

    def map[O2](f: O => O2): Unfold[F,S,O2] = Unfold[F,S,O2](seed, s => Task.suspend(next(s)).map(_.map(f)))

    def step: Trampoline[Step[F,Proc6[F,O],O]] = Trampoline.suspend { next(seed).map {
      case h@Halt(_) => h
      case Await(req,recv) => Await(req, recv andThen (seed2 => Unfold(seed2, next)))
      case Emit(hd,tl) => Emit(hd, Unfold(tl, next))
    }}
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
      def fold[R](halt: Throwable => R,
                  emit: (Seq[Nothing], Nothing) => R,
                  await: (Nothing, (Throwable \/ Any) => Nothing) => R): R = halt(cause)
    }
    case class Emit[S,+O](head: Seq[O], tail: S) extends Step[Nothing,S,O] {
      def onHalt[G[_],S2,O2>:O](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[Nothing,G]):
        Step[G,OnHaltS[G,S,S2,O2],O2] =
          Emit(head, left(tail))
      def fold[R](halt: Throwable => R,
                  emit: (Seq[O], S) => R,
                  await: (Nothing, (Throwable \/ Any) => S) => R): R = emit(head, tail)
    }
    case class Await[F[_],Z,S](req: F[Z], recv: Throwable \/ Z => S) extends Step[F,S,Nothing] {
      def onHalt[G[_],S2,O2>:Nothing](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[F,G]):
        Step[G,OnHaltS[G,S,S2,O2],O2] = Await(E(req), recv andThen left)
      def fold[R](halt: Throwable => R,
                  emit: (Seq[Nothing], S) => R,
                  await: (F[Any], (Throwable \/ Any) => S) => R): R =
        await(req.asInstanceOf[F[Any]], recv.asInstanceOf[Throwable \/ Any => S])
    }
  }

  def emitAll[O](o: Seq[O]): Proc6[Nothing,O] =
    unfoldT(false) {
      case false => Task.now(Emit(o, true))
      case true => Task.now(Halt(End))
    }

  def emit[O](o: O): Proc6[Nothing,O] =
    emitAll(Vector(o))

  def cons[F[_],O](head: Seq[O], tail: Proc6[F,O]): Proc6[F,O] =
    emitAll(head) ++ tail

  def unfoldT[F[_],S,O](seed: S)(f: S => Trampoline[Step[F,S,O]]): Proc6[F,O] =
    Unfold(seed, f)

  def fail(cause: Throwable): Proc6[Nothing,Nothing] =
    unfoldT(())(_ => Task.now(Halt(cause)))

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

