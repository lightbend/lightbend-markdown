/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.sbt

import java.net.URLClassLoader

import com.lightbend.markdown.sbt.LightbendMarkdownValidation.{CodeSamplesReport, MarkdownRefReport}
import org.webjars.{FileSystemCache, WebJarExtractor}
import play.api.libs.json._
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import scala.util.control.NonFatal

object LightbendMarkdownKeys {

  implicit val fileFormat: Format[File] = Format(
    implicitly[Reads[String]].map(file),
    Writes(file => JsString(file.getAbsolutePath))
  )

  case class GenerateDocumentationConfig(
    outputDir: File,
    generateIndex: Boolean,
    config: DocumentationConfiguration
  )

  object GenerateDocumentationConfig {
    implicit val format: Format[GenerateDocumentationConfig] = Json.format[GenerateDocumentationConfig]
  }

  case class DocumentationServerConfig(
    projectPath: File = new File("."),
    port: Int = 9000,
    config: DocumentationConfiguration
  )

  object DocumentationServerConfig {
    implicit val format: Format[DocumentationServerConfig] = Json.format[DocumentationServerConfig]
  }

  case class DocumentationConfiguration(
    documentationRoot: File = file("."),
    projectName: Option[String] = None,
    theme: Option[String] = None,
    sourceUrl: Option[String] = None,
    documentation: Seq[Documentation] = Nil
  )

  object DocumentationConfiguration {
    implicit val format: Format[DocumentationConfiguration] = Json.format[DocumentationConfiguration]
  }

  case class Documentation(
    name: String,
    docsPaths: Seq[DocPath] = Nil,
    homePage: String = "Home.html",
    homePageTitle: String = "Home",
    apiDocs: Map[String, String] = Map(
      "api/java/index.html" -> "Java",
      "api/scala/index.html" -> "Scala"
    )
  )

  case class DocPath(file: File, path: String)

  object DocPath {
    implicit val format: Format[DocPath] = Json.format[DocPath]
  }

  object Documentation {
    implicit val format: Format[Documentation] = Json.format[Documentation]
  }

  val markdownDocumentationRoot = settingKey[File]("The documentation root")
  val markdownDocumentation = taskKey[Seq[Documentation]]("The markdown docs configuration")
  val markdownTheme = settingKey[Option[String]]("The markdown theme object")
  val markdownServerTheme = settingKey[Option[String]]("The markdown theme object")
  val markdownGenerateTheme = settingKey[Option[String]]("The markdown theme object")
  val markdownSourceUrl = settingKey[Option[URL]]("A URL to the source of the markdown files")
  val markdownUseBuiltinTheme = settingKey[Boolean]("Whether the builtin markdown theme should be used")
  val markdownGenerateIndex = settingKey[Boolean]("Whether to build the index of the documentation")
  val markdownValidateDocs = taskKey[Unit]("Validates the play docs to ensure they compile and that all links resolve.")
  val markdownValidateExternalLinks = taskKey[Unit]("Validates that all the external links are valid, by checking that they return 200.")
  val markdownGenerateRefReports = taskKey[Seq[MarkdownRefReport]]("Parses all markdown files and generates a report of references")
  val markdownGenerateCodeSamplesReport = taskKey[CodeSamplesReport]("Parses all markdown files and generates a report of code samples used")
  val markdownGenerateAllDocumentation = taskKey[File]("Generate all the documentation")
  val markdownExtractWebJars = taskKey[File]("Extract all the documentation webjars")
  val markdownStageSite = taskKey[File]("Stage the markdown site")
  val markdownStageIncludeWebJars = settingKey[Boolean]("Whether to include webjars in the staged site")

  val markdownEvaluateSbtFiles = taskKey[Unit]("Evaluate all the sbt files in the project")

  val RunMarkdown = config("run-markdown").hide

  private def readResourceProperty(resource: String, property: String): String = {
    val props = new java.util.Properties
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    try { props.load(stream) }
    catch { case e: Exception => }
    finally { if (stream ne null) stream.close }
    props.getProperty(property)
  }

  val LightbendMarkdownVersion = readResourceProperty("lightbend-markdown.version.properties", "lightbend-markdown.version")
}

object LightbendMarkdown extends AutoPlugin {

  import LightbendMarkdownKeys._

  val autoImport = LightbendMarkdownKeys

  override def trigger = NoTrigger

  override def requires = JvmPlugin

  override def projectSettings = docsRunSettings ++ docsTestSettings

