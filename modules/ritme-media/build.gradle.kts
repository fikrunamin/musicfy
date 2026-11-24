// Ritme's video detection for the in-app browser (spec D12, 4.7): URL heuristics, playlist sniffing,
// the cookie-less probe, the address bar rules and the page script. Plain JVM on purpose: the Android
// app uses it today and the desktop app (JCEF) will use the same code and the same page script.
plugins {
    id("kotlin")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":modules:ritme-protocol"))
    testImplementation(libs.junit)
}
