package com.example.musicfy.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateSourceTest {

    @Test
    fun acceptsTheThreeSpellingsOfARepo() {
        assertEquals("fikrunamin/musicfy", UpdateSource.parseRepo("https://github.com/fikrunamin/musicfy"))
        assertEquals("fikrunamin/musicfy", UpdateSource.parseRepo("github.com/fikrunamin/musicfy"))
        assertEquals("fikrunamin/musicfy", UpdateSource.parseRepo("fikrunamin/musicfy"))
    }

    @Test
    fun acceptsATrailingSlashOrDotGit() {
        assertEquals("o/r", UpdateSource.parseRepo("https://github.com/o/r/"))
        assertEquals("o/r", UpdateSource.parseRepo("https://github.com/o/r.git"))
        assertEquals("o/r", UpdateSource.parseRepo("github.com/o/r.git"))
        assertEquals("o/r", UpdateSource.parseRepo("o/r/"))
        assertEquals("o/r", UpdateSource.parseRepo("o/r.git"))
    }

    @Test
    fun trimsSurroundingWhitespaceAndIgnoresSchemeAndHostCase() {
        assertEquals("o/r", UpdateSource.parseRepo("  https://github.com/o/r \n"))
        assertEquals("o/r", UpdateSource.parseRepo("HTTPS://GitHub.com/o/r"))
    }

    @Test
    fun keepsTheCaseAndTheAllowedCharactersOfOwnerAndRepo() {
        assertEquals("Musicfy-App/My.Repo_2-x", UpdateSource.parseRepo("https://github.com/Musicfy-App/My.Repo_2-x"))
        assertEquals("a1/.dotfiles", UpdateSource.parseRepo("a1/.dotfiles"))
        assertEquals("o/r.github.io", UpdateSource.parseRepo("o/r.github.io.git"))
    }

    @Test
    fun ownerFollowsGithubUsernameRules() {
        assertNull(UpdateSource.parseRepo("-owner/repo"))
        assertNull(UpdateSource.parseRepo("owner-/repo"))
        assertNull(UpdateSource.parseRepo("own_er/repo"))
        assertNull(UpdateSource.parseRepo("own.er/repo"))
        assertEquals("a/repo", UpdateSource.parseRepo("a/repo"))
        val max = "a".repeat(39)
        assertEquals("$max/r", UpdateSource.parseRepo("$max/r"))
        assertNull(UpdateSource.parseRepo("${max}a/r"))
    }

    @Test
    fun repoFollowsGithubRepoNameRules() {
        assertNull(UpdateSource.parseRepo("o/re po"))
        assertNull(UpdateSource.parseRepo("o/re%po"))
        assertNull(UpdateSource.parseRepo("o/."))
        assertNull(UpdateSource.parseRepo("o/.."))
        val max = "r".repeat(100)
        assertEquals("o/$max", UpdateSource.parseRepo("o/$max"))
        assertNull(UpdateSource.parseRepo("o/${max}r"))
    }

    @Test
    fun rejectsAnythingElse() {
        listOf(
            "",
            "   ",
            "musicfy",
            "o/",
            "/r",
            "o/r/extra",
            "https://github.com/o/r/releases",
            "https://github.com/o",
            "http://github.com/o/r",
            "https://www.github.com/o/r",
            "https://gitlab.com/o/r",
            "https://github.com.evil.com/o/r",
            "ssh://git@github.com/o/r.git",
            "git@github.com:o/r.git",
            "https://github.com/o/r?tab=readme",
            "https://github.com/o/r#readme",
            "o/r//",
            "o/r.git.git",
        ).forEach { assertNull("'$it' should be rejected", UpdateSource.parseRepo(it)) }
    }

    @Test
    fun toStoreKeepsBlankForTheDefaultAndTheCanonicalUrlOtherwise() {
        assertEquals("", UpdateSource.toStore("https://github.com/fikrunamin/musicfy"))
        assertEquals("", UpdateSource.toStore("FikrunAmin/Musicfy.git"))
        assertEquals("https://github.com/o/r", UpdateSource.toStore("github.com/o/r/"))
        assertNull(UpdateSource.toStore("not a repo"))
    }

    @Test
    fun resolveFallsBackToTheDefaultForBlankOrBrokenValues() {
        assertEquals(UpdateSource.DEFAULT_REPO, UpdateSource.resolve(null))
        assertEquals(UpdateSource.DEFAULT_REPO, UpdateSource.resolve(""))
        assertEquals(UpdateSource.DEFAULT_REPO, UpdateSource.resolve("garbage"))
        assertEquals("o/r", UpdateSource.resolve("https://github.com/o/r"))
    }

    @Test
    fun defaultIsTheFork() {
        assertEquals("fikrunamin/musicfy", UpdateSource.DEFAULT_REPO)
        assertEquals("https://github.com/fikrunamin/musicfy", UpdateSource.DEFAULT_URL)
        assertEquals(UpdateSource.DEFAULT_URL, UpdateSource.repoUrl(UpdateSource.DEFAULT_REPO))
    }
}
