package sbt

object LightbendMarkdownSbtInternalTerms {
  final val EvaluateConfigurations = sbt.internal.EvaluateConfigurations
  final val Load = sbt.internal.Load
}

object LightbendMarkdownSbtInternalImplicits {
  implicit class ClasspathsCompanionOps(val companion: Classpaths.type) extends AnyVal {
    @inline def internalDependenciesInit(
        projectRef: ProjectRef,
        conf: Configuration,
        self: Configuration,
        data: Settings[Scope],
        deps: sbt.internal.BuildDependencies,
        track: TrackLevel
    ) = Classpaths.internalDependenciesImplTask(projectRef, conf, self, data, deps, track)
  }
}
