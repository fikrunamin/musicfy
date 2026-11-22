package com.example.musicfy.ritme.server

import java.io.File

data class ServerConfig(
    val port: Int,
    /** Public origin used in share links, without trailing slash. */
    val publicUrl: String,
    /** Built web client; when it has no index.html the built-in landing page is served. */
    val webDir: File,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            port = env["PORT"]?.toIntOrNull() ?: 8080,
            publicUrl = (env["PUBLIC_URL"] ?: "http://localhost:8080").trimEnd('/'),
            webDir = File(env["WEB_DIR"] ?: "/app/web"),
        )
    }
}
