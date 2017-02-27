val monixVersion = "2.2.2"
val appSettings = Seq(
  version := "0.0.1",
  organization := "monix",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq(
    // warnings
    "-unchecked", // able additional warnings where generated code depends on assumptions
    "-deprecation", // emit warning for usages of deprecated APIs
    "-feature", // emit warning usages of features that should be imported explicitly
    // Features enabled by default
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:experimental.macros",
    // possibly deprecated options
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible"
  ),


  javacOptions ++= Seq(
    "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
    Resolver.sonatypeRepo("releases")
  ),
  evictionWarningOptions in update :=
    EvictionWarningOptions.default
      .withWarnTransitiveEvictions(false)
      .withWarnDirectEvictions(false)
      .withWarnScalaVersionEviction(false),
  libraryDependencies ++= Seq(
    "io.monix" %% "monix-reactive" % monixVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "com.typesafe" % "config" % "1.3.0",
    "org.slf4j" % "log4j-over-slf4j" % "1.7.21",
    // For testing ...
    "ch.qos.logback" % "logback-classic" % "1.1.3" % "test",
    "org.scalatest" %% "scalatest" % "3.0.0" % "test",
    "io.monix" %% "minitest" % "0.27" % "test"
  ),
  testFrameworks ++= Seq(
    new TestFramework("minitest.runner.Framework")),
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in packageDoc := false,
  sources in (Compile,doc) := Seq.empty,
  publishArtifact in Test := false
)
val monixNio = Project(id = "monix-nio", base = file("."))
  .settings(appSettings)