/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.generator

import java.io.File
import java.nio.file.Files

import com.lightbend.markdown.server.{PrefixedRepository, AggregateFileRepository}
import com.lightbend.markdown.theme.MarkdownTheme
import play.core.PlayVersion
import play.doc._
import play.twirl.api.{Html, Content}

object GenerateSite extends App {

  case class Config(projectName: Option[String] = None,
    outputPath: File = new File("."),
    docsPaths: Seq[(File, String)] = Nil,
    homePage: String = "Home.html",
    homePageTitle: String = "Home",
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

    opt[File]('o', "out") valueName "<path>" action { (x, c) =>
      c.copy(outputPath = x) } text "The path to output files to"

    opt[Seq[(File, String)]]('d', "doc-paths") required() valueName "<path1>,<path2>" action { (x, c) =>
      c.copy(docsPaths = x) } text "The paths of the documentation to serve"

    opt[String]('h', "home-page") valueName "<page-name>" action { (x, c) =>
      c.copy(homePage = x) } text "The home page of the documentation"

    opt[String]('i', "home-page-title") valueName "<page-title>" action { (x, c) =>
      c.copy(homePage = x) } text "The title of the home page of the documentation"

    opt[Seq[(String, String)]]('a', "api-docs") valueName "<path>=<text>" action { (x, c) =>
      c.copy(apiDocs = x) } text "The API docs links to render"

    opt[String]('t', "theme") valueName "<object-name>" action { (x, c) =>
      c.copy(theme = Some(x)) } text s"The name of an object that extends ${classOf[MarkdownTheme].getName}"

    opt[String]('s', "source-url") valueName "<url>" action { (x, c) =>
      c.copy(sourceUrl = Some(x)) } text "The URL to render source paths to"
  }

  options.parse(args, Config()) match {
    case Some(config) => generate(config)
  }

  private def generate(config: Config): Unit = {

    import config._

    val markdownTheme = MarkdownTheme.load(this.getClass.getClassLoader, theme)

    val repo = new AggregateFileRepository(docsPaths.map {
      case (path, "." | "") => new FilesystemRepository(path)
      case (path, prefix) => new PrefixedRepository(prefix + "/", new FilesystemRepository(path))
    })

    val playDoc = {
      new PlayDoc(repo, repo, "resources", PlayVersion.current, PageIndex.parseFrom(repo, homePageTitle, None),
        markdownTheme.playDocTemplates, Some("html"))
    }

    val index = playDoc.pageIndex.getOrElse(throw new IllegalStateException("Generating documentation only works for indexed documentation"))

    def render(toc: Toc): Seq[String] = {
      toc.nodes.flatMap {
        case (_, childToc: Toc) => render(childToc)
        case (_, page: TocPage) =>
          println("Generating " + page.page + "...")
          playDoc.renderPage(page.page).map { rendered =>
            val sourcePath = sourceUrl.map(_ + rendered.path)

            val renderedHtml: Content = markdownTheme.renderPage(projectName, None, homePage, Html(rendered.html),
              rendered.sidebarHtml.map(Html.apply), config.apiDocs, sourcePath)
            val pageName = page.page + ".html"
            Files.write(new File(outputPath, pageName).toPath, renderedHtml.body.getBytes("utf-8"))
            pageName
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
