/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package com.typesafe.markdown.sbt

import com.typesafe.markdown.sbt.TypesafeMarkdownValidation._
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import scala.util.control.NonFatal

object TypesafeMarkdownKeys {
  val markdownManualPath = settingKey[File]("The location of the manual")
  val markdownDocPaths = taskKey[Seq[(File, String)]]("The paths to include in the documentation")
  val markdownApiDocs = settingKey[Seq[(String, String)]]("The API docs links to render")
  val markdownValidateDocs = taskKey[Unit]("Validates the play docs to ensure they compile and that all links resolve.")
  val markdownValidateExternalLinks = taskKey[Seq[String]]("Validates that all the external links are valid, by checking that they return 200.")
  val markdownGenerateRefReport = taskKey[MarkdownRefReport]("Parses all markdown files and generates a report of references")
  val markdownGenerateCodeSamplesReport = taskKey[CodeSamplesReport]("Parses all markdown files and generates a report of code samples used")

  val markdownEvaluateSbtFiles = taskKey[Unit]("Evaluate all the sbt files in the project")

  val RunMarkdown = config("run-markdown").hide
}

object TypesafeMarkdown extends AutoPlugin {

  import TypesafeMarkdownKeys._

  val autoImport = TypesafeMarkdownKeys

  override def trigger = NoTrigger

  override def requires = JvmPlugin

  override def projectSettings = docsRunSettings ++ docsTestSettings

  def docsRunSettings = Seq(
    ivyConfigurations += RunMarkdown,
    markdownManualPath := baseDirectory.value / "manual",
    markdownDocPaths := Seq(markdownManualPath.value -> "."),
    markdownApiDocs := Seq(
      "api/java/index.html" -> "Java",
      "api/scala/index.html" -> "Scala"
    ),
    run <<= docsRunSetting,
    markdownGenerateRefReport <<= TypesafeMarkdownValidation.generateMarkdownRefReportTask,
    markdownValidateDocs <<= TypesafeMarkdownValidation.validateDocsTask,
    markdownValidateExternalLinks <<= TypesafeMarkdownValidation.validateExternalLinksTask,
    libraryDependencies ++= Seq(
      "com.typesafe.markdown" % "typesafe-markdown-server_2.11" %
        readResourceProperty("typesafe-markdown.version.properties", "typesafe-markdown.version") % RunMarkdown.name
    )
  )

  def docsTestSettings = Seq(
    unmanagedSourceDirectories in Test ++= (markdownManualPath.value ** "code").get,

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

  val docsJarFileSetting: Def.Initialize[Task[Option[File]]] = Def.task {
    val jars = update.value.matching(configurationFilter("docs") && artifactFilter(`type` = "jar")).toList
    jars match {
      case Nil =>
        streams.value.log.error("No docs jar was resolved")
        None
      case jar :: Nil =>
        Option(jar)
      case multiple =>
        streams.value.log.error("Multiple docs jars were resolved: " + multiple)
        multiple.headOption
    }
  }

  // Run a documentation server
  val docsRunSetting: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val args = Def.spaceDelimited().parsed
    val port = args.headOption.getOrElse("9000")

    val ct = (classpathTypes in RunMarkdown).value
    val report = update.value

    val classpath = Classpaths.managedJars(RunMarkdown, ct, report)
    val classpathOption = Path.makeString(classpath.map(_.data))
    val docPathsOption = markdownDocPaths.value.map(p => p._1.getAbsolutePath + "=" + p._2).mkString(",")
    val apiDocsOptions = markdownApiDocs.value.map(a => a._1 + "=" + a._2).mkString(",")

    val options = Seq(
      "-classpath", classpathOption,
      "com.typesafe.markdown.server.DocumentationServer",
      "-p", port,
      "-d", docPathsOption,
      "-n", name.value,
      "-a", apiDocsOptions
    )

    val process = Fork.java.fork(ForkOptions(), options)

    println()
    println(Colors.green("Documentation server started, you can now view the docs by going to http://localhost:" + port))
    println()

    waitForKey()

    process.destroy()
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

  private def readResourceProperty(resource: String, property: String): String = {
    val props = new java.util.Properties
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    try { props.load(stream) }
    catch { case e: Exception => }
    finally { if (stream ne null) stream.close }
    props.getProperty(property)
  }

}
