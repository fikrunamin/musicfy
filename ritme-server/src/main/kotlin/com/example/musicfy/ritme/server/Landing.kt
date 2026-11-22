package com.example.musicfy.ritme.server

/** Shown at / and /r/{code} until the web client (ritme-web) is deployed into WEB_DIR. */
object Landing {
    const val RELEASES_URL = "https://github.com/realidkroo/musicfy/releases/latest"
    private val codePattern = Regex("^[${Limits.ROOM_CODE_ALPHABET}]{${Limits.ROOM_CODE_LENGTH}}$")

    /** Only a code matching the room-code alphabet is ever written into the page. */
    fun page(rawCode: String?): String {
        val code = rawCode?.trim()?.uppercase()?.takeIf { codePattern.matches(it) }
        val heading = if (code != null) "Kamu diajak dengar bareng" else "Ritme"
        val body = if (code != null) {
            """
            <p class="code">Kode room: <b>$code</b></p>
            <a class="btn" href="musicfy://ritme/$code">Buka di aplikasi Musicfy</a>
            """
        } else {
            """<p>Minta kode atau link room dari temanmu, lalu buka di aplikasi Musicfy.</p>"""
        }
        return """
            <!doctype html>
            <html lang="id">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Ritme</title>
            <style>
              :root { color-scheme: light dark; --bg: #f6f6f4; --fg: #161616; --muted: #6b6b6b; --accent: #161616; --on-accent: #fff; }
              @media (prefers-color-scheme: dark) { :root { --bg: #111; --fg: #f2f2f2; --muted: #a0a0a0; --accent: #f2f2f2; --on-accent: #111; } }
              body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--bg); color: var(--fg);
                     font: 16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
              main { max-width: 420px; padding: 32px 16px; text-align: center; }
              h1 { font-size: 28px; margin: 0 0 12px; }
              .code b { letter-spacing: 0.15em; }
              .btn { display: inline-block; margin: 16px 0; padding: 12px 20px; border-radius: 999px; background: var(--accent);
                     color: var(--on-accent); text-decoration: none; font-weight: 600; }
              .muted { color: var(--muted); font-size: 14px; }
              .muted a { color: inherit; }
            </style>
            </head>
            <body>
            <main>
              <h1>$heading</h1>
              $body
              <p class="muted">Belum punya Musicfy? <a href="$RELEASES_URL">Unduh di GitHub</a>.</p>
            </main>
            </body>
            </html>
        """.trimIndent()
    }
}
