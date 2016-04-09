/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package com.lightbend.markdown.sbt

import java.io._
import java.net.HttpURLConnection
import java.util.concurrent.Executors

import org.pegdown.ast._
import org.pegdown._
import org.pegdown.plugins.{ToHtmlSerializerPlugin, PegDownPlugins}
import play.doc._
import sbt.{FileRepository => _, Node => _, _}
import sbt.Keys._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

// Test that all the docs are renderable and valid
object LightbendMarkdownValidation {

  import LightbendMarkdownKeys._

  /**
   * A report of all references from all markdown files.
   *
   * This is the main markdown report for validating markdown docs.
   */
  case class MarkdownRefReport(markdownFiles: Seq[File],
    wikiLinks: Seq[LinkRef],
    resourceLinks: Seq[LinkRef],
    codeSamples: Seq[CodeSampleRef],
    relativeLinks: Seq[LinkRef],
    externalLinks: Seq[LinkRef])

  case class LinkRef(link: String, file: File, position: Int)
  case class CodeSampleRef(source: String, segment: String, file: File, sourcePosition: Int, segmentPosition: Int)

  /**
   * A report of just code samples in all markdown files.
   *
   * This is used to compare translations to the originals, checking that all files exist and all code samples exist.
   */
  case class CodeSamplesReport(files: Seq[FileWithCodeSamples]) {
    lazy val byFile = files.map(f => f.name -> f).toMap
    lazy val byName = files.filterNot(_.name.endsWith("_Sidebar.md")).map { file =>
      val filename = file.name
      val name = filename.takeRight(filename.length - filename.lastIndexOf('/'))
      name -> file
    }.toMap
  }
  case class FileWithCodeSamples(name: String, source: String, codeSamples: Seq[CodeSample])
  case class CodeSample(source: String, segment: String,
    sourcePosition: Int, segmentPosition: Int)


  val generateMarkdownRefReportTask = Def.task {

    val base = markdownManualPath.value

    val markdownFiles = (base ** "*.md").get

    val wikiLinks = mutable.ListBuffer[LinkRef]()
    val resourceLinks = mutable.ListBuffer[LinkRef]()
    val codeSamples = mutable.ListBuffer[CodeSampleRef]()
    val relativeLinks = mutable.ListBuffer[LinkRef]()
    val externalLinks = mutable.ListBuffer[LinkRef]()

    def stripFragment(path: String) = if (path.contains("#")) {
      path.dropRight(path.length - path.indexOf('#'))
    } else {
      path
    }

    def parseMarkdownFile(markdownFile: File): String = {

      val processor = new PegDownProcessor(Extensions.ALL, PegDownPlugins.builder()
        .withPlugin(classOf[CodeReferenceParser]).build)

      // Link renderer will also verify that all wiki links exist
      val linkRenderer = new LinkRenderer {
        override def render(node: WikiLinkNode) = {
          node.getText match {

            case link if link.contains("|") =>
              val parts = link.split('|')
              val desc = parts.head
              val page = stripFragment(parts.tail.head.trim)
              wikiLinks += LinkRef(page, markdownFile, node.getStartIndex + desc.length + 3)

            case image if image.endsWith(".png") =>
              image match {
                case full if full.startsWith("http://") =>
                  externalLinks += LinkRef(full, markdownFile, node.getStartIndex + 2)
                case absolute if absolute.startsWith("/") =>
                  resourceLinks += LinkRef("manual" + absolute, markdownFile, node.getStartIndex + 2)
                case relative =>
                  val link = markdownFile.getParentFile.getCanonicalPath.stripPrefix(base.getCanonicalPath).stripPrefix("/") + "/" + relative
                  resourceLinks += LinkRef(link, markdownFile, node.getStartIndex + 2)
              }

            case link =>
              wikiLinks += LinkRef(link.trim, markdownFile, node.getStartIndex + 2)

          }
          new LinkRenderer.Rendering("foo", "bar")
        }

        override def render(node: AutoLinkNode) = addLink(node.getText, node, 1)
        override def render(node: ExpLinkNode, text: String) = addLink(node.url, node, text.length + 3)

        private def addLink(url: String, node: Node, offset: Int) = {
          url match {
            case full if full.startsWith("http://") || full.startsWith("https://") =>
              externalLinks += LinkRef(full, markdownFile, node.getStartIndex + offset)
            case fragment if fragment.startsWith("#") => // ignore fragments, no validation of them for now
            case relative => relativeLinks += LinkRef(relative, markdownFile, node.getStartIndex + offset)
          }
          new LinkRenderer.Rendering("foo", "bar")
        }
      }

      val codeReferenceSerializer = new ToHtmlSerializerPlugin() {
        def visit(node: Node, visitor: Visitor, printer: Printer) = node match {
          case code: CodeReferenceNode => {

            // Label is after the #, or if no #, then is the link label
            val (source, label) = code.getSource.split("#", 2) match {
              case Array(source, label) => (source, label)
              case Array(source) => (source, code.getLabel)
            }

            // The file is either relative to current page page or absolute, under the root
            val sourceFile = if (source.startsWith("/")) {
              source.drop(1)
            } else {
              markdownFile.getParentFile.getCanonicalPath.stripPrefix(base.getCanonicalPath).stripPrefix("/") + "/" + source
            }

            val sourcePos = code.getStartIndex + code.getLabel.length + 4
            val labelPos = if (code.getSource.contains("#")) {
              sourcePos + source.length + 1
            } else {
              code.getStartIndex + 2
            }

            codeSamples += CodeSampleRef(sourceFile, label, markdownFile, sourcePos, labelPos)
            true
          }
          case _ => false
        }
      }

      val astRoot = processor.parseMarkdown(IO.read(markdownFile).toCharArray)
      new ToHtmlSerializer(linkRenderer, java.util.Arrays.asList[ToHtmlSerializerPlugin](codeReferenceSerializer))
        .toHtml(astRoot)
    }

    markdownFiles.foreach(parseMarkdownFile)

    MarkdownRefReport(markdownFiles, wikiLinks.toSeq, resourceLinks.toSeq, codeSamples.toSeq, relativeLinks.toSeq, externalLinks.toSeq)
  }

