lazy val appName = "api-platform-add-api-lambda"
lazy val appDependencies: Seq[ModuleID] = compileDependencies ++ testDependencies

lazy val jacksonVersion = "2.9.8"

lazy val compileDependencies = Seq(
  "io.github.mkotsur" %% "aws-lambda-scala" % "0.1.1",
  "software.amazon.awssdk" % "apigateway" % "2.5.13",
  "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion
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
      case path if path.endsWith("io.netty.versions.properties") => MergeStrategy.first
      case path =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(path)
    }
  )

// Coverage configuration
coverageMinimum := 80
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>"
