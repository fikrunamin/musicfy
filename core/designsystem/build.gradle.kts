plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.example.musicfy.core.designsystem"
        compileSdk = 36
        minSdk = 26
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
        }
        androidMain.dependencies {
            // Compile the Android target against the exact Material3 the app ships (1.5.0-alpha18,
            // newer than CMP's 1.5.0-alpha08), so default-argument bridges match at runtime.
            implementation(libs.material3)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.junit)
        }
    }
}

composeCompiler {
    // Same rules as app/: no source information in release-like builds, same stability config.
    val buildingReleaseLike = gradle.startParameter.taskNames.any { name ->
        name.contains("Release", ignoreCase = true) || name.contains("Profileable", ignoreCase = true)
    }
    includeSourceInformation.set(!buildingReleaseLike)
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
}
