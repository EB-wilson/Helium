
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

val mindustryVersion = properties["mindustryVersion"]
val arcVersion = properties["arcVersion"]
val unkVersion = properties["unkVersion"]

val modOutputDir = properties["modOutputDir"] as? String
val debugJarDir = properties["debugGamePath"] as? String

val sdkRoot: String? = System.getenv("ANDROID_HOME")

val buildDir = layout.buildDirectory.get()

plugins {
  java
  kotlin("jvm") version "2.1.20"
  `maven-publish`
}

group = "com.github.EB-wilson"

version = properties["version"] as String

run { "java SyncBundles.java $version".execute() }

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  jvmToolchain(21)

  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])

      groupId = "com.github.EB-wilson"
      artifactId = "Helium"
      version = "${project.version}"
    }
  }
}

repositories {
  mavenLocal()
  mavenCentral()
  maven ("https://maven.xpdustry.com/mindustry")
  maven { url = uri("https://raw.githubusercontent.com/Zelaux/MindustryRepo/master/repository") }
  maven { url = uri("https://www.jitpack.io") }
}

dependencies {
  compileOnly("com.github.Anuken.Arc:arc-core:$arcVersion")
  compileOnly("com.github.Anuken.Mindustry:core:$mindustryVersion")

  implementation("com.github.EB-wilson.UniverseKit:utilities:$unkVersion")
  implementation("com.github.EB-wilson.UniverseKit:graphic:$unkVersion")
  implementation("com.github.EB-wilson.UniverseKit:reflection:$unkVersion")
  implementation("com.github.EB-wilson.UniverseKit:markdown:$unkVersion")

  implementation(kotlin("stdlib-jdk8"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

  testImplementation("com.github.Anuken.Arc:arc-core:${arcVersion}")
  testImplementation("com.github.Anuken.Mindustry:core:${mindustryVersion}")
}

tasks {
  jar {
    dependsOn(":ModpackModel:deploy")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName = "${project.name}-desktop.jar"

    from(rootDir) {
      include("mod.hjson")
      include("icon.png")
      include("contributors.hjson")
    }

    from("assets/") {
      include("**")
      exclude("git")
    }

    from(project(":ModpackModel").layout.buildDirectory.dir("libs")) {
      include("ModpackModel.jar")
      into("model")
    }

    from(configurations.getByName("runtimeClasspath").map { if (it.isDirectory) it else zipTree(it) })
  }

  register("jarAndroid") {
    dependsOn("jar")

    doLast {
      try {
        if (sdkRoot == null) throw GradleException("No valid Android SDK found. Ensure that ANDROID_HOME is set to your Android SDK directory.");

        val platformRoot = File("$sdkRoot/platforms/").listFiles()
          ?.sorted()
          ?.reversed()
          ?.find { f -> File (f, "android.jar").exists() }
          ?.let { f -> File (f, "android.jar") }

        val d8 = File("$sdkRoot/build-tools/").listFiles()
          ?.sorted()
          ?.reversed()
          ?.find { f -> f.listFiles()?.any{ s -> s.name.contains("d8") }?:false }
          ?.let { f -> f.listFiles()?.find{ s -> s.name.contains("d8") } }

        if (platformRoot == null)
          throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")
        if (d8 == null)
          throw GradleException("No d8 found. Ensure that you have an Android build-tool installed.")

        //collect dependencies needed for desugaring
        val dependencies = (
            configurations.compileClasspath.get().files +
            configurations.runtimeClasspath.get().files +
            setOf(platformRoot)
        ).joinToString(" ") { "--classpath $it" }

        //dex and desugar files - this requires d8 in your PATH
        "${d8.absolutePath} $dependencies --min-api 30 --output ${project.name}-android.jar ${project.name}-desktop.jar"
          .execute(File("$buildDir/libs"))
      }
      catch (e: Throwable) {
        if (e is Error){
          println(e.message)
          return@doLast
        }

        println(e.message)
        println("[WARNING] d8 tool or platform tools was not found, if you was installed android SDK, please check your environment variable")

        delete(
          files("${buildDir}/libs/${project.name}-android.jar")
        )

        val out = JarOutputStream(FileOutputStream("${buildDir}/libs/${project.name}-android.jar"))
        out.putNextEntry(JarEntry("non-androidMod.txt"))
        val reader = StringReader(
          "this mod is don't have classes.dex for android, please consider recompile with a SDK or run this mod on desktop only"
        )

        var r = reader.read()
        while (r != -1) {
          out.write(r)
          out.flush()
          r = reader.read()
        }
        out.close()
      }
    }
  }

  register("deploy", Jar::class) {
    dependsOn("jarAndroid")
    archiveFileName = "${project.name}.jar"

    from (
      zipTree("${buildDir}/libs/${project.name}-desktop.jar"),
      zipTree("${buildDir}/libs/${project.name}-android.jar")
    )

    doLast {
      if (!modOutputDir.isNullOrEmpty()) {
        copy {
          into("$modOutputDir/")
          from("${buildDir}/libs/${project.name}.jar")
        }
      }
    }
  }

  register("deployDesktop", Jar::class) {
    dependsOn("jar")
    archiveFileName = "${project.name}.jar"

    from (zipTree("${buildDir}/libs/${project.name}-desktop.jar"))

    doLast {
      if (!modOutputDir.isNullOrEmpty()) {
        copy {
          into("$modOutputDir/")
          from("${buildDir}/libs/${project.name}.jar")
        }
      }
    }
  }

  register("debugMod", JavaExec::class) {
    dependsOn("classes")
    dependsOn("deployDesktop")

    mainClass = "-jar"
    args = listOf(
      debugJarDir?: "",
      "-debug",
      "-gl",
      "3.0"
    )
  }
}

fun String.execute(path: File? = null, vararg args: Any?): Process {
  val cmd = split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    .apply { addAll(args.map { it?.toString() ?: "null" }) }

  val process = ProcessBuilder(cmd)
    .directory(path ?: rootDir)
    .redirectErrorStream(true)
    .start()

  val output = StringBuilder()
  process.inputStream.bufferedReader().forEachLine {
    output.appendLine(it)
    logger.lifecycle("[${cmd.first()}] $it")
  }

  val code = process.waitFor()
  if (code != 0) throw Error("exit=$code\n$output")
  return process
}

class Error(str: String): RuntimeException(str)
