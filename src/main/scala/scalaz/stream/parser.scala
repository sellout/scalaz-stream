package scalaz.stream

import scalaz.~>
import scalaz.\/.{left,right}
import Process.Env

object parser {

  import Instruction._

  /** A parse result. */
  sealed trait Instruction[+F[_],+O] {
    def map[O2](f: O => O2): Instruction[F,O2] = this match {
      case Emit1(o) => Emit1(f(o))
      case c@Commit(_) => c
      case s@Suspend(_,_) => s
    }
  }

  object Instruction {
    sealed trait Side
    object Side {
      case object L extends Side
      case object R extends Side
    }
    private[stream] case class Suspend[+F[_]](msg: ParseMessage, unparsed: Input[F])
      extends Instruction[F,Nothing]
    private[stream] case class Commit(side: Side)
      extends Instruction[Nothing,Nothing]
    private[stream] case class Emit1[+O](emit: O)
      extends Instruction[Nothing,O]
  }

  sealed trait Input[+F[_]] {
    def feed[F2[x]>:F[x],O](p: Process[F2,O]): Process[F2,O]
    def nonEmpty: Boolean
  }
  object Input {
    val empty: Input[Nothing] = new Input[Nothing] {
      def feed[F2[x]>:Nothing,O](p: Process[F2,O]) = p
      def nonEmpty: Boolean = false
    }
    case class T[I1,I2](lefts: Vector[I1], rights: Vector[I2]) extends Input[Env[I1,I2]#T] {
      def feed[F2[x]>:Env[I1,I2]#T[x],O](p: Process[F2,O]) =
        // todo: feed left, then right, then unemit
        ???
      def nonEmpty = lefts.nonEmpty || rights.nonEmpty
    }
  }

  //def broadcast[A,B](p1: Process1[A,B], p2: Process1[A,B]): Process1[A,B] = {
  //  val (p1p, p2p) = (fairPeeker[A].pipe(p1), fairPeeker[A].pipe(p2))
  //  run1 { or(or(p1p, p2p), default) }
  //}

  //def peeker[A]: Parser1[A,A] = peek1[A].repeat
  //def fairPeeker[A]: Parser1[A,A] = peek1[A].flatMap { a => suspend ++ emit(a) }.repeat

  //def lift[A,B](p: Process1[A,B]): Parser1[A,B] = ???

  case class Parser[+F[_],+O](process: Process[F,Instruction[F,O]]) {
    final def append[F2[x] >: F[x], O2 >: O](p2: => Parser[F2, O2]): Parser[F2, O2] =
      edit(_ ++ p2.process)
    def edit[F2[x] >: F[x], O2](f: Process[F,Instruction[F,O]] => Process[F2,Instruction[F2,O2]]): Parser[F2,O2] =
      Parser(f(process))

    final def flatMap[F2[x] >: F[x], O2](f: O => Parser[F2, O2]): Parser[F2, O2] = Parser {
      process.flatMap {
        case Emit1(o) => f(o).process
        case c@Commit(_) => Process.emit(c)
        case e@Suspend(_,_) => Process.emit(e)
      }
    }
    final def map[O2](f: O => O2): Parser[F,O2] =
      edit(_ map (_ map f))
    final def repeat: Parser[F,O] = edit { _.repeat }

    final def ++[F2[x] >: F[x], O2 >: O](p2: => Parser[F2, O2]): Parser[F2, O2] =
      append(p2)
  }

