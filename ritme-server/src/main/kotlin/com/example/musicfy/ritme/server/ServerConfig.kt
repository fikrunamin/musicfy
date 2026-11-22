package com.example.musicfy.ritme.server

import java.io.File

data class ServerConfig(
    val port: Int,
    /** Public origin, without trailing slash. Reserved for share links; not used by the server yet. */
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