  private def extractCodeSamples(filename: String, markdownSource: String): FileWithCodeSamples = {

    val codeSamples = ListBuffer.empty[CodeSample]

    val processor = new PegDownProcessor(Extensions.ALL, PegDownPlugins.builder()
      .withPlugin(classOf[CodeReferenceParser]).build)

    val codeReferenceSerializer = new ToHtmlSerializerPlugin() {
      def visit(node: Node, visitor: Visitor, printer: Printer) = node match {
        case code: CodeReferenceNode => {

          // Label is after the #, or if no #, then is the link label
          val (source, label) = code.getSource.split("#", 2) match {
            case Array(source, label) => (source, label)
            case Array(source) => (source, code.getLabel)
          }

          // The file is either relative to current page page or absolute, under the root
          val sourceFile = if (source.startsWith("/")) {
            source.drop(1)
          } else {
            filename.dropRight(filename.length - filename.lastIndexOf('/') + 1) + source
          }

          val sourcePos = code.getStartIndex + code.getLabel.length + 4
          val labelPos = if (code.getSource.contains("#")) {
            sourcePos + source.length + 1
          } else {
            code.getStartIndex + 2
          }

          codeSamples += CodeSample(sourceFile, label, sourcePos, labelPos)
          true
        }
        case _ => false
      }
    }

    val astRoot = processor.parseMarkdown(markdownSource.toCharArray)
    new ToHtmlSerializer(new LinkRenderer(), java.util.Arrays.asList[ToHtmlSerializerPlugin](codeReferenceSerializer))
      .toHtml(astRoot)

    FileWithCodeSamples(filename, markdownSource, codeSamples.toList)
  }

