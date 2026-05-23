import org.gradle.api.tasks.PathSensitivity

plugins {
  id("com.android.library")
  kotlin("android")
}

val buildSingboxAar = tasks.register<Exec>("buildSingboxAar") {
  group = "build"
  description = "Builds singboxbridge.aar from Go sources via gomobile (runs only when sources change)"
  val goDir = layout.projectDirectory.dir("go")
  val aar = layout.projectDirectory.file("libs/singboxbridge.aar")
  inputs.dir(goDir)
    .withPropertyName("goSources")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  outputs.file(aar).withPropertyName("aar")
  workingDir = goDir.asFile
  commandLine("bash", "build.sh")
}

dependencies {
  implementation(
    fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar")))
      .builtBy(buildSingboxAar)
  )
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

android {
  namespace = "tgx.singbox"
  compileSdk = 35
  buildToolsVersion = "35.0.0"

  defaultConfig {
    minSdk = 21
  }

  testOptions {
    targetSdk = 35
  }

  lint {
    targetSdk = 35
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    isCoreLibraryDesugaringEnabled = true
  }
}
