libraryDependencies += "net.databinder" %% "dispatch-http" % "0.8.10"

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

libraryDependencies += "org.eclipse.jgit" % "org.eclipse.jgit.pgm" % "3.7.1.201504261725-r"

unmanagedSourceDirectories in Compile += baseDirectory.value.getParentFile / "src" / "main" / "scala"