  val validateDocsTask = Def.task {
    val report = markdownGenerateRefReport.value
    val log = streams.value.log
    val docPaths = markdownDocPaths.value

    val repo = new AggregateFileRepository(docPaths.map {
      case (path, "." | "") => new FilesystemRepository(path)
      case (path, prefix) => new PrefixedRepository(prefix + "/", new FilesystemRepository(path))
    })

    val pageIndex = PageIndex.parseFrom(repo, "", None)

    val pages = report.markdownFiles.map(f => f.getName.dropRight(3) -> f).toMap

    var failed = false

    def doAssertion(desc: String, errors: Seq[_])(onFail: => Unit): Unit = {
      if (errors.isEmpty) {
        log.info("[" + Colors.green("pass") + "] " + desc)
      } else {
        failed = true
        onFail
        log.info("[" + Colors.red("fail") + "] " + desc + " (" + errors.size + " errors)")
      }
    }

    def fileExists(path: String): Boolean = {
      repo.loadFile(path)(_ => ()).nonEmpty
    }

    def assertLinksNotMissing(desc: String, links: Seq[LinkRef], errorMessage: String): Unit = {
      doAssertion(desc, links) {
        links.foreach { link =>
          logErrorAtLocation(log, link.file, link.position, errorMessage + " " + link.link)
        }
      }
    }

    val duplicates = report.markdownFiles
      .filterNot(_.getName.startsWith("_"))
      .groupBy(s => s.getName)
      .filter(v => v._2.size > 1)

    doAssertion("Duplicate markdown file name test", duplicates.toSeq) {
      duplicates.foreach { d =>
        log.error(d._1 + ":\n" + d._2.mkString("\n    "))
      }
    }

    assertLinksNotMissing("Missing wiki links test", report.wikiLinks.filterNot { link =>
      pages.contains(link.link) || repo.findFileWithName(link.link + ".md").nonEmpty
    }, "Could not find link")

    def relativeLinkOk(link: LinkRef) = {
      link match {
        case badScalaApi if badScalaApi.link.startsWith("api/scala/index.html#") =>
          println("Don't use segment links from the index.html page to scaladocs, use path links, ie:")
          println("  api/scala/index.html#play.api.Application@requestHandler")
          println("should become:")
          println("  api/scala/play/api/Application.html#requestHandler")
          false
        case scalaApi if scalaApi.link.startsWith("api/scala/") => fileExists(scalaApi.link.split('#').head)
        case javaApiFrames if javaApiFrames.link.startsWith("api/java/index.html?") =>
          fileExists("api/java/" + javaApiFrames.link.split('#').head.split('?').last)
        case javaApi if javaApi.link.startsWith("api/java/") => fileExists(javaApi.link.split('#').head)
        case resource if resource.link.startsWith("resources/") =>
          fileExists(resource.link.stripPrefix("resources/"))
        case bad => false
      }
    }

    assertLinksNotMissing("Relative link test", report.relativeLinks.collect {
      case link if !relativeLinkOk(link) => link
    }, "Bad relative link")

    assertLinksNotMissing("Missing wiki resources test",
      report.resourceLinks.collect {
        case link if !fileExists(link.link) => link
      }, "Could not find resource")

    val (existing, nonExisting) = report.codeSamples.partition(sample => fileExists(sample.source))

    assertLinksNotMissing("Missing source files test",
      nonExisting.map(sample => LinkRef(sample.source, sample.file, sample.sourcePosition)),
      "Could not find source file")

    def segmentExists(sample: CodeSampleRef) = {
      if (sample.segment.nonEmpty) {
        // Find the code segment
        val sourceCode = repo.loadFile(sample.source)(is => IO.readLines(new BufferedReader(new InputStreamReader(is)))).get
        val notLabel = (s: String) => !s.contains("#" + sample.segment)
        val segment = sourceCode dropWhile (notLabel) drop (1) takeWhile (notLabel)
        !segment.isEmpty
      } else {
        true
      }
    }

    assertLinksNotMissing("Missing source segments test", existing.collect {
      case sample if !segmentExists(sample) => LinkRef(sample.segment, sample.file, sample.segmentPosition)
    }, "Could not find source segment")

    val allLinks = report.wikiLinks.map(_.link).toSet

    pageIndex.foreach { idx =>
      // Make sure all pages are in the page index
      val orphanPages = pages.filterNot(p => idx.get(p._1).isDefined)
      doAssertion("Orphan pages test", orphanPages.toSeq) {
        orphanPages.foreach { page =>
          log.error("Page " + page._2 + " is not referenced by the index")
        }
      }
    }

    if (failed) {
      throw new RuntimeException("Documentation validation failed")
    }
  }

  val validateExternalLinksTask = Def.task {
    val log = streams.value.log
    val report = markdownGenerateRefReport.value

    val grouped = report.externalLinks
      .groupBy { _.link }
      .filterNot { e => e._1.startsWith("http://localhost:") || e._1.contains("example.com") }
      .toSeq.sortBy { _._1 }

    implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(50))

