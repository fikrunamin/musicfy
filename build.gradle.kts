plugins {
    alias(libs.plugins.hilt) apply (false)
    alias(libs.plugins.kotlin.ksp) apply (false)
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.protobufPlugin) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
    dependencies {
        classpath(libs.gradle)
        classpath(kotlin("gradle-plugin", libs.versions.kotlin.get()))
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add("plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics")
                }
            }
        }
    }
}

// Unit tests of every shared module (core + providers + modules). The app's own unit tests run
// per variant via :app:test<Variant>UnitTest. core:designsystem is KMP: its JVM tests are desktopTest.
tasks.register("jvmTests") {
    group = "verification"
    description = "Runs unit tests for all :core, :providers and :modules modules, and :desktopApp."
    val kmpModules = setOf(":core:designsystem")
    dependsOn(
        subprojects
            .filter { it.path.startsWith(":core:") || it.path.startsWith(":providers:") || it.path.startsWith(":modules:") }
            .map { if (it.path in kmpModules) "${it.path}:desktopTest" else "${it.path}:test" },
    )
    dependsOn(":desktopApp:test")
}
