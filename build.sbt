ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.8"

lazy val root = (project in file("."))
  .settings(
    name := "ce-signals",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.3.9",
      "org.http4s" %% "http4s-blaze-server" % "0.23.11",
      "org.http4s" %% "http4s-dsl" % "0.23.11",
      "co.fs2" %% "fs2-core" % "3.2.7",
      "io.vertx" % "vertx-pg-client" % "4.2.5",
      "ch.qos.logback" % "logback-classic" % "1.2.10"
    )
  )