    val futures = grouped.map { entry =>
      Future {
        val (url, refs) = entry
        val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
        try {
          connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.10; rv:37.0) Gecko/20100101 Firefox/37.0")
          connection.connect()
          connection.getResponseCode match {
            // A few people use GitHub.com repositories, which will return 403 errors for directory listings
            case 403 if "GitHub.com".equals(connection.getHeaderField("Server")) => Nil
            case bad if bad >= 300 => {
              refs.foreach { link =>
                logErrorAtLocation(log, link.file, link.position, connection.getResponseCode + " response for external link " + link.link)
              }
              refs
            }
            case ok => Nil
          }
        } catch {
          case NonFatal(e) =>
            refs.foreach { link =>
              logErrorAtLocation(log, link.file, link.position, e.getClass.getName + ": " + e.getMessage + " for external link " + link.link)
            }
            refs
        } finally {
          connection.disconnect()
        }
      }
    }

    val invalidRefs = Await.result(Future.sequence(futures), Duration.Inf).flatten

    ec.shutdownNow()

    if (invalidRefs.isEmpty) {
      log.info("[" + Colors.green("pass") + "] External links test")
    } else {
      log.info("[" + Colors.red("fail") + "] External links test (" + invalidRefs.size + " errors)")
      throw new RuntimeException("External links validation failed")
    }

    grouped.map(_._1)
  }

  private def logErrorAtLocation(log: Logger, file: File, position: Int, errorMessage: String) = synchronized {
    // Load the source
    val lines = IO.readLines(file)
    // Calculate the line and col
    // Tuple is (total chars seen, line no, col no, Option[line])
    val (_, lineNo, colNo, line) = lines.foldLeft((0, 0, 0, None: Option[String])) { (state, line) =>
      state match {
        case (_, _, _, Some(_)) => state
        case (total, l, c, None) => {
          if (total + line.length < position) {
            (total + line.length + 1, l + 1, c, None)
          } else {
            (0, l + 1, position - total + 1, Some(line))
          }
        }
      }
    }
    log.error(errorMessage + " at " + file.getAbsolutePath + ":" + lineNo)
    line.foreach { l =>
      log.error(l)
      log.error(l.take(colNo - 1).map { case '\t' => '\t'; case _ => ' ' } + "^")
    }
  }
}

class AggregateFileRepository(repos: Seq[FileRepository]) extends FileRepository {

  def this(repos: Array[FileRepository]) = this(repos.toSeq)

  private def fromFirstRepo[A](load: FileRepository => Option[A]) = repos.collectFirst(Function.unlift(load))

  def loadFile[A](path: String)(loader: (InputStream) => A) = fromFirstRepo(_.loadFile(path)(loader))

  def handleFile[A](path: String)(handler: (FileHandle) => A) = fromFirstRepo(_.handleFile(path)(handler))

  def findFileWithName(name: String) = fromFirstRepo(_.findFileWithName(name))
}

class PrefixedRepository(prefix: String, repo: FileRepository) extends FileRepository {

  private def withPrefixStripped[T](path: String)(block: String => Option[T]): Option[T] = {
    if (path.startsWith(prefix)) {
      block(path.stripPrefix(prefix))
    } else None
  }

  override def loadFile[A](path: String)(loader: (InputStream) => A): Option[A] =
    withPrefixStripped(path)(repo.loadFile[A](_)(loader))

  override def handleFile[A](path: String)(handler: (FileHandle) => A): Option[A] =
    withPrefixStripped(path)(repo.handleFile[A](_)(handler))

  override def findFileWithName(name: String): Option[String] =
    repo.findFileWithName(name).map(prefix + _)
}

object Colors {

  import scala.Console._

  lazy val isANSISupported = {
    Option(System.getProperty("sbt.log.noformat")).map(_ != "true").orElse {
      Option(System.getProperty("os.name"))
        .map(_.toLowerCase(java.util.Locale.ENGLISH))
        .filter(_.contains("windows"))
        .map(_ => false)
    }.getOrElse(true)
  }

  def red(str: String): String = if (isANSISupported) (RED + str + RESET) else str
  def blue(str: String): String = if (isANSISupported) (BLUE + str + RESET) else str
  def cyan(str: String): String = if (isANSISupported) (CYAN + str + RESET) else str
  def green(str: String): String = if (isANSISupported) (GREEN + str + RESET) else str
  def magenta(str: String): String = if (isANSISupported) (MAGENTA + str + RESET) else str
  def white(str: String): String = if (isANSISupported) (WHITE + str + RESET) else str
  def black(str: String): String = if (isANSISupported) (BLACK + str + RESET) else str
  def yellow(str: String): String = if (isANSISupported) (YELLOW + str + RESET) else str

}