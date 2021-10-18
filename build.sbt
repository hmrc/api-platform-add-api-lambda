lazy val appName = "api-platform-add-api-lambda"
lazy val appDependencies: Seq[ModuleID] = compileDependencies ++ testDependencies

lazy val compileDependencies = Seq(
  "uk.gov.hmrc" %% "api-platform-manage-api" % "0.45.0",
  "software.amazon.awssdk" % "sqs" % "2.5.13",
  "software.amazon.awssdk" % "waf" % "2.5.13"
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
    scalaVersion := "2.12.10",
    libraryDependencies ++= appDependencies,
    parallelExecution in Test := false,
    fork in Test := false,
    retrieveManaged := true
  )
  .settings(
    resolvers += "hmrc-releases" at "https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases/",
    resolvers += Resolver.jcenterRepo
  )
  .settings(
    assemblyOutputPath in assembly := file(s"./$appName.zip"),
    assemblyMergeStrategy in assembly := {
      case path if path.endsWith("io.netty.versions.properties") => MergeStrategy.discard
      case path if path.endsWith("BuildInfo$.class") => MergeStrategy.discard
      case path if path.endsWith("codegen-resources/customization.config") => MergeStrategy.discard
      case path if path.endsWith("codegen-resources/paginators-1.json") => MergeStrategy.discard
      case path if path.endsWith("codegen-resources/service-2.json") => MergeStrategy.discard
      case path =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(path)
    }
  )

// Coverage configuration
coverageMinimum := 90
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>"
