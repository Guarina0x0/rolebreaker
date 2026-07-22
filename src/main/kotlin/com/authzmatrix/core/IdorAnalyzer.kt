package com.authzmatrix.core

import burp.api.montoya.http.message.params.HttpParameter
import burp.api.montoya.http.message.params.HttpParameterType
import burp.api.montoya.http.message.requests.HttpRequest

enum class IdKind { QUERY, BODY, PATH }

/** A tamperable identifier found in a request (candidate for horizontal-access / IDOR testing). */
data class IdCandidate(
    val kind: IdKind,
    val name: String,
    val value: String,
    val pathIndex: Int = -1,
) {
    fun display(): String = when (kind) {
        IdKind.QUERY -> "query  ?$name=$value"
        IdKind.BODY -> "body   $name=$value"
        IdKind.PATH -> "path   [seg $pathIndex] = $value"
    }
}

/**
 * Finds identifier-looking values in a request and rewrites them, so the matrix can also cover
 * **horizontal** access (same role, someone else's resource) on top of the per-persona (vertical)
 * axis.
 */
object IdorAnalyzer {

    private val NUMERIC = Regex("^\\d+$")
    private val UUIDISH = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F-]{8,}$")

    fun idLike(v: String): Boolean = v.isNotEmpty() && (NUMERIC.matches(v) || UUIDISH.matches(v))

    fun detect(req: HttpRequest): List<IdCandidate> {
        val out = ArrayList<IdCandidate>()
        for (p in req.parameters()) {
            when (p.type()) {
                HttpParameterType.URL -> if (idLike(p.value())) out += IdCandidate(IdKind.QUERY, p.name(), p.value())
                HttpParameterType.BODY -> if (idLike(p.value())) out += IdCandidate(IdKind.BODY, p.name(), p.value())
                else -> {}
            }
        }
        val segs = req.pathWithoutQuery().split("/")
        segs.forEachIndexed { i, s -> if (idLike(s)) out += IdCandidate(IdKind.PATH, "seg$i", s, i) }
        return out
    }

    fun mutate(req: HttpRequest, cand: IdCandidate, newValue: String): HttpRequest = when (cand.kind) {
        IdKind.QUERY -> req.withUpdatedParameters(HttpParameter.urlParameter(cand.name, newValue))
        IdKind.BODY -> req.withUpdatedParameters(HttpParameter.bodyParameter(cand.name, newValue))
        IdKind.PATH -> {
            val full = req.path()
            val q = full.indexOf('?')
            val pathPart = if (q >= 0) full.substring(0, q) else full
            val query = if (q >= 0) full.substring(q) else ""
            val segs = pathPart.split("/").toMutableList()
            if (cand.pathIndex in segs.indices) segs[cand.pathIndex] = newValue
            req.withPath(segs.joinToString("/") + query)
        }
    }
}
