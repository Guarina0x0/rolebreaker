package com.authzmatrix.core

/**
 * Produces a stable endpoint key for auto-sweep deduplication. `/users/1` and `/users/2` become
 * the same endpoint, while distinct paths (and distinct query *names*) stay separate.
 */
object EndpointKey {

    private val UUID = Regex(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )
    private val NUMERIC = Regex("\\d+")

    fun isId(value: String): Boolean =
        value.isNotEmpty() && (NUMERIC.matches(value) || UUID.matches(value))

    fun normalize(url: String): String {
        val noFragment = url.substringBefore('#')
        val queryAt = noFragment.indexOf('?')
        val base = if (queryAt >= 0) noFragment.substring(0, queryAt) else noFragment
        val query = if (queryAt >= 0) noFragment.substring(queryAt) else ""

        // Keep scheme + authority intact (a numeric port must not become {id}).
        val schemeAt = base.indexOf("://")
        val pathAt = if (schemeAt >= 0) {
            val slash = base.indexOf('/', schemeAt + 3)
            if (slash >= 0) slash else base.length
        } else {
            0
        }
        val prefix = base.substring(0, pathAt)
        val path = base.substring(pathAt)
        val normalizedPath = path.split("/").joinToString("/") { segment ->
            if (isId(segment)) "{id}" else segment
        }
        val normalizedQuery = query.split("&").joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                pair
            } else {
                val name = pair.substring(0, eq + 1)
                val value = pair.substring(eq + 1)
                name + if (isId(value)) "{id}" else value
            }
        }
        return prefix + normalizedPath + normalizedQuery
    }
}
