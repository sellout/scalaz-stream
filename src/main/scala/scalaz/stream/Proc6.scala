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

  def onHalt[G[_],O2>:O](tl: Throwable => Proc6[G,O2])(implicit S: Sub1[F,G]):
    Proc6[G,O2]
}

object Proc6 {
  import Step._

  type OnHaltS[F[_],+S,S2,O] = S \/ (S2, S2 => Step[F,S2,O])

  case class Unfold[+F[_],S,+O](seed: S, next: S => Step[F,S,O]) extends Proc6[F,O] {
    def onHaltU[G[_],S2,O2>:O](tl: Throwable => Unfold[G,S2,O2])(implicit S: Sub1[F,G]):
    Unfold[G,OnHaltS[G,S,S2,O2],O2] = {
      Unfold(left(seed), _.fold(
        s0 => S.substep(next(s0)).onHalt(tl),
        { p => p._2(p._1).mapS(s2 => right(s2 -> p._2)) }
      ))
    }
    def onHalt[G[_],O2>:O](tl: Throwable => Proc6[G,O2])(implicit S: Sub1[F,G]):
      Proc6[G,O2] = onHaltU(tl.asInstanceOf[Throwable => Unfold[G,Any,O2]])
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
    def subst[F[_],G[_],Ctx[_[_]]](ctx: Ctx[F])(implicit S: Sub1[F,G]): Ctx[G] =
      ctx.asInstanceOf[Ctx[G]]
  }

  trait Step[+F[_],+S,+O] {
    def map[O2](f: O => O2): Step[F,S,O2] = this match {
      case h@Halt(_) => h
      case Emit(h,t) => safe { Emit(h.map(f), t) }
      case a@Await(_,_) => a
    }
    def mapS[S2](f: S => S2): Step[F,S2,O] = this match {
      case h@Halt(_) => h
      case Emit(h, t) => safe { Emit(h, t.map(f)) }
      case Await(req,recv) => Await(req, recv andThen (_.map(f)))
    }
    def onHalt[G[_],S2,O2>:O](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[F,G]):
      Step[G,OnHaltS[G,S,S2,O2],O2]
  }
  object Step {
    def safe[F[_],S,O](s: => Step[F,S,O]): Step[F,S,O] =
      try s
      catch { case t: Throwable => Halt(t) }

    case class Halt(cause: Throwable) extends Step[Nothing,Nothing,Nothing] {
      def onHalt[G[_],S2,O2>:Nothing](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[Nothing,G]):
        Step[G,OnHaltS[G,Nothing,S2,O2],O2] = Step.safe {
          val tl = f(cause)
          Emit(Seq(), Some(right(tl.seed -> tl.next)))
        }
    }
    case class Emit[S,+O](head: Seq[O], tail: Option[S]) extends Step[Nothing,S,O] {
      def onHalt[G[_],S2,O2>:O](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[Nothing,G]):
        Step[G,OnHaltS[G,S,S2,O2],O2] =
          Emit(head, tail.map(left))
    }
    case class Await[F[_],Z,S](req: F[Z], recv: Throwable \/ Z => Option[S]) extends Step[F,S,Nothing] {
      def onHalt[G[_],S2,O2>:Nothing](f: Throwable => Unfold[G,S2,O2])(implicit E: Sub1[F,G]):
        Step[G,OnHaltS[G,S,S2,O2],O2] = Await(E(req), recv andThen (_.map(left)))
    }
  }

  def cons[F[_],O](head: Seq[O], tail: Proc6[F,O]): Proc6[F,O] =
    ???

  //trait Spine[+F[_],+S,+O] {
  //  def head: S
  //  def tail: Throwable => Proc6[F,O]
  //}
  //object Spine {
  //  def apply[F[_],S,O](hd: S, tl: Throwable => Proc6[F,O]): Spine[F,S,O] =
  //    trait Spine[+F[_],+S,+O] {
  //      def head = hd
  //      def tail = tl
  //    }
  //}

  // need to be super explicit about state evolution
  // for onHalt, we have three states -
  // data OnHaltS f s o
  //   = Head s (s -> Proc f o) | Got (Err -> Proc f o) | forall s . Tail s (s -> Step f s o)
  //def join[F[_],O](p: Proc6[F,Proc6[F,O]]): Proc6[F,O] = p match {
  //  case Unfold(z,f) => Unfold(Spine)
  //}

  // state is Either (s, Err => Proc f o) (s, s -> Step f s o)
  def onHalt[F[_],O](p1: Proc6[F,O])(f: PartialFunction[Throwable, Proc6[F,O]]):
  Proc6[F,O] = {
    type S = (Any, PartialFunction[Throwable, Proc6[F,O]]) \/
             (Any, Any => Step[F,Any,O])
    p1 match {
      case Unfold(seed,g) => ???
        //def go(s: S) =
        //  s.fold(
        //    { case (s1, f) => g(s1).mapS(a => left(a -> f): S) },
        //    { case (s2, g2) => g2(s2).mapS(a => right(a -> g2)) }
        //  )
        //go(left(seed -> f))
    }
  }

  def unfold[F[_],S,O](seed: S)(f: S => Step[F,S,O]): Proc6[F,O] =
    Unfold(seed, f)

  def fail(cause: Throwable): Proc6[Nothing,Nothing] =
    unfold(())(_ => Halt(cause))

  val halt = fail(End)

  private[stream] val kill = fail(Kill)

  def suspend[F[_],O](p: => Proc6[F,O]): Proc6[F,O] =
    onHalt(fail(End)) { case End => p }

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
}

// vim: set ts=4 sw=4 et:
