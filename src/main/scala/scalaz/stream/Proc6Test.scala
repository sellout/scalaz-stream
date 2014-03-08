package scalaz.stream

import Proc6._
import scalaz.concurrent.Task
import scalaz.Monoid

object Proc6Test extends App {

  def time(a: => Any): Unit = {
    val start = System.currentTimeMillis
    val result = a
    val stop = System.currentTimeMillis
    // println("result: " + result)
    println(s"took ${(stop-start)/1000.0} seconds")
  }

  def bad(n: Int): Proc6[Task,Int] =
    (0 to n).map(emit).foldLeft(halt: Proc6[Nothing,Int])(_ ++ _)

  def good(n: Int): Proc6[Task,Int] =
    (0 to n).map(emit).reverse.foldLeft(halt: Proc6[Nothing,Int])(
      (acc,h) => h ++ acc
    )

  def badFlatMap2(n: Int): Proc6[Task,Int] =
    if (n == 0) halt
    else emit(1).flatMap(_ => badFlatMap2(n-1) ++ badFlatMap2(n-1))

  // check for left-associated binds
  def badFlatMap1(n: Int): Proc6[Task,Int] =
    (0 to n).map(emit).foldLeft(halt: Proc6[Task,Int])((acc,h) =>
      acc.flatMap(acc => h.map(h => acc + h)))

  def worstCaseScenario(n: Int): Proc6[Task,Int] = {
    @annotation.tailrec
    def churn(p: Proc6[Task,Int], m: Int): Proc6[Task,Int] =
      if (m == 0) p
      else churn(p.flatMap(emit), m-1) // does nothing, but adds another flatMap
    churn(bad(n),n)
  }

  implicit val B = Monoid.instance[Int]((a,b) => a+b, 0)

  println("bad append")
  time { bad(1).runFoldMap(identity).run }
  time { bad(10).runFoldMap(identity).run }
  time { bad(100).runFoldMap(identity).run }
  time { bad(1000).runFoldMap(identity).run }
  time { bad(10000).runFoldMap(identity).run }
  time { bad(100000).runFoldMap(identity).run }
  time { bad(1000000).runFoldMap(identity).run }

  println("good append")
  time { good(1).runFoldMap(identity).run }
  time { good(10).runFoldMap(identity).run }
  time { good(100).runFoldMap(identity).run }
  time { good(1000).runFoldMap(identity).run }
  time { good(10000).runFoldMap(identity).run }
  time { good(100000).runFoldMap(identity).run }
  time { good(1000000).runFoldMap(identity).run }

  println("bad flatMap 1")
  time { badFlatMap1(1).runFoldMap(identity).run }
  time { badFlatMap1(10).runFoldMap(identity).run }
  time { badFlatMap1(100).runFoldMap(identity).run }
  time { badFlatMap1(1000).runFoldMap(identity).run }
  time { badFlatMap1(10000).runFoldMap(identity).run }
  time { badFlatMap1(100000).runFoldMap(identity).run }
  time { badFlatMap1(1000000).runFoldMap(identity).run }

  println("bad flatMap 2")
  time { badFlatMap2(14).runFoldMap(identity).run }
  time { badFlatMap2(15).runFoldMap(identity).run }
  time { badFlatMap2(16).runFoldMap(identity).run }
  time { badFlatMap2(17).runFoldMap(identity).run }
  time { badFlatMap2(18).runFoldMap(identity).run }
  time { badFlatMap2(19).runFoldMap(identity).run }
  time { badFlatMap2(20).runFoldMap(identity).run }
  time { badFlatMap2(21).runFoldMap(identity).run }
  time { badFlatMap2(22).runFoldMap(identity).run }

  println("worst case scenario")
  time { worstCaseScenario(1).runFoldMap(identity).run }
  time { worstCaseScenario(2).runFoldMap(identity).run }
  time { worstCaseScenario(4).runFoldMap(identity).run }
  time { worstCaseScenario(8).runFoldMap(identity).run }
  time { worstCaseScenario(16).runFoldMap(identity).run }
  time { worstCaseScenario(32).runFoldMap(identity).run }
  time { worstCaseScenario(64).runFoldMap(identity).run }
  time { worstCaseScenario(128).runFoldMap(identity).run }
  time { worstCaseScenario(256).runFoldMap(identity).run }
  time { worstCaseScenario(512).runFoldMap(identity).run }
  time { worstCaseScenario(1024).runFoldMap(identity).run }
  time { worstCaseScenario(2048).runFoldMap(identity).run }
}

