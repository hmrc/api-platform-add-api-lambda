lazy val appName = "api-platform-add-api-lambda"

lazy val appDependencies: Seq[ModuleID] = compileDependencies ++ testDependencies

lazy val awsSdkVersion = "2.31.59"

lazy val compileDependencies = Seq(
  "uk.gov.hmrc"            %% "api-platform-manage-api" % "0.47.0",
  "software.amazon.awssdk"  % "sqs"                     % awsSdkVersion,
  "software.amazon.awssdk"  % "waf"                     % awsSdkVersion
)

lazy val testDependencies = Seq(
  "org.scalatest"        %% "scalatest"                      % "3.2.18",
  "com.vladsch.flexmark"  % "flexmark-all"                   % "0.64.8",
  "org.mockito"          %% "mockito-scala-scalatest"        % "1.17.29"
).map(_ % Test)

lazy val lambda = (project in file("."))
  .settings(
    name := appName,
    scalaVersion := "2.13.16",
    libraryDependencies ++= appDependencies,
    Test / parallelExecution := false,
    Test / fork := false
  )
  .settings(
    resolvers += "hmrc-releases" at "https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases/",
  )
  .settings(
    assembly / assemblyOutputPath := file(s"./$appName.zip"),
    assembly / assemblyMergeStrategy := {
      case path if path.endsWith("io.netty.versions.properties") => MergeStrategy.discard
      case path if path.endsWith("BuildInfo$.class") => MergeStrategy.discard
      case path if path.endsWith("codegen-resources/customization.config") => MergeStrategy.discard
      case path if path.endsWith("codegen-resources/paginators-1.json") => MergeStrategy.discard
      case path if path.endsWith("codegen-resources/service-2.json") => MergeStrategy.discard
      case path =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(path)
    }
  )
  .settings(Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.AllLibraryJars)

// Coverage configuration
coverageMinimumStmtTotal := 90
coverageMinimumBranchTotal := 90
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>"
