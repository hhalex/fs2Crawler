package exystence

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext.Implicits.global

object Main extends IOApp {
  def run(args: List[String]) = {

    val httpClientBuilder = BlazeClientBuilder[IO](global)

    httpClientBuilder.resource
      .use(
        _.expect[String](Uri.unsafeFromString("http://exystence.net"))
      )
      .map(println)
      .map(_ => ExitCode.Success)
  }
}
