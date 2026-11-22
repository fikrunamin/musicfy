// Standalone build so the Docker image needs only a JDK (no Android SDK). Shares the repo's
// version catalog and compiles ../modules/ritme-protocol as :ritme-protocol.
rootProject.name = "ritme-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(":ritme-protocol")
project(":ritme-protocol").projectDir = file("../modules/ritme-protocol")
