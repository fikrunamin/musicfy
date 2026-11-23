plugins {
    id("kotlin")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:platform"))
    implementation(project(":core:settings"))
    implementation(project(":core:database"))
    implementation(project(":core:resolver"))
    implementation(project(":core:designsystem"))
    implementation(project(":providers:innertube"))
    implementation(compose.desktop.currentOs)
    implementation(libs.sqlite.bundled)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.example.musicfy.desktop.MainKt"
    }
}
