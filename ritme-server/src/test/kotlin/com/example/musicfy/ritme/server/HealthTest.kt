package com.example.musicfy.ritme.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HealthTest {
    @Test
    fun healthzSaysOk() = testApplication {
        application { ritmeModule(testDeps()) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun configReadsEnvironmentWithDefaults() {
        assertEquals(ServerConfig(8080, "http://localhost:8080", File("/app/web")), ServerConfig.fromEnv(emptyMap()))
        assertEquals(
            ServerConfig(9000, "https://ritme.example.com", File("/srv/web")),
            ServerConfig.fromEnv(mapOf("PORT" to "9000", "PUBLIC_URL" to "https://ritme.example.com/", "WEB_DIR" to "/srv/web")),
        )
    }
}
