//package scalaz.stream
//
//import scalaz.concurrent.Task
//import scalaz.{~>, \/, Catchable, Monad, Monoid}
//import scalaz.syntax.monad._
//import scalaz.syntax.monoid._
//import \/.{left,right}
//
//trait Proc4[+F[_],+O] {
//  import Proc4._
//
//  def Match[G[_]:Monad:Catchable,R:Monoid](
//    emit: Seq[O] => G[R],
//    await: (F ~> G) => G[R],
//    halt: Throwable \/ R => G[R]): G[R]
//}
//
///*
//trait Proc5[+F[_],+O] {
//  import Proc4._
//
//  def stepFoldMap[G[_]:Monad:Catchable,R:Monoid](
//    fg: F ~> G)(f: O => G[R]): G[(R,Proc5[F,O])]
//}
//
//trait Proc5[+F[_],+O] {
//  import Proc4._
//
//  def runFoldMap[G[_]:Monad:Catchable,R:Monoid](
//    fg: F ~> G)(f: O => R): G[R]
//}
//
//data Stream f a
//  = forall s. Stream s (s -> Step f a s)
//
//data Step f a s
//  = Halt Err
//  | Emit [a] s
//  | forall z. Await (f z) (Either Err z -> s)
//
//instance Functor (Step f a) where ...
//
//emit :: a -> Stream f a
//emit a = Stream False go where
//  go False = Emit [a] True
//  go True = Halt
//
//append :: Stream f a -> Stream f a
//append (Stream s1 f) (Stream s2 g) = Stream (Just s1, s2) go where
//  go (Nothing, s2) = (,) Nothing <$> g s2
//  go (Just s1, s2) = h s2 <$> f s1
//  h s2 s1 = (Just s1, s2)
//
//map :: (a -> b) -> Stream f a -> Stream f b
//map f (Stream s1 g) = Stream s1 (go . g) where
//  go Emit as s = Emit (fmap f as) s
//  go h@(Halt err) = Halt err
//  go a@(Await req recv) = a
//
//empty :: Stream f a
//empty = Stream () (const (Halt err))
//
//concat :: Stream f (Stream f a) -> Stream f a
//concat (Stream s f) = Stream (s, Nothing) go where
//  go :: (s, Maybe (Stream f a))
//  go (s, Nothing) = case f s of
//    Halt err -> Halt err
//    Emit streams so = Emit [] (so, Just (foldr append empty streams))
//    Await req g = Await req (\s -> (s, Nothing) . g)
//
//eval :: f a -> Stream f a
//eval f = Stream (Nothing, False) go where
//  go (Nothing, False) = Await f (\a -> (Just a, True))
//  go (Just a) = Emit [a] (Nothing, True)
//  go (_, True) = Halt err
//
//onHalt :: Stream f a -> (Err -> Stream f a) -> Stream f a
//onHalt = -- trivial, just like append; state is (Maybe s, Either (Err -> Stream f a, Stream f a))
//
//yonedify the process - Proc f a = Proc1 a b -> Proc f b
//this would mean proc1 and proc are not the same type, though,
//or would it?
//proc can be run in one of two modes - step, or stream
//
//data Proc f o =
//  Proc
//    (
//
//*/
//
//object Proc4 {
//  type Trampoline[+A] = Task[A]
//  type T[+A] = Task[A]
//  val Trampoline = Task
//  val T = Task
//
//  trait HandleAwait[-F[_],R] {
//    def apply[G[_]:Monad:Catchable,Z](req: F[Z]): G[R]
//  }
//
//  private def attempt[G[_]:Catchable,O](g: G[O]): G[Throwable \/ O] =
//    Catchable[G].attempt(g)
//
//  def fail(err: Throwable): Proc4[Nothing,Nothing] =
//    new Proc4[Nothing,Nothing] {
//      def Match[G[_]:Monad:Catchable,R:Monoid](
//        e: Seq[Nothing] => G[R],
//        a: (F ~> G) => G[R],
//        h: Throwable \/ R => G[R]): G[R] = h(left(err))
//    }
//
//  val halt = fail(End)
//
//  def emit[O](o: O): Proc4[Nothing,O] = emitAll(Vector(o))
//
//  def emitAll[O](out: Seq[O]): Proc4[Nothing,O] = new Proc4[Nothing,O] {
//    def Match[G[_]:Monad:Catchable,R:Monoid](
//      e: Seq[O] => G[R],
//      a: (Nothing ~> G) => G[R],
//      h: Throwable \/ R => G[R]): G[R] =
//      attempt(e(out)).flatMap(h)
//  }
//
//  def eval[F[_],O](req: F[O]): Proc4[F,O] = new Proc4[F,O] {
//    def Match[G[_]:Monad:Catchable,R:Monoid](
//      e: Seq[O] => G[R],
//      a: (F ~> G) => G[R],
//      h: Throwable \/ R => G[R]): G[R] =
//      a(req)
//  }
//
//  def suspendM[F[_]:Monad,O](f: => F[O]): F[O] =
//    ().point.flatMap(_ => f)
//
//  def onHalt[F[_],O](p1: Proc4[F,O], p2: Throwable => Proc4[F,O]): Proc4[F,O] =
//    new Proc4[F,O] {
//      def Match[G[_]:Monad:Catchable,R:Monoid](
//        e: Seq[O] => G[R],
//        a: F[O] => G[R],
//        h: Throwable \/ R => G[R]): G[R] = suspendM[G,R] {
//        attempt(p1.Match(e,a,h)).flatMap(
//          _.fold(err => p2(err).Match(e,a,h),
//                 r1 => p2(End).Match(e,a,h).map(r2 => r1 |+| r2))
//        )
//      }
//    }
//  def append[F[_],O](p1: Proc4[F,O], p2: => Proc4[F,O]): Proc4[F,O] =
//    onHalt(p1, { case End => p2; case err => fail(err)})
//
//  def bind[F[_],O,O2](p1: Proc4[F,O])(f: O => Proc4[F,O2]): Proc4[F,O2] =
//    new Proc4[F,O2] {
//      def Match[G[_]:Monad:Catchable,R:Monoid](
//        e: Seq[O2] => G[R],
//        a: (F ~> G) => G[R],
//        h: Throwable \/ R => G[R]): G[R] = suspendM[G,R] {
//        p1.Match(
//          emit = os => os.map(f).reverse.foldLeft(halt: Proc4[F,O2])(
//                         (tl,hd) => append(hd,tl)
//                       ).Match(e,a,h),
//          await = (fg: F ~> G) o: F[O]) =>
//          err => h(err)
//        )
//      }
//    }
////
////  def suspend[F[_],O](p: => Proc4[F,O]): Proc4[F,O] =
////    new Proc4[F,O] {
////      def Match[R](
////        e: (Seq[O], T[R]) => T[R],
////        a: HandleAwait[F,R],
////        h: Throwable => T[R]): T[R] = Task.suspend(p.Match(e,a,h))
////    }
//
//  /**
//   * Special exception indicating normal termination due to
//   * input ('upstream') termination. An `Await` may respond to an `End`
//   * by switching to reads from a secondary source.
//   */
//  case object End extends Exception {
//    override def fillInStackTrace = this
//  }
//
//  /**
//   * Special exception indicating downstream termination.
//   * An `Await` should respond to a `Kill` by performing
//   * necessary cleanup actions, then halting.
//   */
//  case object Kill extends Exception {
//    override def fillInStackTrace = this
//  }
//
//}
