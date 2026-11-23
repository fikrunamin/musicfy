package com.example.musicfy.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateSourceUrlsTest {
    private val urls = UpdateSourceUrls("o/r")

    @Test
    fun webUrls() {
        assertEquals("https://github.com/o/r", urls.repoUrl)
        assertEquals("https://github.com/o/r/releases", urls.releasesPageUrl)
    }

    @Test
    fun releasesApi() {
        assertEquals("https://api.github.com/repos/o/r/releases", urls.releasesApi())
        assertEquals("https://api.github.com/repos/o/r/releases?per_page=20", urls.releasesApi(perPage = 20))
    }

    @Test
    fun commitsApi() {
        assertEquals("https://api.github.com/repos/o/r/commits?per_page=50", urls.commitsApi(perPage = 50))
    }

    @Test
    fun releaseAssets() {
        assertEquals(
            "https://github.com/o/r/releases/download/v1.2.3/musicfy.apk",
            urls.releaseAssetUrl("v1.2.3", UpdateSourceUrls.APK_ASSET),
        )
        assertEquals("https://github.com/o/r/releases/download/v1.2.3/changelog.json", urls.changelogJsonUrl("v1.2.3"))
    }

    @Test
    fun nightlyWorkflow() {
        assertEquals(
            "https://api.github.com/repos/o/r/actions/workflows/nightly.yml/runs?status=success&per_page=1",
            urls.nightlyRunsApi(),
        )
        assertEquals(
            "https://nightly.link/o/r/workflows/nightly.yml/main/musicfy-gms-nightly.zip",
            urls.nightlyApkUrl(),
        )
    }

    @Test
    fun fromStoredValue() {
        assertEquals("https://github.com/fikrunamin/musicfy", UpdateSourceUrls.fromStored(null).repoUrl)
        assertEquals("https://github.com/a/b", UpdateSourceUrls.fromStored("github.com/a/b").repoUrl)
    }

    @Test
    fun newIssueUrlFollowsTheSource() {
        assertEquals("https://github.com/o/r/issues/new", UpdateSourceUrls("o/r").newIssueUrl)
        assertEquals(
            "https://github.com/fikrunamin/musicfy/issues/new",
            UpdateSourceUrls.fromStored(null).newIssueUrl,
        )
    }
}
