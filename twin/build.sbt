organization in ThisBuild := "com.lightbend"
name := "twin"//"akka-quickstart-scala-iot"

version := "1.0"

scalaVersion := "2.13.1"

lazy val akkaVersion = "2.6.16"
lazy val akkaHttpVersion = "10.2.6" // akka HTTP




libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion, // basic actor stuff
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion, // akka http
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
)

libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion

lazy val akkaManagementVersion = "1.1.1" // akka cluster update to 1.1.1 as shown in https://doc.akka.io/docs/akka-management/current/kubernetes-deployment/forming-a-cluster.html
libraryDependencies ++= {
  Seq(
    "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % akkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
    "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagementVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test)
}

libraryDependencies ++= Seq( // Akka persistence event-sourcing https://doc.akka.io/docs/akka/current/typed/persistence.html
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test
)

// Akka persistence jdbc
val SlickVersion = "3.3.3"
libraryDependencies ++= Seq(
  "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.0.4",
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.typesafe.slick" %% "slick" % SlickVersion,
  "com.typesafe.slick" %% "slick-hikaricp" % SlickVersion,
  "org.postgresql" % "postgresql" % "42.2.18"
  //"org.postgresql" % "postgresql" % "42.2.23"
)

val AkkaProjectionVersion = "1.1.0"
val ScalikeJdbcVersion = "3.5.0"
// Akka Projection JDBC for CQRS
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-jdbc" % AkkaProjectionVersion,
  "org.scalikejdbc" %% "scalikejdbc" % ScalikeJdbcVersion,
  "org.scalikejdbc" %% "scalikejdbc-config" % ScalikeJdbcVersion,
  "com.lightbend.akka" %% "akka-projection-testkit" % AkkaProjectionVersion % Test
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

Compile / mainClass := Some("twin.TwinApp") // tell compiler the main class

enablePlugins(JavaServerAppPackaging, DockerPlugin) // enable docker plugin for deployment in k8s 
// https://doc.akka.io/docs/akka-management/current/kubernetes-deployment/building-using-sbt.html
