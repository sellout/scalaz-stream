package scalaz.stream

import scalaz.~>
import Process.Env

object parser {

  import Instruction._

  /** A parse result. */
  sealed trait Instruction[+O] {
    def map[O2](f: O => O2): Instruction[O2] = this match {
      case Emits(o) => Emits(o map f)
      case f@Fetch(_) => f
      case c@Commit(_) => c
      case s@Suspend(_) => s
    }
  }

  object Instruction {
    sealed trait Side
    object Side {
      case object L extends Side
      case object R extends Side
    }
    private[stream] case class Suspend(msg: ParseMessage) extends Instruction[Nothing]
    private[stream] case class Fetch(side: Side) extends Instruction[Nothing]
    private[stream] case class Commit(side: Side) extends Instruction[Nothing]
    private[stream] case class Emits[+O](emits: Seq[O]) extends Instruction[O]
  }

  //def broadcast[A,B](p1: Process1[A,B], p2: Process1[A,B]): Process1[A,B] = {
  //  val (p1p, p2p) = (fairPeeker[A].pipe(p1), fairPeeker[A].pipe(p2))
  //  run1 { or(or(p1p, p2p), default) }
  //}

  //def peeker[A]: Parser1[A,A] = peek1[A].repeat
  //def fairPeeker[A]: Parser1[A,A] = peek1[A].flatMap { a => suspend ++ emit(a) }.repeat

  //def lift[A,B](p: Process1[A,B]): Parser1[A,B] = ???

  case class Parser[+F[_],+O](process: Process[F,Instruction[O]]) {
    final def append[F2[x] >: F[x], O2 >: O](p2: => Parser[F2, O2]): Parser[F2, O2] =
      edit(_ ++ p2.process)
    def edit[F2[x] >: F[x], O2](f: Process[F,Instruction[O]] => Process[F2,Instruction[O2]]): Parser[F2,O2] =
      Parser(f(process))

    final def flatMap[F2[x] >: F[x], O2](f: O => Parser[F2, O2]): Parser[F2, O2] = Parser {
      process.flatMap {
        case Emits(o) => Process.emitAll(o).flatMap(f andThen (_.process))
        case c@Commit(_) => Process.emit(c)
        case e@Suspend(_) => Process.emit(e)
        case f@Fetch(_) => Process.emit(f)
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

  def runT[I1,I2,O](p: ParserT[I1,I2,O]): Tee[I1,I2,O] = {
    def go(bufL: Vector[I1], bufR: Vector[I2]): Process1[Instruction[O],O] =
      Process.receive1 {
        case Emits(os) => Process.emitAll(os) ++ go(bufL, bufR)
        case Commit(Side.L) => go(Vector.empty, bufR)
        case Commit(Side.R) => go(bufL, Vector.empty)
        case Fetch(_) => go(bufL, bufR)
          // not correct, we need to populate the buffer
          // need a monolithic Tee unfortunately
        case Suspend(s) =>
          if (bufL.isEmpty && bufR.isEmpty) go(bufL, bufR)
          else Process.fail(ParseError(bufL, bufR, s))
      }
    p.process.pipe(go(Vector.empty, Vector.empty))
  }

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
  def emit[A](a: A): Parser1[Any,A] = Parser { Process.emit(Instruction.Emits(List(a))) }

  /** The parser that emits a `Seq[A]` of values. */
  def emitAll[A](a: Seq[A]): Parser1[Any,A] = Parser { Process.emit(Instruction.Emits(a)) }

  def fetch1[A]: Parser1[A,A] = Parser { Process.emit(Instruction.Fetch(Side.L)) }
  def fetchL[A]: ParserT[A,Any,A] = fetch1
  def fetchR[A]: ParserT[Any,A,A] = Parser { Process.emit(Instruction.Fetch(Side.R)) }

  /** Await a single input, but leave it unconsumed. */
  def peek1[A]: Parser1[A,A] =
    fetch1[A] ++ Parser { Process.receive1((a: A) => Process.emit(Instruction.Emits(List(a)))) }

  /** Await a single input from the left branch, but leave it unconsumed. */
  def peekL[A]: ParserT[A,Any,A] = peek1[A]

  /** Await a single input from the right branch, but leave it unconsumed. */
  def peekR[A]: ParserT[Any,A,A] =
    fetchR[A] ++ Parser { Process.awaitR[A].map(a => Instruction.Emits(List(a))) }

  /**
   * The parser that consumes no input and switches branches immediately. If there
   * are no other branches currently active to consume buffered input, parsing fails.
   */
  val suspend: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Suspend(ParseMessage(Nil))) }

  /** In the event that `p` fails with a `ParseMessage`, pushes `msg` onto the stack. */
  def scope[X,Y,A](msg: String)(p: ParserT[X,Y,A]): ParserT[X,Y,A] =
    p.edit { _ map {
      case Suspend(stack) => Suspend(msg :: stack)
      case a => a
    }}

  def or[I1,I2,O](p: ParserT[I1,I2,O], p2: ParserT[I1,I2,O]): ParserT[I1,I2,O] = Parser {
    // need to simplify redundant fetch instructions - after switching parsers,
    // if the other side has already fetched, don't emit a fetch instruction
    // keep a Long nonce for each branch
    def goL: Tee[Instruction[O], Instruction[O], Instruction[O]] =
      tee.receiveLOr[Instruction[O], Instruction[O], Instruction[O]](tee.passR) {
        case e@Emits(_) => Process.emit(e) ++ goL
        case c@Commit(_) => Process.emit(c) ++ goL
        case f@Fetch(_) => Process.emit(f) ++ goL
        case s@Suspend(_) => goR
      }
    def goR: Tee[Instruction[O], Instruction[O], Instruction[O]] =
      tee.receiveROr[Instruction[O], Instruction[O], Instruction[O]](tee.passL) {
        case e@Emits(_) => Process.emit(e) ++ goR
        case c@Commit(_) => Process.emit(c) ++ goR
        case f@Fetch(_) => Process.emit(f) ++ goR
        case s@Suspend(_) => Process.emit(s) ++ goL
      }
    p.process.tee(p2.process) { goL }
  }

  private def commit1: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Commit(Side.L)) }
  private def commitL: Parser1[Any,Nothing] = commit1
  private def commitR: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Commit(Side.R)) }
}
