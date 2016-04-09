/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.markdown.sbt

import java.net.URLClassLoader

import awscala.{Region0, Region}
import com.lightbend.markdown.sbt.LightbendMarkdownValidation.{CodeSamplesReport, MarkdownRefReport}
import org.apache.commons.codec.digest.DigestUtils
import org.webjars.{FileSystemCache, WebJarExtractor}
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import scala.util.control.NonFatal

object LightbendMarkdownKeys {
  val markdownDocsTitle = settingKey[String]("The title of the documentation")
  val markdownManualPath = settingKey[File]("The location of the manual")
  val markdownDocPaths = taskKey[Seq[(File, String)]]("The paths to include in the documentation")
  val markdownApiDocs = settingKey[Seq[(String, String)]]("The API docs links to render")
  val markdownTheme = settingKey[Option[String]]("The markdown theme object")
  val markdownServerTheme = settingKey[Option[String]]("The markdown theme object")
  val markdownGenerateTheme = settingKey[Option[String]]("The markdown theme object")
  val markdownSourceUrl = settingKey[Option[URL]]("A URL to the source of the markdown files")
  val markdownUseBuiltinTheme = settingKey[Boolean]("Whether the builtin markdown theme should be used")
  val markdownGenerateIndex = settingKey[Boolean]("Whether to build the index of the documentation")
  val markdownValidateDocs = taskKey[Unit]("Validates the play docs to ensure they compile and that all links resolve.")
  val markdownValidateExternalLinks = taskKey[Seq[String]]("Validates that all the external links are valid, by checking that they return 200.")
  val markdownGenerateRefReport = taskKey[MarkdownRefReport]("Parses all markdown files and generates a report of references")
  val markdownGenerateCodeSamplesReport = taskKey[CodeSamplesReport]("Parses all markdown files and generates a report of code samples used")
  val markdownGenerateAllDocumentation = taskKey[File]("Generate all the documentation")
  val markdownExtractWebJars = taskKey[File]("Extract all the documentation webjars")
  val markdownStageSite = taskKey[File]("Stage the markdown site")

