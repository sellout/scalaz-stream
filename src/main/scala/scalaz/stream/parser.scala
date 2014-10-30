package scalaz.stream

import scalaz.~>

object parser {

  type Parser1[-I,+O] = Process[Env[I,Any]#Is, O]
  type ParserT[-I,-I2,+O] = Process[Env[I,I2]#T, O]
  type ParserY[-I,-I2,+O] = Process[Env[I,I2]#Y, O]

  case class ParseMessage(stack: List[String]) extends Throwable {
    def ::(msg: String) = copy(stack = msg :: stack)
    override def fillInStackTrace = this
  }

  //def run1[A,B](p: Parser1[A,B]): Process1[A,B] = {
  //  def go(buf: Vector[A], p: Parser1[A,B]): Process1[A,B] = p.suspendStep.flatMap {
  //    case h@Process.Halt(_) => h
  //    case Step(ea, )
  //  }
  //}

  /** Await a single input, but leave it unconsumed. */
  def peek1[A]: Parser1[A,A] = Process.await(Get[A])(a => Process.emit(a))

  /** Await a single input from the left branch, but leave it unconsumed. */
  def peekL[A]: ParserT[A,Any,A] = peek1[A]

  /** Await a single input from the right branch, but leave it unconsumed. */
  def peekR[A]: ParserT[Any,A,A] = Process.await(R[A])(a => Process.emit(a))

  /** Await a single input and consume this and any previously peeked input. */
  def await1[A]: Parser1[A,A] = attempt1[A].flatMap { a => commit1 ++ Process.emit(a) }

  /** Await a single input and consume this and any previous peeked input. */
  def awaitL[A]: Parser1[A,A] = attempt1[A].flatMap { a => commit1 ++ Process.emit(a) }

  /** Await a single input and commit after it has been consumed. */
  def attempt1[A]: Parser1[A,A] = peek1[A] ++ commit1

  /** Await a single input from the left and commit after it has been consumed. */
  def attemptL[A]: ParserT[A,Any,A] = attempt1[A]

  /** Await a single input from the right and commit after it has been consumed. */
  def attemptR[A]: ParserT[Any,A,A] = peekR[A] ++ commit1

  /**
   * The parser that consumes no input and switches branches immediately. If there
   * are no other branches currently active to consume buffered input, parsing fails.
   */
  val suspend: Process[Env[Any,Any]#Is,Nothing] = Process.eval_(Suspend(ParseMessage(Nil)))

  /** In the event that `p` fails with a `ParseMessage`, pushes `msg` onto the stack. */
  def scope[X,Y,A](msg: String)(p: ParserT[X,Y,A]): ParserT[X,Y,A] =
    p.translate (new (Env[X,Y]#T ~> Env[X,Y]#T) {
      def apply[a](e: Env[X,Y]#T[a]) = {
        def cast[p](e: Env[X,Y]#T[p]): Env[X,Y]#T[a] = e.asInstanceOf[Env[X,Y]#T[a]]
        e.fold(cast(L[X]), cast(R[Y]), sys.error("unpossible"),
               cast(CommitL), cast(CommitR), stack => cast(Suspend(msg :: stack)))
      }
    })

  def or[I1,I2,O](p: ParserT[I1,I2,O], p2: ParserT[I1,I2,O]): ParserT[I1,I2,O] =
    // run p until it suspends, then run p2 until it suspends, then run p, etc
    // if at any point a buffered element goes unconsumed this is an error
    ???

  private def commit1: Process[Env[Any,Any]#Is,Nothing] = Process.eval_(CommitL)
  private def commitL: Process[Env[Any,Any]#Is,Nothing] = Process.eval_(CommitL)
  private def commitR: Process[Env[Any,Any]#T,Nothing] = Process.eval_(CommitR)

  case class Env[-I, -I2]() {
    sealed trait Y[-X] {
      private[stream] def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R
    }
    sealed trait T[-X] extends Y[X]
    sealed trait Is[-X] extends T[X]
    case object Left extends Is[I] {
      def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R = l
    }
    case object Right extends T[I2] {
      def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R = r
    }
    case object Both extends Y[ReceiveY[I, I2]] {
      def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R = both
    }
    case object CommitL extends Is[Unit] {
      def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R = commitL
    }
    case object CommitR extends T[Unit] {
      def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R = commitR
    }
    def Suspend(reason: ParseMessage) = new Is[Unit] {
      def fold[R](l: => R, r: => R, both: => R, commitL: => R, commitR: => R, suspend: ParseMessage => R): R =
        suspend(reason)
    }
  }

  private val Left_  = Env[Any, Any]().Left
  private val Right_ = Env[Any, Any]().Right
  private val Both_  = Env[Any, Any]().Both
  private val CommitL_ = Env[Any, Any]().CommitL
  private val CommitR_ = Env[Any, Any]().CommitR

  def Suspend(reason: ParseMessage): Env[Any,Any]#Is[Unit] =
    Env().Suspend(reason)

  def Get[I]: Env[I, Any]#Is[I] = Left_
  def L[I]: Env[I, Any]#Is[I] = Left_
  def R[I2]: Env[Any, I2]#T[I2] = Right_
  def Both[I, I2]: Env[I, I2]#Y[ReceiveY[Any, Any]] = Both_
  private val CommitL: Env[Any,Any]#Is[Unit] = CommitL_
  private val CommitR: Env[Any,Any]#T[Unit] = CommitR_
}
