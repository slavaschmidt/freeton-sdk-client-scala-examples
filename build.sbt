name := "freeton-sdk-client-scala-examples"

version := "0.1"

scalaVersion := "2.12.12"

lazy val bindingVersion = "1.0.0-SNAPSHOT-2"

libraryDependencies ++= Seq(
  "org.freeton" %% "freeton-sdk-client-scala-binding" % bindingVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3"
)

lazy val libPath = (baseDirectory.value / "lib").getPath

fork in Runtime := true

envVars in Runtime := Map(
  "LD_LIBRARY_PATH" -> libPath,
  "PATH"            -> libPath
)

javaOptions in Runtime += s"-Djava.io.freetontmpdir=$libPath"
