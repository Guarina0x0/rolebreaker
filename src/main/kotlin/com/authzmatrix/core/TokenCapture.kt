package com.authzmatrix.core

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse

/**
 * Harvests JWTs from traffic (headers, cookies, bodies) so the user never pastes tokens.
 * Used both by the live proxy handler and the on-demand history scanner.
 */
class TokenCapture(private val store: PersonaStore) {

    @Volatile
    var enabled: Boolean = true

    fun inspectRequest(req: HttpRequest) {
        if (!enabled) return
        harvest(req.toString(), "req ${req.method()} ${req.path()}")
    }

    fun inspectResponse(resp: HttpResponse, where: String) {
        if (!enabled) return
        harvest(resp.toString(), "resp $where")
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
}
