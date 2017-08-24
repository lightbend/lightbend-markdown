val runtimeLibrarySettings = Seq(
  crossScalaVersions := Seq("2.12.3", "2.11.11"),
  scalaVersion := crossScalaVersions.value.head
)
val sbtPluginSettings = Seq(
  crossScalaVersions := Seq("2.10.6"),
  scalaVersion := crossScalaVersions.value.head
)

val PlayVersion = "2.6.3"
val PlayJsonVersion = "2.6.3"

def playLibrary(name: String): ModuleID =
  "com.typesafe.play" %% name % PlayVersion

lazy val root = (project in file("."))
  .settings(common: _*)
  .settings(
    name := "lightbend-markdown",
    publish := {},
    PgpKeys.publishSigned := {},
    publishTo := Some(Resolver.file("dummy", target.value / "dummy"))
  )
  .enablePlugins(CrossPerProjectPlugin)
  .aggregate(server, plugin, theme)

lazy val playDoc = "com.typesafe.play" %% "play-doc" % "1.8.1"

lazy val server = (project in file("server"))
  .settings(common: _*)
  .settings(runtimeLibrarySettings: _*)
  .enablePlugins(SbtTwirl)
  .settings(
    name := "lightbend-markdown-server",
    libraryDependencies ++= Seq(
      playLibrary("play-akka-http-server"),
      playLibrary("play-logback"),
      playDoc,
      "com.github.scopt" %% "scopt" % "3.6.0",
      "org.webjars" % "webjars-locator-core" % "0.30"
    )
  )

lazy val plugin = (project in file("plugin"))
  .settings(common: _*)
  .settings(sbtPluginSettings: _*)
  .settings(
    name := "sbt-lightbend-markdown",
    libraryDependencies ++= Seq(
      "org.webjars" % "webjars-locator-core" % "0.30",
      playDoc,
      "com.typesafe.play" %% "play-json" % PlayJsonVersion
    ),
    sbtPlugin := true,
    resourceGenerators in Compile <+= generateVersionFile
  )

lazy val theme = (project in file("theme"))
  .enablePlugins(SbtWeb, SbtTwirl)
  .settings(common: _*)
  .settings(runtimeLibrarySettings: _*)
  .settings(
    name := "lightbend-markdown-builtin-theme",
    libraryDependencies ++= Seq(
      "org.webjars" % "jquery" % "1.9.0",
      "org.webjars" % "prettify" % "4-Mar-2013"
    ),
    pipelineStages in Assets := Seq(uglify),
    LessKeys.compress := true
  ).dependsOn(server)

import ReleaseTransformations._

def common: Seq[Setting[_]] = Seq(
  organization := "com.lightbend.markdown",

  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  bintrayOrganization := Some("typesafe"),
  bintrayRepository := "ivy-releases",
  bintrayPackage := "lightbend-markdown",
  bintrayReleaseOnPublish := false,
  publishMavenStyle := false,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,

  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("+publishSigned"),
    releaseStepTask(bintrayRelease in thisProjectRef.value),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

/**
 * sbt release's releaseStepCommand does not execute remaining commands, which sbt-doge relies on
 */
def releaseStepCommandAndRemaining(command: String): State => State = { originalState =>
  // Capture current remaining commands
  val originalRemaining = originalState.remainingCommands

  def runCommand(command: String, state: State): State = {
    import sbt.complete.Parser
    val newState = Parser.parse(command, state.combinedParser) match {
      case Right(cmd) => cmd()
      case Left(msg) => throw sys.error(s"Invalid programmatic input:\n$msg")
    }
    if (newState.remainingCommands.isEmpty) {
      newState
    } else {
      runCommand(newState.remainingCommands.head, newState.copy(remainingCommands = newState.remainingCommands.tail))
    }
  }

  runCommand(command, originalState.copy(remainingCommands = Nil)).copy(remainingCommands = originalRemaining)
}

def generateVersionFile = Def.task {
  val version = (Keys.version in server).value
  val file = (resourceManaged in Compile).value / "lightbend-markdown.version.properties"
  val content = s"lightbend-markdown.version=$version"
  IO.write(file, content)
  Seq(file)
}