  type Parser1[-I,+O] = Parser[Env[I,Any]#Is, O]
  type ParserT[-I,-I2,+O] = Parser[Env[I,I2]#T,O]
  type ParserY[-I,-I2,+O] = Parser[Env[I,I2]#Y,O]

  case class ParseMessage(stack: List[String]) {
    def ::(msg: String) = copy(stack = msg :: stack)
    override def toString = stack.mkString("\n")
  }
  case class ParseError(unparsedL: Vector[Any],
                        unparsedR: Vector[Any],
                        message: ParseMessage) extends Throwable {
    override def fillInStackTrace = this
    override def toString = {
      val l = unparsedL.mkString(", ")
      val r = unparsedR.mkString(", ")
      s"unparsed input:\n$l\n$r\nstack:\n$message"
    }
  }

  def or[F[_],O](p: Parser[F,O], p2: Parser[F,O]): Parser[F,O] = Parser {
    // need to make sure that the suspend operations are accurate
    // might be simpler to just have a separate Record instruction
    def go(
        out: Seq[Instruction[F,O]],
        emitSuspend: Boolean,
        p: Process[F,Instruction[F,O]],
        p2: Process[F,Instruction[F,O]]): Process[F,Instruction[F,O]] = out match {
      case Seq(Emit1(_), tl@_*) =>
        val emitstl = out.span { case Emit1(o) => true; case _ => false }
        Process.emitAll(emitstl._1) ++ go(emitstl._2, emitSuspend, p, p2)
      case Seq(Commit(_), tl@_*) =>
        go(tl, false, p, p2)
      case Seq(s0@Suspend(_, _), tl@_*) =>
        val s = s0.asInstanceOf[Instruction[F,O]] // Scala fail here, this cast should not be needed
        if (emitSuspend && s0.unparsed.nonEmpty) Process.emit(s) ++ go(tl, false, p2, p)
        else if (emitSuspend && !s0.unparsed.nonEmpty) go(tl, false, p2, p)
        else go(tl, true, p2, p)
      case _ if out.isEmpty =>
        p.step.haltOrStep (
          { case Cause.End => p2
            case c => p2.kill.causedBy(c) },
          s => s.head.emitOrAwait(
            out => go(out, emitSuspend, s.next.continue, p2),
            new Process.HandleAwait[F,Instruction[F,O], Process[F,Instruction[F,O]]] { def apply[x] =
              (req, rcv) => Process.awaitOr[F,x,Instruction[F,O]](req)(
                e => go(out, emitSuspend, rcv(left(e)).run, p2))(
                a => go(out, emitSuspend, rcv(right(a)).run, p2)
              )
            })
        )
    }

    // when p suspends, run p2 with leftover; if it suspends with nonempty input
    // before committing, we emit the Suspend and switch to the first branch
    // if p2 commits before suspend, then we switch to `p` as normal!
    go(Vector.empty, false, p.process, p2.process)
  }

  //def runT[I1,I2,O](p: ParserT[I1,I2,O]): Tee[I1,I2,O] = {
  //  import Process._
  //  import tee.{AwaitL,AwaitR}
  //  def go(bufL: Vector[I1], bufR: Vector[I2], cur: Tee[I1,I2,Instruction[O]]): Tee[I1,I2,O] = {
  //    def interpret(i: Seq[Instruction[O]]): (Tee[I1,I2,O], Vector[I1], Vector[I2]) = {
  //      var l = bufL; var r = bufR; var buf: Tee[I1,I2,O] = Process.halt
  //      i.foreach {
  //        case Suspend(msg) =>
  //          if (l.nonEmpty || r.nonEmpty) return (Process.fail(ParseError(l, r, msg)), l, r)
  //        case Commit(side) => side match { case Side.L => l = Vector.empty
  //                                          case Side.R => r = Vector.empty }
  //        case Emit1s(os) => buf = buf ++ Process.emitAll(os)
  //      }
  //      (buf, l, r)
  //    }
  //    cur.suspendStep flatMap {
  //      case Step(Emit1(os), tl) =>
  //        val (hd, bufL2, bufR2) = interpret(os)
  //        hd ++ go(bufL2, bufR2, Append(Halt(Cause.End), tl.stack))
  //      case Step(AwaitL(rcv), tl) =>
  //        if (bufL.nonEmpty) go(bufL.tail, bufR, rcv(right(bufL.head)) ++
  //                                               Append(Halt(Cause.End), tl.stack))
  //        else Process.awaitOr(L[I1])(e => rcv(left(e)))(???)
  //      case Step(AwaitR(rcv), tl) => ???
  //    }
  //  }
  //  go(Vector.empty, Vector.empty, p.process)
  //}
  // case class Suspend[F[_]](msg: ParseMessage, tape: Tape[F])
  // trait Tape[+F[_]] { def nonce: Long, def uncons[A]: Option[(F[A], Tape[F])] }
  // or just switches branches and feeds the tape to the other branch
  // Question - how to ensure that on switch back, don't replay
  // tokens already seen? Some sort of nonce, increases monotonically
  // each branch records the last nonce it saw from any input
  // when resuming a branch, we check to see if the nonce is higher than
  // this high water mark; if not, we emit the Suspend and reset
  // trait Input[+F[_]] { def feed[O](p: Process[F,O]): Process[F,O] }
  // case class Suspend[F[_]](msg: ParseMessage, unparsed: Input[F], nonce: Long)
  // when suspending, each side tells the other how many it has peeked
  // if the other side suspends, it drops that many elements from the Input,
  // and if its input is <= the size of the other branch's input, there's some
  // unparsed input and the error should be reported
  //

  // what does peek1[Int] ++ peek1[Int] do? do we get the same value for both?
  // if yes, then we're limited to 1 token of lookahead
  // if no, then state needs to be much more complicated in implementation of `or`

  /** Await a single input and consume this and any previously peeked input. */
  def await1[A]: Parser1[A,A] = peek1[A].flatMap { a => commit1 ++ emit(a) }

  /** Await a single input and consume this and any previous peeked input. */
  def awaitL[A]: ParserT[A,Any,A] = await1[A]

  /** Await a single input and consume this and any previous peeked input. */
  def awaitR[A]: ParserT[Any,A,A] = peekR[A].flatMap { a => commitR ++ emit(a) }

  /** Await a single input and commit after it has been consumed. */
  def attempt1[A]: Parser1[A,A] = peek1[A] ++ commit1

  /** Await a single input from the left and commit after it has been consumed. */
  def attemptL[A]: ParserT[A,Any,A] = peek1[A] ++ commitL

  /** Await a single input from the right and commit after it has been consumed. */
  def attemptR[A]: ParserT[Any,A,A] = peekR[A] ++ commitR

  /** The parser which succeeds but ignores all input. */
  def default1[A]: Parser1[A,Nothing] = await1[A].flatMap { _ => suspend }.repeat

  /** The parser that emits a single value. */
  def emit[A](a: A): Parser1[Any,A] = Parser { Process.emit(Instruction.Emit1(a)) }

  /** The parser that emits a `Seq[A]` of values. */
  def emitAll[A](a: Seq[A]): Parser1[Any,A] = Parser { Process.emitAll(a map (Instruction.Emit1(_))) }

  /** Await a single input, but leave it unconsumed. */
  def peek1[A]: Parser1[A,A] =
    Parser { Process.receive1((a: A) => Process.emit(Instruction.Emit1(a))) }

  /** Await a single input from the left branch, but leave it unconsumed. */
  def peekL[A]: ParserT[A,Any,A] = peek1[A]

  /** Await a single input from the right branch, but leave it unconsumed. */
  def peekR[A]: ParserT[Any,A,A] =
    Parser { Process.awaitR[A].map(Instruction.Emit1(_)) }

  /**
   * The parser that consumes no input and switches branches immediately. If there
   * are no other branches currently active to consume buffered input, parsing fails.
   */
  val suspend: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Suspend(ParseMessage(Nil), Input.empty)) }

  /** In the event that `p` fails with a `ParseMessage`, pushes `msg` onto the stack. */
  def scope[X,Y,A](msg: String)(p: ParserT[X,Y,A]): ParserT[X,Y,A] =
    p.edit { _ map {
      case Suspend(stack, rem) => Suspend(msg :: stack, rem.asInstanceOf[Input[Env[X,Y]#T]])
      case a => a
    }}

  //def or[I1,I2,O](p: ParserT[I1,I2,O], p2: ParserT[I1,I2,O]): ParserT[I1,I2,O] = Parser {
  //  def goL: Tee[Instruction[O], Instruction[O], Instruction[O]] =
  //    tee.receiveLOr[Instruction[O], Instruction[O], Instruction[O]](tee.passR) {
  //      case e@Emit1s(_) => Process.emit(e) ++ goL
  //      case c@Commit(_) => Process.emit(c) ++ goL
  //      case s@Suspend(_) => goR
  //    }
  //  def goR: Tee[Instruction[O], Instruction[O], Instruction[O]] =
  //    tee.receiveROr[Instruction[O], Instruction[O], Instruction[O]](tee.passL) {
  //      case e@Emit1s(_) => Process.emit(e) ++ goR
  //      case c@Commit(_) => Process.emit(c) ++ goR
  //      case s@Suspend(_) => Process.emit(s) ++ goL
  //    }
  //  p.process.tee(p2.process) { goL }
  //}

  private def commit1: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Commit(Side.L)) }
  private def commitL: Parser1[Any,Nothing] = commit1
  private def commitR: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Commit(Side.R)) }
}
