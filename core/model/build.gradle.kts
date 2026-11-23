plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Same as app/: Room reads @ColumnInfo etc. from both the parameter and the property.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    api(project(":providers:innertube"))
    api(libs.room.common)
    api(libs.compose.runtime.annotation)
    implementation(libs.kotlinx.coroutines.core)
    // The entities' toggleLike() etc. call YouTube functions that return ktor's HttpResponse.
    implementation(libs.ktor.client.core)
    implementation(libs.apache.lang3)
    testImplementation(libs.junit)
}

tasks.test {
    // ./gradlew :core:model:test -PupdateGolden=true rewrites golden files
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
}
