package com.authzmatrix.core

/** Filters out static assets so auto-mode doesn't flood the matrix with .js/.css/images/etc. */
object StaticAssets {

    private val STATIC = Regex(
        """\.(js|mjs|css|png|jpe?g|gif|svg|ico|webp|bmp|woff2?|ttf|eot|otf|pdf|zip|gz|map|mp4|webm|mp3)$""",
        RegexOption.IGNORE_CASE,
    )

    fun isStatic(url: String): Boolean {
        // Only the path decides — a query like ?file=report.pdf must NOT count as static.
        val path = url.substringBefore('#').substringBefore('?')
        return STATIC.containsMatchIn(path)
    }
}
