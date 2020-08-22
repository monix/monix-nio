import scala.xml.Elem
import scala.xml.transform.{RewriteRule, RuleTransformer}
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import ReleaseTransformations._

import scalariform.formatter.preferences._
import sbt.addCommandAlias
import sbtrelease.{Version, versionFormatError}

addCommandAlias("release", ";+publishSigned ;sonatypeReleaseAll")

val monixVersion = "3.2.2"

val appSettings = Seq(
  name := "monix-nio",
  organization := "io.monix",

  scalaVersion := "2.13.3",
  crossScalaVersions := Seq("2.11.12", "2.12.12", "2.13.3"),

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
    "-Ywarn-dead-code"
  ),

  // Targeting Java 7, but only for Scala <= 2.11
  javacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 11 =>
      // generates code with the Java 6 class format
      Seq("-source", "1.7", "-target", "1.7")
    case _ =>
      // For 2.12 we are targeting the Java 8 class format
      Seq("-source", "1.8", "-target", "1.8")
  }),

  // Targeting Java 7, but only for Scala <= 2.11
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion <= 11 =>
      // Generates code with the Java 7 class format
      Seq("-target:jvm-1.7")
    case _ =>
      // For 2.12 we are targeting the Java 8 class format, the default
      Seq.empty
  }),

  // Linter
  scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, majorVersion)) if majorVersion >= 11 =>
      Seq(
        // Turns all warnings into errors ;-)
        // "-Xfatal-warnings",
        // Enables linter options
        "-Xlint:adapted-args", // warn if an argument list is modified to match the receiver
        "-Xlint:nullary-unit", // warn when nullary methods return Unit
        "-Xlint:inaccessible", // warn about inaccessible types in method signatures
        "-Xlint:infer-any", // warn when a type argument is inferred to be `Any`
        "-Xlint:missing-interpolator", // a string literal appears to be missing an interpolator id
        "-Xlint:doc-detached", // a ScalaDoc comment appears to be detached from its element
        "-Xlint:private-shadow", // a private field (or class parameter) shadows a superclass field
        "-Xlint:type-parameter-shadow", // a local type parameter shadows a type already in scope
        "-Xlint:poly-implicit-overload", // parametrized overloaded implicit methods are not visible as view bounds
        "-Xlint:option-implicit", // Option.apply used implicit view
        "-Xlint:delayedinit-select", // Selecting member of DelayedInit
        "-Xlint:package-object-classes", // Class or object defined in package object
      )
    case _ =>
      Seq.empty
  }),

  // Turning off fatal warnings for ScalaDoc, otherwise we can't release.
  scalacOptions in (Compile, doc) ~= (_ filterNot (_ == "-Xfatal-warnings")),

  // ScalaDoc settings
  autoAPIMappings := true,
  scalacOptions in ThisBuild ++= Seq(
    // Note, this is used by the doc-source-url feature to determine the
    // relative path of a given source file. If it's not a prefix of a the
    // absolute path of the source file, the absolute path of that file
    // will be put into the FILE_SOURCE variable, which is
    // definitely not what we want.
    "-sourcepath", file(".").getAbsolutePath.replaceAll("[.]$", "")
  ),

  parallelExecution in Test := false,
  parallelExecution in IntegrationTest := false,
  testForkedParallel in Test := false,
  testForkedParallel in IntegrationTest := false,
  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),

  resolvers ++= Seq(
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases",
    Resolver.sonatypeRepo("releases")
  ),

  evictionWarningOptions in update :=
    EvictionWarningOptions.default
      .withWarnTransitiveEvictions(false)
      .withWarnDirectEvictions(false)
      .withWarnScalaVersionEviction(false),
  libraryDependencies ++= Seq(
    "io.monix" %% "monix-reactive" % monixVersion,
    "io.monix" %% "minitest" % "2.8.2" % Test
  ),

  testFrameworks := Seq(new TestFramework("minitest.runner.Framework")),

  updateOptions := updateOptions.value.withGigahorse(false),

  // -- Settings meant for deployment on oss.sonatype.org

  usePgpKeyHex("2673B174C4071B0E"),
  pgpPublicRing := baseDirectory.value / "project" / ".gnupg" / "pubring.gpg",
  pgpSecretRing := baseDirectory.value / "project" / ".gnupg" / "secring.gpg",
  pgpPassphrase := sys.env.get("PGP_PASS").map(_.toArray),

  publishMavenStyle := true,
  releaseCrossBuild := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,              // : ReleaseStep
    inquireVersions,                        // : ReleaseStep
    runClean,                               // : ReleaseStep
    runTest,                                // : ReleaseStep
    publishArtifacts,                       // : ReleaseStep, checks whether `publishTo` is properly set up
  ),
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,

  releaseVersion := { ver => Version(ver).map(_.string).getOrElse(versionFormatError(ver)) },
  releaseNextVersion := { ver => Version(ver).map(_.string).getOrElse(versionFormatError(ver)) },

  credentials += Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    sys.env.getOrElse("SONATYPE_USER", ""),
    sys.env.getOrElse("SONATYPE_PASS", "")
  ),

  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },

  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false }, // removes optional dependencies

  // For evicting Scoverage out of the generated POM
  // See: https://github.com/scoverage/sbt-scoverage/issues/153
  pomPostProcess := { (node: xml.Node) =>
    new RuleTransformer(new RewriteRule {
      override def transform(node: xml.Node): Seq[xml.Node] = node match {
        case e: Elem
          if e.label == "dependency" && e.child.exists(child => child.label == "groupId" && child.text == "org.scoverage") => Nil
        case _ => Seq(node)
      }
    }).transform(node).head
  },

  pomExtra :=
    <url>https://monix.io/</url>
    <licenses>
      <license>
        <name>Apache License, Version 2.0</name>
        <url>https://www.apache.org/licenses/LICENSE-2.0</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:monix/monix.git</url>
      <connection>scm:git:git@github.com:monix/monix.git</connection>
    </scm>
    <developers>
      <developer>
        <id>creyer</id>
        <name>Sorin Chiprian</name>
        <url>https://github.com/creyer</url>
      </developer>
      <developer>
        <id>alex_ndc</id>
        <name>Alexandru Nedelcu</name>
        <url>https://alexn.org</url>
      </developer>
      <developer>
        <id>radusw</id>
        <name>Radu Gancea</name>
        <url>https://github.com/radusw</url>
      </developer>
    </developers>
)

lazy val cmdlineProfile =
  sys.props.getOrElse("sbt.profile", default = "")

def profile: Project ⇒ Project = pr => cmdlineProfile match {
  case "coverage" => pr
  case _ => pr.disablePlugins(scoverage.ScoverageSbtPlugin)
}

val formattingSettings = Seq(
  scalariformAutoformat := true,
  ScalariformKeys.preferences := ScalariformKeys
    .preferences
    .value
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
)

val monixNio = Project(id = "monix-nio", base = file("."))
  .configure(profile)
  .settings(appSettings)
  .settings(formattingSettings)

val benchmark = Project(id = "monix-nio-benchmarks", base = file("benchmarks"))
  .configure(profile)
  .dependsOn(monixNio)
  .enablePlugins(JmhPlugin)
  .settings(formattingSettings)
  .settings(
    scalaVersion := "2.13.3",
    publishArtifact := false,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false,
    publishArtifact in (Compile, packageBin) := false
  )

