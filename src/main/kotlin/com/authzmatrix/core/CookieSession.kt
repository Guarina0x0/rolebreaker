package com.authzmatrix.core

import com.authzmatrix.model.Persona

/**
 * Parsing/merging primitives for the `Cookie` request header. Kept free of Montoya types so the
 * tricky parts (auth-cookie detection, session replacement) are unit-testable.
 */
object CookieHeader {

    /** Parse `Cookie: name=value; other=value` (or a bare cookie string) into an ordered map. */
    fun parse(raw: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isNullOrBlank()) return out
        val trimmed = raw.trim()
        val body = if (trimmed.startsWith("Cookie:", ignoreCase = true)) {
            trimmed.substring(trimmed.indexOf(':') + 1).trim()
        } else {
            trimmed
        }.replace("\r", "").replace("\n", " ")
        for (part in body.split(';')) {
            val pair = part.trim()
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            if (name.isEmpty()) continue
            out[name] = pair.substring(eq + 1).trim()
        }
        return out
    }

    /** Serialise a bundle in stable insertion order (suitable for a Cookie header). */
    fun serialize(cookies: Map<String, String>): String =
        cookies.entries.filter { it.key.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    /** Canonical form for deduplication: sorted by name, case-insensitively. */
    fun canonical(cookies: Map<String, String>): String =
        cookies.entries.filter { it.key.isNotBlank() }
            .sortedBy { it.key.lowercase() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    /** Extract the name/value pair from a single `Set-Cookie` header value. */
    fun parseSetCookie(value: String): Pair<String, String>? {
        val first = value.substringBefore(';').trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        if (name.isEmpty()) return null
        return name to first.substring(eq + 1).trim()
    }

    /** Merge all Set-Cookie headers from a response into one map (later headers win). */
    fun fromSetCookieHeaders(values: List<String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (value in values) parseSetCookie(value)?.let { (name, v) -> out[name] = v }
        return out
    }

    /**
     * Replace the original auth cookies with the persona's bundle, keeping unrelated cookies
     * (locale, theme, …) so the request still behaves normally.
     */
    fun replaceAuthCookies(existing: Map<String, String>, persona: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in existing) {
            if (!CookieSession.isAuthCookie(name, value)) out[name] = value
        }
        out.putAll(persona)
        return out
    }

    /** Remove auth cookies (heuristic + explicit names), keeping non-auth cookies. */
    fun stripAuthCookies(existing: Map<String, String>, extraNames: Set<String> = emptySet()): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in existing) {
            if (name in extraNames) continue
            if (CookieSession.isAuthCookie(name, value)) continue
            out[name] = value
        }
        return out
    }
}

/** Heuristics for recognising session/auth cookies (as opposed to locale, consent, etc.). */
object CookieSession {

    private val AUTH_NAME = Regex(
        "(?i)(sess|session|auth|token|jwt|sid|remember|login|csrf|xsrf|laravel|phpsess|jsession|connect[._-]?sid|_session|access[_-]?token)",
    )

    /** Stable identity cookies: excludes CSRF/XSRF, which rotate without changing the session. */
    private val IDENTITY_NAME = Regex(
        "(?i)(sess|session|auth|token|jwt|sid|remember|login|laravel|phpsess|jsession|connect[._-]?sid|_session|access[_-]?token)",
    )

    /** Auth material that must be removed when swapping identities (includes CSRF). */
    fun isAuthCookie(name: String, value: String): Boolean =
        AUTH_NAME.containsMatchIn(name) || Jwt.looksLikeJwt(value)

    /** Stable session-identifying cookie (excludes CSRF/XSRF). */
    fun isIdentityCookie(name: String, value: String): Boolean {
        if (name.contains("csrf", ignoreCase = true) || name.contains("xsrf", ignoreCase = true)) return false
        return IDENTITY_NAME.containsMatchIn(name) || Jwt.looksLikeJwt(value)
    }

    /** Only the cookies that identify a session; used for dedupe/matching. */
    fun identitySubset(cookies: Map<String, String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in cookies) if (isIdentityCookie(name, value)) out[name] = value
        return out
    }

    /** Best matching persona for the request's cookies (most matching auth cookies wins). */
    fun matchPersona(requestCookies: Map<String, String>, personas: List<Persona>): Persona? {
        val best = personas.asSequence()
            .filter { !it.anonymous && it.usesCookies }
            .mapNotNull { persona ->
                val cookies = synchronized(persona) { LinkedHashMap(persona.cookies) }
                val identity = identitySubset(cookies).ifEmpty { cookies }
                val matches = identity.count { (name, value) -> requestCookies[name] == value }
                if (matches > 0) persona to matches else null
            }
            .maxByOrNull { it.second }
        if (best != null) return best.first

        // Legacy single-cookie persona (cookieName + token, no bundle).
        return personas.firstOrNull { persona ->
            if (persona.anonymous || !persona.usesCookies) return@firstOrNull false
            val (cookies, token) = synchronized(persona) { LinkedHashMap(persona.cookies) to persona.token }
            cookies.isEmpty() && requestCookies[persona.cookieName] == token
        }
    }

    /** Human-readable note for the token catalog (values are truncated). */
    fun describe(cookies: Map<String, String>): String =
        if (cookies.isEmpty()) {
            ""
        } else {
            cookies.entries.joinToString(", ") { (name, value) ->
                val shown = if (value.length <= 6) value else "…" + value.takeLast(6)
                "$name=$shown"
            }
        }
}
