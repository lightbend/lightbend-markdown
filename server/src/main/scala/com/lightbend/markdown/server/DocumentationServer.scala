/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.server

import java.io.File
import akka.stream.scaladsl.StreamConverters
import com.lightbend.markdown.theme.MarkdownTheme
import org.webjars.WebJarAssetLocator
import play.api.http.{ContentTypes, HttpEntity}
import play.api.libs.MimeTypes
import play.api.mvc.Results._
import play.api.mvc._
import play.core._
import play.core.server._
import play.doc._
import play.api.routing.sird._
import play.twirl.api.Html

/**
  * Used to start the documentation server.
  */
object DocumentationServer extends App {

  case class Config(projectName: Option[String] = None,
    projectPath: File = new File("."),
    docsPaths: Seq[(File, String)] = Nil,
    homePage: String = "Home.html",
    homePageTitle: String = "Home",
    port: Int = 9000,
    apiDocs: Seq[(String, String)] = Seq(
      "api/java/index.html" -> "Java",
      "api/scala/index.html" -> "Scala"
    ),
    theme: Option[String] = None,
    sourceUrl: Option[String] = None
  )

  val options = new scopt.OptionParser[Config]("Documentation Server") {

    opt[String]('n', "project-name") valueName "<name>"  action { (x, c) =>
      c.copy(projectName = Some(x)) } text "The name of the project"

    opt[File]('r', "root-path") valueName "<path>" action { (x, c) =>
      c.copy(projectPath = x) } text "The path of the project"

    opt[Seq[(File, String)]]('d', "doc-paths") required() valueName "<path1>,<path2>" action { (x, c) =>
      c.copy(docsPaths = x) } text "The paths of the documentation to serve"

    opt[String]('h', "home-page") valueName "<page-name>" action { (x, c) =>
      c.copy(homePage = x) } text "The home page of the documentation"

    opt[String]('i', "home-page-title") valueName "<page-title>" action { (x, c) =>
      c.copy(homePage = x) } text "The title of the home page of the documentation"

    opt[Int]('p', "port") valueName "<port>" action { (x, c) =>
      c.copy(port = x) } text "The port to run the server on"

    opt[Seq[(String, String)]]('a', "api-docs") valueName "<path>=<text>" action { (x, c) =>
      c.copy(apiDocs = x) } text "The API docs links to render"

    opt[String]('t', "theme") valueName "<object-name>" action { (x, c) =>
      c.copy(theme = Some(x)) } text s"The name of an object that extends ${classOf[MarkdownTheme].getName}"

    opt[String]('s', "source-url") valueName "<url>" action { (x, c) =>
      c.copy(sourceUrl = Some(x)) } text "The URL to render source paths to"
  }

  options.parse(args, Config()) match {
    case Some(config) => start(config)
  }

  private def start(config: Config): Unit = {

    import config._

    val markdownTheme = MarkdownTheme.load(this.getClass.getClassLoader, theme)

    val repo = new AggregateFileRepository(docsPaths.map {
      case (path, "." | "") => new FilesystemRepository(path)
      case (path, prefix) => new PrefixedRepository(prefix + "/", new FilesystemRepository(path))
    })

    def playDoc = {
      new PlayDoc(repo, repo, "resources", PlayVersion.current, PageIndex.parseFrom(repo, homePageTitle, None),
        markdownTheme.playDocTemplates, Some("html"))
    }

    val webjarLocator = new WebJarAssetLocator()

    val server = NettyServer.fromRouter(ServerConfig(rootDir = projectPath, port = Some(port))) {
      case GET(p"/") => Action {
        Redirect("/" + homePage)
      }

      case GET(p"/$page.html") => Action {
        playDoc.renderPage(page) match {
          case None => NotFound(markdownTheme.renderPage(projectName, None, homePage,
            Html("Page " + page + " not found."), None, config.apiDocs, None))
          case Some(RenderedPage(mainPage, sidebar, path, breoadcrumbs)) =>
            val sourcePath = sourceUrl.map(_ + path)

            Ok(markdownTheme.renderPage(projectName, None, homePage,
              Html(mainPage), sidebar.map(Html.apply), config.apiDocs, sourcePath))
        }
      }

      case GET(p"/resources/$path*") => Action {
        sendFileInline(repo, path).getOrElse(NotFound("Resource not found [" + path + "]"))
      }

      case GET(p"/webjars/$webjar/$path*") => Action {
        val resource = Option(webjarLocator.getFullPathExact(webjar, path))
        resource.fold[Result](NotFound)(Ok.sendResource(_))
      }

      case GET(p"/api/$path*") => Action {
        sendFileInline(repo, "api/" + path).getOrElse(NotFound("API doc resource not found [" + path + "]"))
      }

      case _ => Action {
        Redirect("/" + homePage)
      }
    }

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run() = server.stop()
    })
  }

  private def sendFileInline(repo: FileRepository, path: String): Option[Result] = {
    repo.handleFile(path) { handle =>
      Ok.sendEntity(
        HttpEntity.Streamed(
          StreamConverters.fromInputStream(() => handle.is),
          Some(handle.size),
          MimeTypes.forFileName(handle.name).orElse(Some(ContentTypes.BINARY))
        )
      )
    }
  }
}


