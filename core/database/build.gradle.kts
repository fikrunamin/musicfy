plugins {
    id("kotlin")
    alias(libs.plugins.kotlin.ksp)
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:model"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.json)
}

tasks.test {
    // SchemaParityTest reads both exported schemas; declare them so the test reruns when either changes.
    inputs.dir(layout.projectDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("../../app/schemas/com.example.musicfy.db.InternalDatabase/39.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