  def docsRunSettings = Seq(
    ivyConfigurations += RunMarkdown,
    markdownDocumentationRoot := baseDirectory.value / "manual",
    markdownDocumentation := Nil,
    markdownUseBuiltinTheme := true,
    markdownGenerateIndex := false,
    markdownTheme := {
      if (markdownUseBuiltinTheme.value) {
        Some("com.lightbend.markdown.theme.builtin.BuiltinMarkdownTheme")
      } else {
        None
      }
    },
    markdownServerTheme := markdownTheme.value,
    markdownGenerateTheme := markdownTheme.value,
    markdownSourceUrl := None,
    run <<= docsRunSetting,
    markdownGenerateRefReports <<= LightbendMarkdownValidation.generateMarkdownRefReportsTask,
    markdownValidateDocs <<= LightbendMarkdownValidation.validateDocsTask,
    markdownValidateExternalLinks <<= LightbendMarkdownValidation.validateExternalLinksTask,
    libraryDependencies += {
      val version = LightbendMarkdownVersion
      val artifact =
        if (markdownUseBuiltinTheme.value) {
          "lightbend-markdown-theme-builtin"
        } else {
          "lightbend-markdown-server"
        }
      "com.lightbend.markdown" % s"${artifact}_${scalaBinaryVersion.value}" % version % RunMarkdown.name
    },

    internalDependencyClasspath in RunMarkdown <<= (thisProjectRef, settingsData, buildDependencies) flatMap { (tpf, sd, bd) =>
      Classpaths.internalDependencies0(tpf, RunMarkdown, RunMarkdown, sd, bd)
    },
    externalDependencyClasspath in RunMarkdown := {
      Classpaths.managedJars(RunMarkdown, (classpathTypes in RunMarkdown).value, update.value)
    },
    dependencyClasspath in RunMarkdown := (internalDependencyClasspath in RunMarkdown).value ++
      (externalDependencyClasspath in RunMarkdown).value,

    target in markdownGenerateAllDocumentation := target.value / "markdown-generated-docs",
    markdownGenerateAllDocumentation <<= markdownGenerateAllDocumentationSetting,
    target in markdownExtractWebJars := target.value / "markdown-webjars",
    markdownExtractWebJars <<= markdownExtractWebJarsSetting,
    target in markdownStageSite := target.value / "markdown-site",
    markdownStageSite <<= markdownStageSiteSetting,
    markdownStageIncludeWebJars := false,

    includeFilter in markdownStageSite := "*"
  )

