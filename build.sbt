name := """play-google"""

version := "1.2.0"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.2"

libraryDependencies ++= Seq(
  guice,
  ehcache,
  ws,
  filters
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
