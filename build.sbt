lazy val appName = "api-platform-add-api-lambda"
lazy val appDependencies: Seq[ModuleID] = compileDependencies ++ testDependencies

lazy val compileDependencies = Seq(
  "uk.gov.hmrc" %% "aws-gateway-proxied-request-lambda" % "0.4.0",
  "uk.gov.hmrc" %% "api-platform-manage-api" % "0.4.0"
)

lazy val testScope: String = "test"

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.0.5" % testScope,
  "org.mockito" % "mockito-core" % "2.25.1" % testScope
)

lazy val plugins: Seq[Plugins] = Seq()

lazy val lambda = (project in file("."))
  .enablePlugins(plugins: _*)
  .settings(
    name := appName,
    scalaVersion := "2.11.11",
    libraryDependencies ++= appDependencies,
    parallelExecution in Test := false,
    fork in Test := false,
    retrieveManaged := true
  )
  .settings(
    resolvers += Resolver.bintrayRepo("hmrc", "releases"),
    resolvers += Resolver.jcenterRepo
  )
  .settings(
    assemblyJarName in assembly := s"$appName.zip",
    assemblyMergeStrategy in assembly := {
      case path if path.endsWith("io.netty.versions.properties") => MergeStrategy.discard
      case path if path.endsWith("BuildInfo$.class") => MergeStrategy.discard
      case path =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(path)
    }
  )

// Coverage configuration
coverageMinimum := 70
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>"
