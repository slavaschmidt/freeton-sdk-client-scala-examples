name := "freeton-sdk-client-scala-examples"

version := "0.1"

scalaVersion := "2.12.12"

lazy val bindingVersion = "1.0.0-M3"

libraryDependencies += "com.dancingcode" %% "freeton-sdk-client-scala-binding" % bindingVersion
libraryDependencies += "ch.qos.logback"  % "logback-classic"                   % "1.2.3"

fork in run := true

envVars in run := Map(
  "LD_LIBRARY_PATH" -> (baseDirectory.value / "lib").getPath,
  "PATH"            -> (baseDirectory.value / "lib").getPath
)

javaOptions in run += s"-Djava.io.freetontmpdir=${(baseDirectory.value / "lib").getPath}"
