plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.datastore.preferences.core)
    api(project(":providers:innertube"))
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :core:settings:test -PupdateGolden=true rewrites the key-name golden file
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
