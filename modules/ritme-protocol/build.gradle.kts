// Ritme wire protocol. Plain Kotlin on purpose: the Android app, the Ktor server (ritme-server/,
// a separate Gradle build that includes this directory) and, via golden JSON fixtures, the web
// client all agree on these shapes.
plugins {
    alias(libs.plugins.kotlin.serialization)
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :modules:ritme-protocol:test -PupdateGolden=true rewrites fixtures/*.json
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
