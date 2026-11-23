// UpdateSource.kt
//
// The "Update source" setting (UpdateSourceKey): the GitHub repo whose releases the app updates
// from, and where its changelog and commit list come from. Plain JVM so the desktop app can use
// the same key and parser later.

package com.example.musicfy.core.settings

/** Parses and stores the update source; [UpdateSourceUrls] builds the URLs from its owner/repo. */
object UpdateSource {
    const val DEFAULT_REPO = "fikrunamin/musicfy"
    const val DEFAULT_URL = "https://github.com/$DEFAULT_REPO"

    // GitHub usernames and organisations: letters, digits and hyphens, at most 39, not starting
    // or ending with a hyphen. Repo names: letters, digits, '.', '_' and '-', at most 100.
    private const val OWNER = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"
    private const val REPO = "[A-Za-z0-9._-]{1,100}?"
    private val repoPattern = Regex(
        "^(?:(?:https://)?github\\.com/)?($OWNER)/($REPO)(?:\\.git)?/?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `owner/repo` from `https://github.com/owner/repo`, `github.com/owner/repo` or `owner/repo`,
     * each with an optional trailing `/` or `.git`; null for anything else.
     */
    fun parseRepo(input: String): String? {
        val match = repoPattern.matchEntire(input.trim()) ?: return null
        val (owner, repo) = match.destructured
        if (repo == "." || repo == ".." || repo.endsWith(".git", ignoreCase = true)) return null
        return "$owner/$repo"
    }

    fun repoUrl(repo: String): String = "https://github.com/$repo"

    /** What to save for typed [input]: "" for the default repo, its canonical URL otherwise, null when invalid. */
    fun toStore(input: String): String? {
        val repo = parseRepo(input) ?: return null
        return if (repo.equals(DEFAULT_REPO, ignoreCase = true)) "" else repoUrl(repo)
    }

    /** `owner/repo` for a stored value; blank or unreadable means the default. */
    fun resolve(stored: String?): String = stored?.let(::parseRepo) ?: DEFAULT_REPO
}

/** Every GitHub URL the updater, the changelog and the commit list use, for one `owner/repo`. */
class UpdateSourceUrls(val repo: String) {
    val repoUrl: String get() = UpdateSource.repoUrl(repo)
    val releasesPageUrl: String get() = "$repoUrl/releases"

    /** The new-issue page, where bug reports go (the configured source, the fork by default). */
    val newIssueUrl: String get() = "$repoUrl/issues/new"

    private val api: String get() = "https://api.github.com/repos/$repo"

    fun releasesApi(perPage: Int? = null): String =
        "$api/releases" + (perPage?.let { "?per_page=$it" } ?: "")

    /** Newest commits of the default branch. */
    fun commitsApi(perPage: Int): String = "$api/commits?per_page=$perPage"

    fun releaseAssetUrl(tag: String, asset: String): String = "$repoUrl/releases/download/$tag/$asset"

    fun changelogJsonUrl(tag: String): String = releaseAssetUrl(tag, CHANGELOG_ASSET)

    /** The newest successful run of the nightly workflow; 404 when the repo has no such workflow. */
    fun nightlyRunsApi(): String =
        "$api/actions/workflows/$NIGHTLY_WORKFLOW/runs?status=success&per_page=1"

    fun nightlyApkUrl(): String =
        "https://nightly.link/$repo/workflows/$NIGHTLY_WORKFLOW/main/$NIGHTLY_ARTIFACT"

    companion object {
        /** The APK each release carries (.github/workflows/release.yml uploads it under this name). */
        const val APK_ASSET = "musicfy.apk"
        const val CHANGELOG_ASSET = "changelog.json"
        const val NIGHTLY_WORKFLOW = "nightly.yml"
        const val NIGHTLY_ARTIFACT = "musicfy-gms-nightly.zip"

        fun fromStored(stored: String?): UpdateSourceUrls = UpdateSourceUrls(UpdateSource.resolve(stored))
    }
}
