plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:platform"))
    implementation(libs.vlcj)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver3)
}

// desktopApp/resources/<os-arch>/vlc, filled by scripts/desktop/fetch-libvlc.sh (Linux: system libvlc)
val osArch: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        os.startsWith("mac") -> "macos-$arch"
        os.startsWith("windows") -> "windows-$arch"
        else -> "linux-$arch"
    }
}
val bundledVlcDir = rootProject.layout.projectDirectory.dir("desktopApp/resources/$osArch/vlc").asFile
val libVlcCategory = "com.example.musicfy.desktop.player.testing.NeedsLibVlc"
val testSourceSet = the<SourceSetContainer>()["test"]

tasks.test {
    useJUnit { excludeCategories(libVlcCategory) }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Tests that load libVLC. Run scripts/desktop/fetch-libvlc.sh first (Linux: apt install libvlc-dev vlc-plugin-base)."
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnit { includeCategories(libVlcCategory) }
    systemProperty("musicfy.vlc.dir", bundledVlcDir.absolutePath)
    systemProperty("musicfy.vlc.audible", providers.gradleProperty("audible").getOrElse("false"))
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.test)
    // A go/no-go gate must really load libVLC: never up-to-date, never restored from the build cache
    // (the bundled libVLC folder is not a tracked input).
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
