val monixVersion = "2.2.1"
val appSettings = Seq(
  version := "0.0.1",
  organization := "monix",
  scalaVersion := "2.11.8",
  scalacOptions ++= Seq(
    // turns all warnings into errors ;-)
    "-Xfatal-warnings",
    // possibly old/deprecated linter options
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Yinline-warnings",
    "-Ywarn-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Xlog-free-terms",
    // enables linter options
    "-Xlint:adapted-args", // warn if an argument list is modified to match the receiver
    "-Xlint:nullary-unit", // warn when nullary methods return Unit
    "-Xlint:inaccessible", // warn about inaccessible types in method signatures
    "-Xlint:nullary-override", // warn when non-nullary `def f()' overrides nullary `def f'
    "-Xlint:infer-any", // warn when a type argument is inferred to be `Any`
    "-Xlint:-missing-interpolator", // disables missing interpolator warning
    "-Xlint:doc-detached", // a ScalaDoc comment appears to be detached from its element
    "-Xlint:private-shadow", // a private field (or class parameter) shadows a superclass field
    "-Xlint:type-parameter-shadow", // a local type parameter shadows a type already in scope
    "-Xlint:poly-implicit-overload", // parametrized overloaded implicit methods are not visible as view bounds
    "-Xlint:option-implicit", // Option.apply used implicit view
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit
    "-Xlint:by-name-right-associative", // By-name parameter of right associative operator
    "-Xlint:package-object-classes", // Class or object defined in package object
    "-Xlint:unsound-match" // Pattern match may not be typesafe
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