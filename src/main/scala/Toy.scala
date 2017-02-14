import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.concurrent.duration._

import cats.implicits._

import org.atnos.eff.{Eff, |=}
import org.atnos.eff.all._
import org.atnos.eff.future._

sealed trait Toy[A]
final case class Getting(key: String)                extends Toy[Option[String]]
final case class Setting(key: String, value: String) extends Toy[Unit]
final case class Doing(v: String)                    extends Toy[String]

object Toy {
  type _toy[R] = Toy |= R

  def get[R  : _toy](key: String): Eff[R, Option[String]] =
    Eff.send(Getting(key))

  def set[R  : _toy](k: String, v: String): Eff[R, Unit] =
    Eff.send(Setting(k, v))

  def doing[R  : _toy](v: String): Eff[R, String] =
    Eff.send(Doing(v))

  def cycle[R  : _toy](x: String): Eff[R, String] = for {
    v <- get(x)
    s <- doing(v.getOrElse(s"$x: N/A"))
  } yield s

  def groupCycle[R : _toy](xs: List[String]): Eff[R, List[String]] =
    xs.traverse(cycle[R])

  /** Use the Eff applicative traverse. */
  def groupCycleA[R : _toy](xs: List[String]): Eff[R, List[String]] =
    Eff.traverseA(xs)(cycle[R])

  /** NEW */
  def parGroupCycle[R : _toy : _Future](xs: List[String]): Eff[R, List[String]] = {
    def futureCycle(x: String): Eff[R, String] =
      futureAttempt[R, String](futureDelay[R, Eff[R, String]](cycle(x)).flatten)
        .map(_.fold(
          e => s"Cycle failed: ${e.getMessage}",
          identity
        ))

    Eff.traverseA(xs)(futureCycle)
  }

  def parGroupCycle2[R : _toy](xs: List[String])(implicit ec: ExecutionContext): Eff[R, List[String]] = {
    def futureCycle(x: String): Future[Eff[R, String]] = {
      val prom = Promise[Eff[R, String]]
      Future(cycle(x)).onComplete {
        case Success(a) => prom.success(a)
        case Failure(e) => prom.success(Eff.pure(s"Cycle failed: ${e.getMessage}"))
      }
      prom.future
    }

    Await.result(Future.traverse(xs)(futureCycle).map(Eff.sequenceA(_)), 1 minute)
  }
}