  val markdownS3PublishDocs = taskKey[Unit]("Publish the documentation to S3")
  val markdownS3Bucket = settingKey[Option[String]]("The S3 bucket to publish to")
  val markdownS3Prefix = settingKey[String]("The S3 directory to publish to")
  val markdownS3CredentialsHost = settingKey[String]("The S3 credentials host to get credentials for")
  val markdownS3Region = settingKey[Region]("The AWS region to use")
  val markdownS3DeletionThreshold = settingKey[Int]("The maximum number of files to delete when cleaning up, if this is exceeded, the upload will abort")
  val markdownContentTypes = settingKey[Map[String, String]]("A map of file name extensions to content types, used to tell S3 what content type a file should be.")

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
    markdownDocsTitle := name.value,
    markdownManualPath := baseDirectory.value / "manual",
    markdownDocPaths := Seq(markdownManualPath.value -> "."),
    markdownApiDocs := Seq(
      "api/java/index.html" -> "Java",
      "api/scala/index.html" -> "Scala"
    ),
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
    markdownGenerateRefReport <<= LightbendMarkdownValidation.generateMarkdownRefReportTask,
    markdownValidateDocs <<= LightbendMarkdownValidation.validateDocsTask,
    markdownValidateExternalLinks <<= LightbendMarkdownValidation.validateExternalLinksTask,
    libraryDependencies += {
      val version = LightbendMarkdownVersion
      val artifact = if (markdownUseBuiltinTheme.value) {
        "lightbend-markdown-theme-builtin_2.11"
      } else {
        "lightbend-markdown-server_2.11"
      }
      "com.lightbend.markdown" % artifact % version % RunMarkdown.name

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
    markdownContentTypes := ContentTypes,
    target in markdownExtractWebJars := target.value / "markdown-webjars",
    markdownExtractWebJars <<= markdownExtractWebJarsSetting,
    target in markdownStageSite := target.value / "markdown-site",
    markdownStageSite <<= markdownStageSiteSetting,

    markdownS3PublishDocs <<= markdownS3PublishDocsSetting,
    markdownS3Bucket := None,
    // The reason we don't default this to no prefix is because otherwise, it would delete everything in the bucket if
    // someone forgot to configure it, which obviously we don't want.
    markdownS3Prefix := "please/configure/markdown/s3/prefix/",
    markdownS3CredentialsHost := "s3.amazonaws.com",
    markdownS3Region := Region0.US_EAST_1,
    markdownS3DeletionThreshold := 30,
    includeFilter in markdownS3PublishDocs := "*"
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

  private val docsJarFileSetting: Def.Initialize[Task[Option[File]]] = Def.task {
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
  private val docsRunSetting: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    val args = Def.spaceDelimited().parsed
    val port = args.headOption.getOrElse("9000")

    val classpath = (dependencyClasspath in RunMarkdown).value
    val classpathOption = Path.makeString(classpath.map(_.data))
    val docPathsOption = markdownDocPaths.value.map(p => p._1.getAbsolutePath + "=" + p._2).mkString(",")
    val apiDocsOptions = markdownApiDocs.value.map(a => a._1 + "=" + a._2).mkString(",")
    val markdownThemeOption = markdownServerTheme.value.fold(Seq.empty[String])(Seq("-t", _))
    val markdownSourceUrlOption = markdownSourceUrl.value.fold(Seq.empty[String])(url => Seq("-s", url.toString))

    val options = Seq(
      "-classpath", classpathOption,
      "com.lightbend.markdown.server.DocumentationServer",
      "-p", port,
      "-d", docPathsOption,
      "-n", markdownDocsTitle.value,
      "-a", apiDocsOptions
    ) ++ markdownThemeOption ++ markdownSourceUrlOption

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

    val docPathsOption = markdownDocPaths.value.map(p => p._1.getAbsolutePath + "=" + p._2).mkString(",")
    val apiDocsOptions = markdownApiDocs.value.map(a => a._1 + "=" + a._2).mkString(",")
    val outputDir = (target in markdownGenerateAllDocumentation).value
    val markdownThemeOption = markdownGenerateTheme.value.fold(Seq.empty[String])(Seq("-t", _))
    val markdownSourceUrlOption = markdownSourceUrl.value.fold(Seq.empty[String])(url => Seq("-s", url.toString))
    val markdownGenerateIndexOption = if (markdownGenerateIndex.value) Seq("-g") else Nil

    val options = Seq(
      "-classpath", classpathOption,
      "com.lightbend.markdown.generator.GenerateSite",
      "-d", docPathsOption,
      "-n", markdownDocsTitle.value,
      "-a", apiDocsOptions,
      "-o", outputDir.getAbsolutePath
    ) ++ markdownThemeOption ++ markdownSourceUrlOption ++ markdownGenerateIndexOption

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
    val docPathMappings = markdownDocPaths.value.flatMap {
      case (path, prefix) =>
        val p = if (prefix == ".") "" else if (prefix.endsWith("/")) prefix else prefix + "/"
        val files = path.descendantsExcept((includeFilter in markdownS3PublishDocs).value, (excludeFilter in markdownS3PublishDocs).value).get
        files.filterNot(_.isDirectory) pair relativeTo(path) map {
          case (file, mapping) => {
            val prefixedMapping = p + mapping
            val finalMapping = if (prefixedMapping.startsWith("api/")) {
              prefixedMapping
            } else {
              "resources/" + prefixedMapping
            }
            file -> stageDir / finalMapping
          }
        }
    }
    val webJarsDir = markdownExtractWebJars.value
    val webJarMappings = (webJarsDir.***.filter(!_.isDirectory).get pair relativeTo(webJarsDir)) map {
      case (file, path) => file -> stageDir / "webjars" / path
    }

    val allMappings = generatedDocMappings ++ docPathMappings ++ webJarMappings

    Sync(streams.value.cacheDirectory / "markdown-site-cache")(allMappings)

    println("Site is staged to " + stageDir)

    stageDir
  }

  private val markdownS3PublishDocsSetting = Def.task {

    import awscala.s3.{S3ObjectSummary, S3}
    import com.amazonaws.services.s3.model.{ ListObjectsRequest, ObjectListing, DeleteObjectsRequest, ObjectMetadata }
    import scala.collection.JavaConverters._

    val s3Prefix = markdownS3Prefix.value
    val log = streams.value.log
    val deleteThreshold = markdownS3DeletionThreshold.value

    val allDocsDir = markdownGenerateAllDocumentation.value
    val generatedDocMappings = (allDocsDir.***.filter(!_.isDirectory).get pair relativeTo(allDocsDir)) map {
      case (file, path) => (s3Prefix + path, (file, Some("text/html; charset=utf8")))
    }
    val docPathMappings = markdownDocPaths.value.flatMap {
      case (path, prefix) =>
        val p = if (prefix == ".") "" else if (prefix.endsWith("/")) prefix else prefix + "/"
        val files = path.descendantsExcept((includeFilter in markdownS3PublishDocs).value, (excludeFilter in markdownS3PublishDocs).value).get
        files.filterNot(_.isDirectory) pair relativeTo(path) map {
          case (file, mapping) => file -> (p + mapping)
        }
    } map {
      case (file, path) =>
        val key = if (path.startsWith("api/")) {
          s3Prefix + path
        } else {
          s3Prefix + "resources/" + path
        }
        (key, (file, detectContentType(markdownContentTypes.value, file.getName)))
    }
    val webJarsDir = markdownExtractWebJars.value
    val webJarMappings = (webJarsDir.***.filter(!_.isDirectory).get pair relativeTo(webJarsDir)) map {
      case (file, path) => (s3Prefix + "webjars/" + path, (file, detectContentType(markdownContentTypes.value, file.getName)))
    }

    val allMappings = generatedDocMappings ++ docPathMappings ++ webJarMappings
    val allMappingsMap = allMappings.toMap

    val s3Credentials = Credentials.forHost(credentials.value, markdownS3CredentialsHost.value)
      .getOrElse(sys.error("No credentials found for " + markdownS3CredentialsHost.value))

    implicit val region = markdownS3Region.value
    val s3 = S3(s3Credentials.userName, s3Credentials.passwd)
    val bucketName = markdownS3Bucket.value.getOrElse(sys.error("No S3 bucket configured, you need to set markdownS3Bucket"))
    val bucket = s3.bucket(bucketName).getOrElse(sys.error(s"S3 bucket $bucketName not found"))

    def listObjects(prefix: String): Vector[S3ObjectSummary] = {

      val request = new ListObjectsRequest().withBucketName(bucket.getName).withPrefix(prefix)

      def completeStream(listing: ObjectListing, nextPage: Int): Vector[S3ObjectSummary] = {
        val objects = listing.getObjectSummaries.asScala.map(S3ObjectSummary(bucket, _)).toVector

        objects ++ (if (listing.isTruncated) {
          log.info(s"Getting page $nextPage of object listing...")
          completeStream(s3.listNextBatchOfObjects(listing), nextPage + 1)
        } else Vector.empty)
      }

      log.info("Getting page 1 of object listing...")
      val firstListing = s3.listObjects(request)
      completeStream(firstListing, 2)
    }

    val allObjects = listObjects(markdownS3Prefix.value)
    val allObjectsMap = allObjects.map(o => o.getKey -> o).toMap

    val toDelete = allObjects.filterNot { obj =>
      allMappingsMap.contains(obj.getKey)
    }
    val (existingFiles, newFiles) = allMappings.partition {
      case (key, _) => allObjectsMap.contains(key)
    }
    val (inSync, toUpdate) = existingFiles.partition {
      case (key, (file, _)) => allObjectsMap(key).getETag == md5(file)
    }

    def putFile(key: String, file: File, contentType: Option[String]): Unit = {
      val metaData = new ObjectMetadata()
      contentType.foreach(metaData.setContentType)
      metaData.setContentLength(file.length())
      s3.putObject(bucket, key, IO.readBytes(file), metaData)
    }

    // Sanity check
    if (toDelete.size > deleteThreshold) {
      log.error(s"Requested syncing to bucket $bucketName to folder $s3Prefix, but this will mean deleting ${toDelete.size} files that exist there and are not needed. You probably have misconfigured the prefix. If not, please manually clean up the folder first. Aborting.")
      sys.error("Aborting potentially too destructive operation.")
    }

    newFiles.foreach {
      case (key, (file, contentType)) =>
        log.info(s"Uploading $key...")
        putFile(key, file, contentType)
    }
    toUpdate.foreach {
      case (key, (file, contentType)) =>
        log.info(s"Updating $key...")
        putFile(key, file, contentType)
    }
    if (toDelete.nonEmpty) {
      log.info(s"Cleaning up ${toDelete.size} old files...")
      val deleteReq = new DeleteObjectsRequest(bucketName).withKeys(toDelete.map(_.getKey): _*)
      s3.deleteObjects(deleteReq)
    }

    log.info("Documentation sync complete!")
    log.info(s"Uploaded ${newFiles.size} new files, updated ${toUpdate.size} existing files, deleted ${toDelete.size} old files, and ${inSync.size} files were in sync.")
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

  private def detectContentType(contentTypes: Map[String, String], name: String): Option[String] = {
    val extension = name.split('.').last
    contentTypes.get(extension)
  }

  private val ContentTypes = Map(
    "html" -> "text/html; charset=utf8",
    "css" -> "text/css; charset=utf8",
    "js" -> "application/javascript",
    "txt" -> "text/plain; charset=utf8",
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "gif" -> "image/gif",
    "ico" -> "image/x-icon",
    "pdf" -> "application/pdf"
  )

  private def md5(file: File): String = {
    DigestUtils.md5Hex(IO.readBytes(file))
  }

}