  def docsTestSettings = Seq(
    unmanagedSourceDirectories in Test ++= (baseDirectory.value / "manual" ** "code").get,

    markdownEvaluateSbtFiles := {
      val unit = loadedBuild.value.units(thisProjectRef.value.build)
      val (eval, structure) = Load.defaultLoad(state.value, unit.localBase, state.value.log)
      val sbtFiles = ((unmanagedSourceDirectories in Test).value * "*.sbt").get
      val log = state.value.log
      if (sbtFiles.nonEmpty) {
        log.info("Testing .sbt files...")
      }
      val result = sbtFiles.map { sbtFile =>
        val relativeFile = relativeTo(baseDirectory.value)(sbtFile).getOrElse(sbtFile.getAbsolutePath)
        try {
          EvaluateConfigurations.evaluateConfiguration(eval(), sbtFile, unit.imports)(unit.loader)
          log.info(s"  ${Colors.green("+")} $relativeFile")
          true
        } catch {
          case NonFatal(_) =>
            log.error(s" ${Colors.yellow("x")} $relativeFile")
            false
        }
      }
      if (result.contains(false)) {
        throw new TestsFailedException
      }
    },

    parallelExecution in Test := false,
    javacOptions in Test ++= Seq("-g", "-Xlint:deprecation"),
    testOptions in Test += Tests.Argument(TestFrameworks.Specs2, "sequential", "true", "junitxml", "console"),
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit, "-v", "--ignore-runners=org.specs2.runner.JUnitRunner")
  )

  // Run a documentation server
  private val docsRunSetting: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val args = Def.spaceDelimited().parsed
    val port = args.headOption.getOrElse("9000").toInt

    val classpath = (dependencyClasspath in RunMarkdown).value
    val classpathOption = Path.makeString(classpath.map(_.data))

    val docsConfig = DocumentationServerConfig(target.value, port, DocumentationConfiguration(
      markdownDocumentationRoot.value, Some(name.value), markdownServerTheme.value,
      markdownSourceUrl.value.map(_.toString), markdownDocumentation.value))

    val docsConfigPath = target.value / "docs-server-config.json"
    IO.write(docsConfigPath, Json.stringify(Json.toJson(docsConfig)))

    val options = Seq(
      "-classpath", classpathOption,
      "com.lightbend.markdown.server.DocumentationServer",
      docsConfigPath.getAbsolutePath
    )
    val process = Fork.java.fork(ForkOptions(), options)

    println()
    println(Colors.green("Documentation server started, you can now view the docs by going to http://localhost:" + port))
    println()

    waitForKey()

    process.destroy()
  }

  private val markdownGenerateAllDocumentationSetting = Def.task {
    val classpath = (dependencyClasspath in RunMarkdown).value
    val classpathOption = Path.makeString(classpath.map(_.data))

    val outputDir = (target in markdownGenerateAllDocumentation).value

    val docsConfig = GenerateDocumentationConfig(outputDir, markdownGenerateIndex.value,
      DocumentationConfiguration(markdownDocumentationRoot.value, Some(name.value), markdownGenerateTheme.value,
        markdownSourceUrl.value.map(_.toString), markdownDocumentation.value))

    val docsConfigPath = target.value / "docs-generation-config.json"
    IO.write(docsConfigPath, Json.stringify(Json.toJson(docsConfig)))

    val options = Seq(
      "-classpath", classpathOption,
      "com.lightbend.markdown.generator.GenerateSite",
      docsConfigPath.getAbsolutePath
    )

    val process = Fork.java.fork(ForkOptions(), options)

    if (process.exitValue() != 0) {
      sys.error("GenerateSite failed: " + process.exitValue())
    }

    outputDir
  }


  private val markdownExtractWebJarsSetting = Def.task {
    val classpath = (dependencyClasspath in RunMarkdown).value
    val classloader = new URLClassLoader(classpath.map(_.data.toURI.toURL).toArray, null)

    val outputDir = (target in markdownExtractWebJars).value
    val extractor = new WebJarExtractor(new FileSystemCache(streams.value.cacheDirectory / "markdown-extract-webjars"), classloader)

    extractor.extractAllWebJarsTo(outputDir)
    classloader.close()

    outputDir
  }

  private val markdownStageSiteSetting = Def.task {
    val stageDir = (target in markdownStageSite).value

    val allDocsDir = markdownGenerateAllDocumentation.value
    val generatedDocMappings = allDocsDir.***.filter(!_.isDirectory).get pair rebase(allDocsDir, stageDir)
    val docPathMappings = for {
      documentation <- markdownDocumentation.value
      DocPath(path, prefix) <- documentation.docsPaths
      p = if (prefix == ".") "" else if (prefix.endsWith("/")) prefix else prefix + "/"
      files = path.descendantsExcept((includeFilter in markdownStageSite).value, (excludeFilter in markdownStageSite).value).get
      (file, mapping) <- files.filterNot(_.isDirectory) pair relativeTo(path)
    } yield {
      val prefixedMapping = p + mapping
      val finalMapping = if (prefixedMapping.startsWith("api/")) {
        s"${documentation.name}/$prefixedMapping"
      } else {
        s"${documentation.name}/resources/$prefixedMapping"
      }
      file -> stageDir / finalMapping
    }
    val webJarsDir = markdownExtractWebJars.value
    val webJarMappings = if (markdownStageIncludeWebJars.value) {
      (webJarsDir.***.filter(!_.isDirectory).get pair relativeTo(webJarsDir)) map {
        case (file, path) => file -> stageDir / "webjars" / path
      }
    } else Nil

    val allMappings = generatedDocMappings ++ docPathMappings ++ webJarMappings

    Sync(streams.value.cacheDirectory / "markdown-site-cache")(allMappings)

    println("Site is staged to " + stageDir)

    stageDir
  }

  private lazy val consoleReader = {
    val cr = new jline.console.ConsoleReader
    // Because jline, whenever you create a new console reader, turns echo off. Stupid thing.
    cr.getTerminal.setEchoEnabled(true)
    cr
  }

  private def waitForKey() = {
    consoleReader.getTerminal.setEchoEnabled(false)
    def waitEOF() {
      consoleReader.readCharacter() match {
        case 4 => // STOP
        case 11 =>
          consoleReader.clearScreen(); waitEOF()
        case 10 =>
          println(); waitEOF()
        case _ => waitEOF()
      }

    }
    waitEOF()
    consoleReader.getTerminal.setEchoEnabled(true)
  }

}
