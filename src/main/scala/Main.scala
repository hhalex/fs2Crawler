package exystence

import java.util.Locale
import java.util.concurrent.TimeUnit

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.implicits._
import com.github.nscala_time.time.Imports._
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._
import org.http4s._
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.FollowRedirect
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.jsoup.Jsoup

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.{Success, Try}

case class BlogPost(title: String,
                    date: DateTime,
                    permanentLink: Uri,
                    albumImage: Uri,
                    categories: Seq[String],
                    tags: Seq[String])

object ExtractInfos {
  def apply(html: String, uri: Uri) = Try {
    val detailedPage = Jsoup
      .parse(html)
    val postTitle =
      detailedPage.selectFirst("div.posttop").ownText()
    val postDate = detailedPage
      .selectFirst("div.postmetatop div.date span")
      .ownText()

    val albumImage = Uri.unsafeFromString(
      detailedPage
        .selectFirst("div.postcontent img")
        .parent()
        .attr("href")
    )

    val parsedPostDate = DateTime.parse(
      postDate,
      DateTimeFormat
        .forPattern("MMMM dd, yyyy")
        .withLocale(new Locale(("en")))
    )
    val postCategories = detailedPage
      .select("div.postmetatop div.categs a[rel]")
      .asScala
      .toList
      .map(_.text())

    val postTags = detailedPage
      .select("div.postmetabottom a[rel~=tag]")
      .asScala
      .toList
      .map(_.text())

    val blogPost = BlogPost(
      postTitle,
      parsedPostDate,
      uri,
      albumImage,
      postCategories,
      postTags
    )

    println(s"""
               |Title: '$postTitle'
               |Date: '$parsedPostDate'
               |PermanentUri: '$uri'
               |AlbumImage: '$albumImage'
               |Categories: '${postCategories.mkString("', '")}'
               |Tags: '${postTags.mkString("', '")}'
               """.stripMargin)
    blogPost
  }
}

object Main extends IOApp {
  def run(args: List[String]) = {

    val httpClientBuilder = BlazeClientBuilder[IO](global)

    val blogPostStream = fs2.Stream
      .range(1, 1046)
      .covary[IO]
      .metered(FiniteDuration(2, TimeUnit.SECONDS))
      .evalMap(page => {
        val uriString = s"http://exystence.net/page/$page"
        println(s"Start downloading from '$uriString'")
        val url = Uri.unsafeFromString(uriString)
        httpClientBuilder.resource
          .map(FollowRedirect(2))
          .use(_.expect[String](url))
      })
      .flatMap(htmlPage => {
        val permanentLinks = Jsoup
          .parse(htmlPage)
          .select("div.posttop div.date a")
          .asScala
          .toList

        fs2.Stream
          .emits(permanentLinks.map { l =>
            val href = l.attr("href")
            Uri.unsafeFromString(href)
          })
          .covary[IO]
          .metered(FiniteDuration(1, TimeUnit.SECONDS))
          .evalMap(uri => {
            println(s"Fetching and processing link: $uri")
            httpClientBuilder.resource
              .map(FollowRedirect(2))
              .use(_.expect[String](uri))
              .map(ExtractInfos(_, uri))
          })

      })

    implicit val dateTimeEncoder: Encoder[DateTime] = Encoder.instance {
      _.toString(DateTimeFormat.shortDate()).asJson
    }
    implicit val uriEncoder: Encoder[Uri] = Encoder.instance {
      _.toString().asJson
    }
    implicit val blogPostEncoder: Encoder[BlogPost] = deriveEncoder[BlogPost]

    val app = HttpRoutes
      .of[IO] {
        case Method.GET -> Root / "streamPosts" => {
          Ok(blogPostStream.collect({ case Success(v) => v }).map(blogPost => ServerSentEvent(blogPost.asJson.noSpaces)))
        }
        case r @ Method.GET -> Root =>
          StaticFile
            .fromResource("/index.html",
              Blocker.liftExecutionContext(global),
              Some(r)
            )
            .getOrElseF(NotFound())
      }
      .orNotFound

    BlazeServerBuilder[IO]
      .withHttpApp(app)
      .bindHttp(8081, "localhost")
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
  }
}
