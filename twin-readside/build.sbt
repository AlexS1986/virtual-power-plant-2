
lazy val akkaVersion = "2.6.16"
lazy val akkaHttpVersion = "10.2.6" // akka HTTP

lazy val root = (project in file(".")).enablePlugins(SbtTwirl) // twirl

organization in ThisBuild := "com.lightbend"
name := "readside"

version := "1.0"

scalaVersion := "2.13.1"

  // Akka persistence jdbc
val SlickVersion = "3.3.3"
libraryDependencies ++= Seq(
   "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion, 
  "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.0.4",
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.slick" %% "slick" % SlickVersion,
  "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion,
  "org.postgresql" % "postgresql" % "42.2.18",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  //"org.postgresql" % "postgresql" % "42.2.23"
)

libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion

val AkkaProjectionVersion = "1.1.0"
val ScalikeJdbcVersion = "3.5.0"
lazy val akkaManagementVersion = "1.1.1" 

// Akka Projection JDBC for CQRS
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
  "org.scalikejdbc" %% "scalikejdbc" % ScalikeJdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-config" % ScalikeJdbcVersion,
  "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test, 
)

dockerExposedPorts := Seq(8080, 8558, 25520)
dockerUpdateLatest := true
dockerUsername := sys.props.get("docker.username")
dockerRepository := sys.props.get("docker.registry")
dockerBaseImage := "adoptopenjdk:11-jre-hotspot"
// make version compativle with docker for publishing
ThisBuild / dynverSeparator := "-"

scalacOptions := Seq("-feature", "-unchecked", "-deprecation", "-encoding", "utf8")
classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.AllLibraryJars
fork in run := true
Compile / run / fork := true

Compile / mainClass := Some("com.example.HttpServerWithActorInteraction") // tell compiler the main class

enablePlugins(JavaServerAppPackaging, DockerPlugin) // enable docker plugin for deployment in k8s



