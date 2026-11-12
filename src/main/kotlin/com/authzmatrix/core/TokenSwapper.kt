package com.authzmatrix.core

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation

/**
 * Produces a copy of a request carrying a given persona's identity, replacing whatever
 * auth material the original request had. For anonymous personas it strips it instead.
 */
object TokenSwapper {

    fun apply(req: HttpRequest, p: Persona): HttpRequest = when {
        p.anonymous -> strip(req, p)
        p.location == TokenLocation.BEARER_HEADER -> setHeader(req, "Authorization", "Bearer ${p.token}")
        p.location == TokenLocation.CUSTOM_HEADER -> setHeader(req, p.headerName, p.token)
        p.location == TokenLocation.COOKIE -> setCookie(req, p.cookieName, p.token)
        else -> req
    }

    private fun strip(req: HttpRequest, p: Persona): HttpRequest {
        var out = req

        // Strip unconditionally: an "anonymous" replay must never inherit a Bearer credential,
        // even if the persona was configured for a custom header/cookie.
        out = removeHeader(out, "Authorization")

        // Configured auth slot (no-op when it is Authorization, already removed above).
        out = removeHeader(out, p.headerName)
        out = removeCookie(out, p.cookieName)

        // Other headers carrying a JWT (X-Auth-Token, access_token, …).
        val jwtHeaderNames = out.headers()
            .filter { !it.name().equals("Cookie", ignoreCase = true) && Jwt.findAll(it.value()).isNotEmpty() }
            .map { it.name() }
            .distinct()
        for (name in jwtHeaderNames) out = removeHeader(out, name)

        // JWTs hidden in cookies; keep non-auth cookies such as locale/CSRF.
        return stripJwtCookies(out)
    }

    private fun stripJwtCookies(req: HttpRequest): HttpRequest {
        val existing = req.headerValue("Cookie") ?: return req
        val kept = existing.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && Jwt.findAll(it.substringAfter("=", "")).isEmpty() }
        return if (kept.isEmpty()) removeHeader(req, "Cookie")
        else setHeader(req, "Cookie", kept.joinToString("; "))
    }

    private fun setHeader(req: HttpRequest, name: String, value: String): HttpRequest =
        if (req.hasHeader(name)) req.withUpdatedHeader(name, value) else req.withAddedHeader(name, value)

    private fun removeHeader(req: HttpRequest, name: String): HttpRequest =
        if (req.hasHeader(name)) req.withRemovedHeader(name) else req

    private fun setCookie(req: HttpRequest, name: String, value: String): HttpRequest {
        val merged = upsertCookie(req.headerValue("Cookie"), name, value)
        return setHeader(req, "Cookie", merged)
    }

    private fun removeCookie(req: HttpRequest, name: String): HttpRequest {
        val existing = req.headerValue("Cookie") ?: return req
        val kept = existing.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.substringBefore("=").trim().equals(name, ignoreCase = true) }
        return if (kept.isEmpty()) removeHeader(req, "Cookie")
        else setHeader(req, "Cookie", kept.joinToString("; "))
    }

    private fun upsertCookie(existing: String?, name: String, value: String): String {
        val pairs = (existing ?: "").split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val idx = pairs.indexOfFirst { it.substringBefore("=").trim().equals(name, ignoreCase = true) }
        if (idx >= 0) pairs[idx] = "$name=$value" else pairs += "$name=$value"
        return pairs.joinToString("; ")
    }
}
