/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.vlsi.jandex.JandexProcessResources
import java.io.File
import java.lang.IllegalStateException
import java.util.Properties
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.module
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.withType

/**
 * Configures the `JavaPluginExtension` to use Java 11, or a newer Java version explicitly
 * configured for testing.
 */
fun Project.preferJava11() {
  extensions.findByType<JavaPluginExtension>()!!.run {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

/**
 * Apply the given `sparkVersion` as a `strictly` version constraint and [withSparkExcludes] on the
 * current [Dependency].
 */
fun ModuleDependency.forSpark(sparkVersion: String): ModuleDependency {
  val dep = this as ExternalModuleDependency
  dep.version { strictly(sparkVersion) }
  return this.withSparkExcludes()
}

/** Apply a bunch of common dependency-exclusion to the current Spark [Dependency]. */
fun ModuleDependency.withSparkExcludes(): ModuleDependency {
  return this.exclude("commons-logging", "commons-logging")
    .exclude("log4j", "log4j")
    .exclude("org.slf4j", "slf4j-log4j12")
    .exclude("org.slf4j", "slf4j-reload4j")
    .exclude("org.eclipse.jetty", "jetty-util")
    .exclude("org.apache.avro", "avro")
    .exclude("org.apache.arrow", "arrow-vector")
}

fun DependencyHandlerScope.forScala(scalaVersion: String) {
  // Note: Quarkus contains Scala dependencies since 2.9.0
  add("implementation", "org.scala-lang:scala-library:$scalaVersion!!")
  add("implementation", "org.scala-lang:scala-reflect:$scalaVersion!!")
}

/**
 * Forces all [Test] tasks to use Java 11 for test execution, which is mandatory for tests using
 * Spark.
 */
fun Project.forceJava11ForTests() {
  if (!JavaVersion.current().isJava11) {
    tasks.withType(Test::class.java).configureEach {
      val javaToolchains = project.extensions.findByType(JavaToolchainService::class.java)
      javaLauncher.set(
        javaToolchains!!.launcherFor { languageVersion.set(JavaLanguageVersion.of(11)) }
      )
    }
  }
}

fun Project.dependencyVersion(key: String) = rootProject.extra[key].toString()

fun Project.testLogLevel() = System.getProperty("test.log.level", "WARN")

/** Check whether the current build is run in the context of integrations-testing. */
fun isIntegrationsTestingEnabled() =
  System.getProperty("nessie.integrationsTesting.enable").toBoolean()

/**
 * Adds an `implementation` dependency to `nessie-client` using the Nessie version supported by the
 * latest released Iceberg version (`versionClientNessie`), if the system property
 * `nessieIntegrationsTesting` is set to `true`.
 */
fun Project.nessieClientForIceberg(): Dependency {
  val dependencyHandlerScope = DependencyHandlerScope.of(dependencies)
  if (!isIntegrationsTestingEnabled()) {
    return dependencies.create(
      "org.projectnessie:nessie-client:${dependencyVersion("versionClientNessie")}"
    )
  } else {
    return dependencyHandlerScope.nessieProject("nessie-client")
  }
}

/**
 * Resolves the Nessie Quarkus server for integration tests that depend on it.
 *
 * This is necessary for tools-integrations-testing, because all Nessie projects that depend on
 * Apache Iceberg are handled in a separate build. See `README.md` in the `iceberg/` directory.
 */
fun DependencyHandlerScope.nessieQuarkusServerRunner(): ModuleDependency {
  return nessieProject("nessie-quarkus", "quarkusRunner")
}

/**
 * Resolves a Nessie project in the "right" Gradle build.
 *
 * This is necessary for tools-integrations-testing, because all Nessie projects that depend on
 * Apache Iceberg are handled in a separate build. See `README.md` in the `iceberg/` directory.
 */
fun DependencyHandlerScope.nessieProject(
  artifactId: String,
  configuration: String? = null
): ModuleDependency {
  if (!isIntegrationsTestingEnabled()) {
    return project(":$artifactId", configuration)
  } else {
    return module("org.projectnessie", artifactId, configuration = configuration)
  }
}

/** Utility method to check whether a Quarkus build shall produce the uber-jar. */
fun Project.withUberJar(): Boolean = hasProperty("uber-jar") || isIntegrationsTestingEnabled()

fun Project.applyShadowJar() {
  plugins.apply(ShadowPlugin::class.java)

  plugins.withType<ShadowPlugin>().configureEach {
    val shadowJar =
      tasks.named<ShadowJar>("shadowJar") {
        outputs.cacheIf { false } // do not cache uber/shaded jars
        archiveClassifier.set("")
        mergeServiceFiles()
      }

    tasks.named<Jar>("jar") {
      dependsOn(shadowJar)
      archiveClassifier.set("raw")
    }
  }
}

/** Just load [Properties] from a [File]. */
fun loadProperties(file: File): Properties {
  val props = Properties()
  file.reader().use { reader -> props.load(reader) }
  return props
}

/** Hack for Jandex-Plugin (removed later). */
fun Project.useBuildSubDirectory(buildSubDir: String) {
  buildDir = file("$buildDir/$buildSubDir")

  // TODO open an issue for the Jandex plugin - it configures the task's output directory too
  //  early, so re-assigning the output directory (project.buildDir=...) to a different path
  //  isn't reflected in the Jandex output.
  tasks.withType<JandexProcessResources>().configureEach {
    val sourceSets: SourceSetContainer by project
    sourceSets.all { destinationDir = this.output.resourcesDir!! }
  }
}

/** Adds relocation-information to a Maven-POM. */
fun Project.addRelocateTo(toArtifact: String) {
  val project = this
  configure<PublishingExtension> {
    publications.named("maven") {
      this as MavenPublication
      pom {
        distributionManagement {
          relocation {
            artifactId.set(toArtifact)
            groupId.set(project.group.toString())
            version.set(project.version.toString())
            message.set(
              "The artifact ${project.name} will be removed in a future version of Nessie, " +
                "please ensure tha all references to ${project.name} are updated to ${toArtifact}."
            )
          }
        }
      }
    }
  }
}

/** Resolves the Spark and Scala major versions for all `nessie-spark-extensions*` projects. */
fun Project.getSparkScalaVersionsForProject(): SparkScalaVersions {
  val sparkScala = project.name.split("-").last().split("_")

  val sparkMajorVersion = if (sparkScala[0][0].isDigit()) sparkScala[0] else "3.2"
  val scalaMajorVersion = sparkScala[1]

  useBuildSubDirectory(scalaMajorVersion)

  return useSparkScalaVersionsForProject(sparkMajorVersion, scalaMajorVersion)
}

fun Project.scalaDependencyVersion(scalaMajorVersion: String): String {
  val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
  val scalaDepName = "scala-library-v${scalaMajorVersion.replace("[.]".toRegex(), "")}"
  val scalaDep =
    versionCatalog.findLibrary(scalaDepName).orElseThrow {
      IllegalStateException("No library '$scalaDepName' defined in version catalog 'libs'")
    }
  return scalaDep.get().versionConstraint.preferredVersion
}

fun Project.sparkDependencyVersion(sparkMajorVersion: String, scalaMajorVersion: String): String {
  val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
  val sparkDepName =
    "spark-sql-v${sparkMajorVersion.replace("[.]".toRegex(), "")}-v${scalaMajorVersion.replace("[.]".toRegex(), "")}"
  val sparkDep =
    versionCatalog.findLibrary(sparkDepName).orElseThrow {
      IllegalStateException("No library '$sparkDepName' defined in version catalog 'libs'")
    }
  return sparkDep.get().versionConstraint.preferredVersion
}

fun Project.useSparkScalaVersionsForProject(sparkMajorVersion: String): SparkScalaVersions {
  val scalaMajorVersion =
    rootProject.extra["sparkVersion-${sparkMajorVersion}-scalaVersions"]
      .toString()
      .split(",")
      .map { it.trim() }[0]
  return useSparkScalaVersionsForProject(sparkMajorVersion, scalaMajorVersion)
}

fun Project.useSparkScalaVersionsForProject(
  sparkMajorVersion: String,
  scalaMajorVersion: String
): SparkScalaVersions {
  return SparkScalaVersions(
    sparkMajorVersion,
    scalaMajorVersion,
    sparkDependencyVersion(sparkMajorVersion, scalaMajorVersion),
    scalaDependencyVersion(scalaMajorVersion)
  )
}

class SparkScalaVersions(
  val sparkMajorVersion: String,
  val scalaMajorVersion: String,
  val sparkVersion: String,
  val scalaVersion: String
) {}
