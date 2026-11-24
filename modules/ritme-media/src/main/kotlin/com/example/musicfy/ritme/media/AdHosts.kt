package com.example.musicfy.ritme.media

/**
 * Known ad and ad-video hosts (spec 4.7 step 3, R12): their videos are never offered. A small static
 * list on purpose; ranking by the largest playing video does the rest.
 */
object AdHosts {
    private val DOMAINS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "imasdk.googleapis.com",
        "adservice.google.com", "2mdn.net", "moatads.com", "adnxs.com", "amazon-adsystem.com",
        "adsrvr.org", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "taboola.com", "outbrain.com", "teads.tv", "spotxchange.com", "spotx.tv", "springserve.com",
        "innovid.com", "smartadserver.com", "adform.net", "yieldmo.com", "vidoomy.com", "aniview.com",
        "adcolony.com", "applovin.com", "exoclick.com", "juicyads.com", "popads.net",
        "propellerads.com", "trafficjunky.net",
    )

    fun isAd(host: String): Boolean {
        val name = host.lowercase().removeSuffix(".")
        return DOMAINS.any { name == it || name.endsWith(".$it") }
    }
}
