lazy val appName = "api-platform-add-api-lambda"

lazy val appDependencies: Seq[ModuleID] = compileDependencies ++ testDependencies

lazy val awsSdkVersion = "2.31.66"
lazy val mockitoVersion = "5.18.0"

lazy val compileDependencies = Seq(
  "uk.gov.hmrc"            %% "api-platform-manage-api" % "0.49.0",
  "software.amazon.awssdk"  % "sqs"                     % awsSdkVersion,
  "software.amazon.awssdk"  % "waf"                     % awsSdkVersion
)

lazy val testDependencies = Seq(
  "org.scalatest"     %% "scalatest"    % "3.2.19",
  "org.mockito"        % "mockito-core" % mockitoVersion,
  "org.scalatestplus" %% "mockito-5-18" % "3.2.19.0"
).map(_ % Test)

lazy val lambda = (project in file("."))
  .settings(
    name := appName,
    scalaVersion := "2.13.16",
    libraryDependencies ++= appDependencies,
    Test / parallelExecution := false,
    Test / fork := true,
    Test / javaOptions := Seq(
      s"-javaagent:${csrCacheDirectory.value.getAbsolutePath}/https/repo1.maven.org/maven2/org/mockito/mockito-core/$mockitoVersion/mockito-core-$mockitoVersion.jar"
    )
  )
  .settings(
    resolvers += "hmrc-releases" at "https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases/"
  )
  .settings(
    assembly / assemblyOutputPath := file(s"./$appName.zip"),
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class") => MergeStrategy.first
      case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.last
      case PathList("META-INF", xs @ _*) => MergeStrategy.first
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

// Coverage configuration
coverageMinimumStmtTotal := 90
coverageMinimumBranchTotal := 90
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>"
