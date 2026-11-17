package com.authzmatrix.core

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse

/**
 * Harvests JWTs and cookie sessions from traffic (headers, cookies, bodies) so the user never
 * pastes credentials by hand. Used both by the live proxy handler and the on-demand history
 * scanner.
 */
class TokenCapture(private val store: PersonaStore) {

    @Volatile
    var enabled: Boolean = true

    /** Returns the number of new identities (JWT or cookie session) discovered. */
    fun inspectRequest(req: HttpRequest, onlyActive: Boolean = false): Int {
        if (!enabled) return 0
        val where = "req ${req.method()} ${req.path()}"
        var n = harvest(req.toString(), where, onlyActive)
        n += captureCookies(CookieHeader.parse(req.headerValue("Cookie")), where, onlyActive)
        return n
    }

    /** Returns the number of new identities (JWT or cookie session) discovered. */
    fun inspectResponse(resp: HttpResponse, where: String, onlyActive: Boolean = false): Int {
        if (!enabled) return 0
        var n = harvest(resp.toString(), "resp $where", onlyActive)
        val setCookies = resp.headers()
            .filter { it.name().equals("Set-Cookie", ignoreCase = true) }
            .map { it.value() }
        val cookies = CookieHeader.fromSetCookieHeaders(setCookies).ifEmpty {
            CookieHeader.fromSetCookieHeaders(
                resp.toString().lineSequence()
                    .filter { it.startsWith("Set-Cookie:", ignoreCase = true) }
                    .map { it.substringAfter(':').trim() }
                    .toList(),
            )
        }
        n += captureCookies(cookies, "resp $where", onlyActive)
        return n
    }

    /** Scan a raw HTTP message blob and record any JWTs found. Returns count of new tokens.
     *  With [onlyActive], expired tokens are ignored (used by the history scan). */
    fun harvest(blob: String, where: String, onlyActive: Boolean = false): Int {
        var n = 0
        for (tok in Jwt.findAll(blob)) {
            val info = Jwt.parse(tok) ?: continue
            if (onlyActive && info.isExpired()) continue
            if (store.capture(tok, where)) n++
        }
        return n
    }

    private fun captureCookies(cookies: Map<String, String>, where: String, onlyActive: Boolean): Int {
        if (cookies.isEmpty() || cookies.values.all { it.isBlank() }) return 0
        if (onlyActive) {
            val identity = CookieSession.identitySubset(cookies)
            val values = if (identity.isNotEmpty()) identity.values else cookies.values
            if (values.any { Jwt.parse(it)?.isExpired() == true }) return 0
        }
        return if (store.captureCookie(cookies, where)) 1 else 0
    }
}
