plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.datastore.preferences.core)
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :core:settings:test -PupdateGolden=true rewrites the key-name golden file
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
