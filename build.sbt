name := "freeton-sdk-client-scala-examples"

version := "0.1"

scalaVersion := "2.12.12"

lazy val bindingVersion = "1.0.0-SNAPSHOT-1"

libraryDependencies ++= Seq(
  "org.freeton" %% "freeton-sdk-client-scala-binding" % bindingVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)
