package scalaz.stream

import scalaz.concurrent.Task
import scalaz.\/

trait Proc4[+F[_],+O] {
  import Proc4._

  def Match[R](
    emit: (Seq[O], T[R]) => T[R],
    await: HandleAwait[F,R],
    halt: Throwable => T[R]): T[R]
}

object Proc4 {
  type Trampoline[+A] = Task[A]
  type T[+A] = Task[A]
  val Trampoline = Task
  val T = Task

  trait HandleAwait[-F[_],R] {
    def apply[Z](req: F[Z], recv: Throwable \/ Z => T[R]): T[R]
  }

  def fail(err: Throwable): Proc4[Nothing,Nothing] = new Proc4[Nothing,Nothing] {
    def Match[R](
      emit: (Seq[Nothing], T[R]) => T[R],
      await: HandleAwait[Nothing,R],
      halt: Throwable => T[R]): T[R] = halt(err)
  }
  val halt = fail(End)

  def emit[O](o: O): Proc4[Nothing,O] = emitAll(Vector(o))

  def emitAll[O](out: Seq[O]): Proc4[Nothing,O] = new Proc4[Nothing,O] {
    def Match[R](
      e: (Seq[O], T[R]) => T[R],
      a: HandleAwait[Nothing,R],
      h: Throwable => T[R]): T[R] =
      halt.Match(e,a,h).flatMap(r => e(out, Task.now(r)))
  }

  def eval[F[_],O](req: F[O]): Proc4[F,O] = new Proc4[F,O] {
    def Match[R](
      e: (Seq[O], T[R]) => T[R],
      a: HandleAwait[F,R],
      h: Throwable => T[R]): T[R] =
      a[O](req, (res: Throwable \/ O) => T.suspend { res.fold(fail, emit).Match(e,a,h) })
  }

  def onHalt[F[_],O](p1: Proc4[F,O], p2: Throwable => Proc4[F,O]): Proc4[F,O] =
    new Proc4[F,O] {
      def Match[R](
        e: (Seq[O], T[R]) => T[R],
        a: HandleAwait[F,R],
        h: Throwable => T[R]): T[R] = {
        ??? // 'headsplode
        // need to call p1 with some properly jiggered arguments
        // such that p2 gets invoked
      }
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
