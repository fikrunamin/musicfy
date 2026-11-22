package com.example.musicfy.ritme.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LandingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun validCodeGetsADeepLink() {
        val page = Landing.page("k7m2qx")
        assertTrue(page.contains("musicfy://ritme/K7M2QX"))
        assertTrue(page.contains(Landing.RELEASES_URL))
    }

    @Test
    fun invalidCodeIsNeverEchoed() {
        val page = Landing.page("<script>alert(1)</script>")
        assertFalse(page.contains("<script>alert"))
        assertFalse(page.contains("musicfy://ritme/"))
    }

    @Test
    fun shareLinkServesTheLandingPageWhenNoWebBuildExists() = testApplication {
        application { ritmeModule(testDeps(webDir = tmp.newFolder("empty"))) }
        val response = client.get("/r/K7M2QX")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("musicfy://ritme/K7M2QX"))
        assertTrue(client.get("/").bodyAsText().contains("Ritme"))
    }

    @Test
    fun webBuildIndexWinsWhenPresent() = testApplication {
        val web = tmp.newFolder("web").apply { resolve("index.html").writeText("<html>ritme-web</html>") }
        application { ritmeModule(testDeps(webDir = web)) }
        assertEquals("<html>ritme-web</html>", client.get("/r/K7M2QX").bodyAsText())
        assertEquals("<html>ritme-web</html>", client.get("/").bodyAsText())
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }
}
