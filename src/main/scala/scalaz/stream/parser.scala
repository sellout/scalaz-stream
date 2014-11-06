package scalaz.stream

import scalaz.~>
import scalaz.\/.{left,right}
import Process.Env
import Util._

object parser {

  import Instruction._

  type Parser1[-I,+O] = Parser[Env[I,Any]#Is, O]
  type ParserT[-I,-I2,+O] = Parser[Env[I,I2]#T,O]
  type ParserY[-I,-I2,+O] = Parser[Env[I,I2]#Y,O]

  case class Parser[+F[_],+O](process: Process[F,Instruction[F,O]]) {
    final def append[F2[x] >: F[x], O2 >: O](p2: => Parser[F2, O2]): Parser[F2, O2] =
      edit(_ ++ p2.process)
    def edit[F2[x] >: F[x], O2](f: Process[F,Instruction[F,O]] => Process[F2,Instruction[F2,O2]]): Parser[F2,O2] =
      Parser(f(process))

    final def flatMap[F2[x] >: F[x], O2](f: O => Parser[F2, O2]): Parser[F2, O2] = Parser {
      process.flatMap {
        case Emit1(o) => f(o).process
        case c@Commit => Process.emit(c)
        case e@Suspend(_) => Process.emit(e)
        case r@Record(_) => Process.emit(r)
      }
    }
    final def map[O2](f: O => O2): Parser[F,O2] =
      edit(_ map (_ map f))
    final def or[F2[x]>:F[x],O2](p2: Parser[F2,O2]) =
      parser.or(this, p2)
    final def pipe[O2](p: Process1[O,O2]): Parser[F,O2] = {
      def go(p: Process1[O,O2]): Process1[Instruction[F,O],Instruction[F,O2]] = {
        val (hd, tl) = p.unemit
        if (hd.nonEmpty) Process.emitAll(hd map (Emit1(_))) ++ go(tl)
        else if (tl.isHalt) tl.asInstanceOf[Process1[Instruction[F,O], Instruction[F,O2]]]
        else Process.await1[Instruction[F,O]].flatMap {
          case Emit1(o) => go(process1.feed1(o)(p))
          case c@Commit => Process.emit(c) ++ go(tl)
          case e@Suspend(_) => Process.emit(e) ++ go(tl)
          case r@Record(_) => Process.emit(r) ++ go(tl)
        }
      }
      edit { _ pipe (go(p)) }
    }
    final def repeat: Parser[F,O] = edit { _.repeat }

    final def run: Process[F,O] = {
      def go(unparsed: Input[F], prevSuspend: Boolean): Process1[Instruction[F,O],O] =
        Process.receive1 { (i: Instruction[F,O]) => i match {
          case Instruction.Emit1(o) => Process.emit(o) ++ go(unparsed, false)
          case r@Record(i) => go(unparsed append i, false)
          case Commit => go(Input.empty, false)
          case Suspend(s) =>
            if (unparsed.nonEmpty || prevSuspend) Process.fail(ParseError(unparsed, s))
            else go(unparsed, true)
        }}
      process.pipe(go(Input.empty, false))
    }
    final def accept[O2](f: PartialFunction[O,O2]): Parser[F,O2] =
      edit { _.map {
        case e@Emit1(o) => f.lift(o).map(Emit1(_)).getOrElse(Suspend(ParseMessage.empty))
        case r@Record(_) => r
        case Commit => Commit
        case s@Suspend(_) => s
      }}

    final def tee[F2[x]>:F[x],O2,O3](p2: Parser[F2,O2])(t: Tee[O,O2,O3]): Parser[F2,O3] = {
      def go(t: Tee[O,O2,O3]): Tee[Instruction[F2,O], Instruction[F2,O2], Instruction[F2,O3]] = {
        t.step.haltOrStep (
          { case Cause.End => Process.halt
            case c => Process.Halt(c) },
          s => s.head.emitOrAwait(
            out => Process.emitAll(out map (Emit1(_))) ++ go(s.next.continue),
            new Process.HandleAwait[Env[O,O2]#T,
                                    O3,
                                    Tee[Instruction[F2,O], Instruction[F2,O2], Instruction[F2,O3]]] {
              def apply[x] = (req, rcv) => {
                val req1 = req.asInstanceOf[Env[Instruction[F2,O],Instruction[F2,O2]]#T[x]]
                Process.awaitOr[Env[Instruction[F2,O],Instruction[F2,O2]]#T,x,Instruction[F2,O3]](req1)(
                  e => go(rcv(left(e)).run))(
                  { case e@Emit1(o) => go(rcv(right(o.asInstanceOf[x])).run)
                    case r@Record(_) => Process.emit(r.asInstanceOf[Instruction[F2,O3]]) ++
                                        go(s.toProcess)
                    case Commit => Process.emit(Commit) ++ go(s.toProcess)
                    case e@Suspend(_) => Process.emit(e.asInstanceOf[Instruction[F2,O3]]) ++
                                         go(s.toProcess)
                  }
                )
              }
            }
          )
        )
      }
      edit { _.tee(p2.process)(go(t)) }
    }

    final def withFilter(f: O => Boolean): Parser[F,O] =
      edit { _.map {
        case e@Emit1(o) => if (!f(o)) Suspend(ParseMessage.empty) else e
        case e => e
      }}

    final def ++[F2[x] >: F[x], O2 >: O](p2: => Parser[F2, O2]): Parser[F2, O2] =
      append(p2)
    final def |[F2[x]>:F[x],O2](p2: Parser[F2,O2]) = this.or(p2)
  }

  case class ParseMessage(stack: List[String]) {
    def ::(msg: String) = copy(stack = msg :: stack)
    override def toString = stack.mkString("\n")
  }
  object ParseMessage {
    val empty = ParseMessage(List())
  }
  case class ParseError(unparsed: Input[Any],
                        message: ParseMessage) extends Throwable {
    override def fillInStackTrace = this
    override def toString = {
      s"unparsed input:\n$unparsed\nstack:\n$message"
    }
  }

  def or[F[_],O](p: Parser[F,O], p2: Parser[F,O]): Parser[F,O] = Parser {
    def go(
        out: Seq[Instruction[F,O]],
        emitSuspend: Boolean,
        unparsed: Input[F],
        p: Process[F,Instruction[F,O]],
        p2: Process[F,Instruction[F,O]]): Process[F,Instruction[F,O]] = Process.suspend { out match {
      case Seq(Emit1(_), tl@_*) =>
        val emitstl = out.span { case Emit1(o) => true; case _ => false }
        Process.emitAll(emitstl._1) ++ go(emitstl._2, emitSuspend, unparsed, p, p2)
      case Seq(Commit, tl@_*) =>
        go(tl, false, Input.empty, p, p2)
      case Seq(Record(i), tl@_*) =>
        go(tl, emitSuspend, unparsed append i.asInstanceOf[Input[F]], p, p2)
      case Seq(s0@Suspend(_), tl@_*) =>
        val s = s0.asInstanceOf[Instruction[F,O]] // Scala fail here, this cast should not be needed
        if (emitSuspend && unparsed.nonEmpty) Process.emit(s) ++ go(tl, false, Input.empty, p2, p)
        else if (emitSuspend && !unparsed.nonEmpty) go(tl, false, Input.empty, p2, p)
        else go(tl, true, Input.empty, unparsed.feed(p2), p)
      case _ if out.isEmpty =>
        p.step.haltOrStep (
          { case Cause.End => p2
            case c => p2.kill.causedBy(c) },
          s => s.head.emitOrAwait(
            out => go(out, emitSuspend, unparsed, s.next.continue, p2),
            new Process.HandleAwait[F,Instruction[F,O], Process[F,Instruction[F,O]]] { def apply[x] =
              (req, rcv) => Process.awaitOr[F,x,Instruction[F,O]](req)(
                e => go(out, emitSuspend, unparsed, rcv(left(e)).run, p2))(
                a => go(out, emitSuspend, unparsed, rcv(right(a)).run, p2)
              )
            })
        )
    }}

    // when p suspends, run p2 with leftover; if it suspends with nonempty input
    // before committing, we emit the Suspend and switch to the first branch
    // if p2 commits before suspend, then we switch to `p` as normal!
    go(Vector.empty, false, Input.empty, p.process, p2.process)
  }

  /** Await a single input and consume this and any previously peeked input. */
  def await1[A]: Parser1[A,A] = peek1[A].flatMap { a => commit ++ emit(a) }

  /** Await a single input and consume this and any previous peeked input. */
  def awaitL[A]: ParserT[A,Any,A] = await1[A]

  /** Await a single input and consume this and any previous peeked input. */
  def awaitR[A]: ParserT[Any,A,A] = peekR[A].flatMap { a => commit ++ emit(a) }

  /** Await a single input and commit after it has been consumed. */
  def attempt1[A]: Parser1[A,A] = peek1[A] ++ commit

  /** Await a single input from the left and commit after it has been consumed. */
  def attemptL[A]: ParserT[A,Any,A] = peek1[A] ++ commit

  /** Await a single input from the right and commit after it has been consumed. */
  def attemptR[A]: ParserT[Any,A,A] = peekR[A] ++ commit

  /** The parser which ignores all input. Calls `suspend` after each value read. */
  def default1[A]: Parser1[A,Nothing] = await1[A].flatMap { _ => suspend }.repeat

  /**
   * The parser which ignores all input from the left branch.
   * Calls `suspend` after each value read.
   */
  def defaultL[A]: ParserT[A,Any,Nothing] = default1

  /**
   * The parser which ignores all input from the right branch.
   * Calls `suspend` after each value read.
   */
  def defaultR[A]: ParserT[Any,A,Nothing] = awaitR[A].flatMap { _ => suspend }.repeat

  /** The parser that emits a single value. */
  def emit[A](a: A): Parser1[Any,A] = Parser { Process.emit(Instruction.Emit1(a)) }

  /** The parser that emits a `Seq[A]` of values. */
  def emitAll[A](a: Seq[A]): Parser1[Any,A] = Parser { Process.emitAll(a map (Instruction.Emit1(_))) }

  /** Await a single input, but leave it unconsumed. */
  def peek1[A]: Parser1[A,A] = Parser { Process.receive1((a: A) =>
    Process.emitAll(List(Instruction.Record(Input.Single(a)), Instruction.Emit1(a))))
  }

  /** Await a single input from the left branch, but leave it unconsumed. */
  def peekL[A]: ParserT[A,Any,A] = peek1[A]

  /** Await a single input from the right branch, but leave it unconsumed. */
  def peekR[A]: ParserT[Any,A,A] = Parser { Process.awaitR[A].flatMap { a =>
    Process.emitAll(List(Instruction.Record(Input.R(a)), Instruction.Emit1(a)))
  }}

  /** Await inputs repeatedly, but suspend before emitting each. */
  def peeks1[A]: Parser1[A,A] = peek1[A].flatMap { a => suspend ++ emit(a) }.repeat

  /** Await inputs repeatedly from the left branch, but suspend before emitting each. */
  def peeksL[A]: ParserT[A,Any,A] = peeks1

  /** Await inputs repeatedly from the right branch, but suspend before emitting each. */
  def peeksR[A]: ParserT[Any,A,A] = peekR[A].flatMap { a => suspend ++ emit(a) }.repeat

  /**
   * The parser that consumes no input and switches branches immediately. If there
   * are no other branches currently active to consume the input, parsing will fail.
   */
  val suspend: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Suspend(ParseMessage(Nil))) }

  /** In the event that `p` suspends, pushes `msg` onto its stack. */
  def scope[X,Y,A](msg: String)(p: ParserT[X,Y,A]): ParserT[X,Y,A] =
    p.edit { _ map {
      case Suspend(stack) => Suspend(msg :: stack)
      case a => a
    }}

  private def commit: Parser1[Any,Nothing] = Parser { Process.emit(Instruction.Commit) }

  /** A parse instruction. */
  private[stream] sealed trait Instruction[+F[_],+O] {
    def map[O2](f: O => O2): Instruction[F,O2] = this match {
      case Emit1(o) => Emit1(f(o))
      case c@Commit => c
      case s@Suspend(_) => s
      case r@Record(_) => r
    }
  }

  object Instruction {
    private[stream] case class Suspend[+F[_]](msg: ParseMessage)
      extends Instruction[F,Nothing]
    private[stream] case object Commit
      extends Instruction[Nothing,Nothing]
    private[stream] case class Emit1[+O](emit: O)
      extends Instruction[Nothing,O]
    private[stream] case class Record[+F[_]](unparsed: Input[F])
      extends Instruction[F,Nothing]
  }

  sealed trait Input[+F[_]] {
    def feed[F2[x]>:F[x],O](p: Process[F2,O]): Process[F2,O]
    def append[F2[x]>:F[x]](i2: Input[F2]): Input[F2]
    def nonEmpty: Boolean
    def isEmpty = !nonEmpty
  }

  object Input {
    val empty: Input[Nothing] = new Input[Nothing] {
      def feed[F2[x]>:Nothing,O](p: Process[F2,O]) = p
      def nonEmpty: Boolean = false
      def append[F2[x]>:Nothing](i: Input[F2]): Input[F2] = i
    }
    def Single[I](i: I): Input[Env[I,Any]#Is] = L(i).asInstanceOf[Input[Env[I,Any]#Is]]
    def L[I](i: I): Input[Env[I,Any]#T] = T(Vector(i), Vector.empty)
    def R[I](i: I): Input[Env[Any,I]#T] = T(Vector.empty, Vector(i))

    case class T[I1,I2](lefts: Vector[I1], rights: Vector[I2]) extends Input[Env[I1,I2]#T] {
      def feed[F2[x]>:Env[I1,I2]#T[x],O](p: Process[F2,O]) = {
        val y = p.asInstanceOf[Wye[I1,I2,O]]
        val y2 = wye.feedR(rights)(wye.feedL(lefts)(y))
        y.asInstanceOf[Process[F2,O]]
      }
      def nonEmpty = lefts.nonEmpty || rights.nonEmpty
      def append[F2[x]>:Env[I1,I2]#T[x]](i: Input[F2]): Input[F2] = i match {
        case T(l2,r2) => T(lefts fast_++ l2, rights fast_++ r2)
        case _ => sys.error("unpossible! trait is sealed")
      }
    }
  }
}
