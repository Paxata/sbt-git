sbtPlugin := true

name := "sbt-git"
organization := "com.typesafe.sbt"


lazy val `sbt-git` = project in file(".") enablePlugins (GitVersioning, GitBranchPrompt)

git.baseVersion := "0.8"


libraryDependencies += "org.eclipse.jgit" % "org.eclipse.jgit.pgm" % "3.7.0.201502260915-r"

publishMavenStyle := false


scriptedSettings
scriptedLaunchOpts += s"-Dproject.version=${version.value}"
