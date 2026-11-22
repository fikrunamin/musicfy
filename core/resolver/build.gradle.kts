plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:platform"))
    api(project(":core:settings")) // AudioQuality lives in com.example.musicfy.constants
    api(project(":providers:innertube"))
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
