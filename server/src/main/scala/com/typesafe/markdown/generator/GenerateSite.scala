package com.typesafe.markdown.generator

import java.io.File
import java.nio.file.Files

import com.typesafe.markdown.server.{PrefixedRepository, AggregateFileRepository}
import play.core.PlayVersion
import play.doc._
import com.typesafe.markdown.server.html
import play.twirl.api.Content

object GenerateSite extends App {

  case class Config(projectName: Option[String] = None,
    outputPath: File = new File("."),
    docsPaths: Seq[(File, String)] = Nil,
    homePage: String = "Home",
    apiDocs: Seq[(String, String)] = Seq(
      "api/java/index.html" -> "Java",
      "api/scala/index.html" -> "Scala"
    ))

  val options = new scopt.OptionParser[Config]("Documentation Server") {

    opt[String]('n', "project-name") valueName "<name>"  action { (x, c) =>
      c.copy(projectName = Some(x)) } text "The name of the project"

    opt[File]('o', "out") valueName "<path>" action { (x, c) =>
      c.copy(outputPath = x) } text "The path to output files to"

    opt[Seq[(File, String)]]('d', "doc-paths") required() valueName "<path1>,<path2>" action { (x, c) =>
      c.copy(docsPaths = x) } text "The paths of the documentation to serve"

    opt[String]('h', "home-page") valueName "<page-name>" action { (x, c) =>
      c.copy(homePage = x) } text "The home page of the documentation"

    opt[Seq[(String, String)]]('a', "api-docs") valueName "<path>=<text>" action { (x, c) =>
      c.copy(apiDocs = x) } text "The API docs links to render"
  }

  options.parse(args, Config()) match {
    case Some(config) => generate(config)
  }

  private def generate(config: Config): Unit = {

    import config._

    val repo = new AggregateFileRepository(docsPaths.map {
      case (path, "." | "") => new FilesystemRepository(path)
      case (path, prefix) => new PrefixedRepository(prefix + "/", new FilesystemRepository(path))
    })

    val playDoc = {
      new PlayDoc(repo, repo, "resources", PlayVersion.current, PageIndex.parseFrom(repo, homePage, None), new PlayDocTemplates {
        override def nextLink(toc: TocTree): String = html.nextLink(toc).body
        override def sidebar(hierarchy: List[Toc]): String = html.sidebar(hierarchy).body
        override def toc(toc: Toc): String = PlayDocTemplates.toc(toc)
      })
    }

    val index = playDoc.pageIndex.getOrElse(throw new IllegalStateException("Generating documentation only works for indexed documentation"))

    def render(toc: Toc): Seq[String] = {
      toc.nodes.flatMap {
        case (_, childToc: Toc) => render(childToc)
        case (_, page: TocPage) =>
          playDoc.renderPage(page.page).map { rendered =>
            println("Generating " + page.page + "...")
            val renderedHtml: Content = html.documentation(projectName, None, homePage, rendered.html, rendered.sidebarHtml, config.apiDocs)
            Files.write(new File(outputPath, page.page).toPath, renderedHtml.body.getBytes("utf-8"))
            page.page
          }.toSeq
      }
    }

    // Ensure parent directory exists
    Files.createDirectories(outputPath.toPath)

    // Render all pages
    val allPages = render(index.toc).toSet
    // Delete all pages that don't exist
    outputPath.list().filterNot(allPages).foreach(new File(outputPath, _).delete())
    println("Done!")
  }

}
