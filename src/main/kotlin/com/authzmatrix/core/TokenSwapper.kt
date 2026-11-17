package com.authzmatrix.core

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation

/**
 * Produces a copy of a request carrying a given persona's identity, replacing whatever auth
 * material the original request had. For anonymous personas it strips it instead.
 *
 * Cookie personas can carry a full cookie bundle (session + CSRF + …), not just a single value.
 * Header personas also strip auth cookies from the original request, so a leftover session cookie
 * cannot silently authenticate the wrong identity.
 */
object TokenSwapper {

    fun apply(req: HttpRequest, p: Persona): HttpRequest = synchronized(p) {
        when {
            p.anonymous -> strip(req, p)
            p.usesCookies -> applyCookieBundle(req, p)
            p.location == TokenLocation.BEARER_HEADER -> setHeader(
                stripForHeader(req, p, keep = "Authorization"),
                "Authorization",
                "Bearer ${p.token}",
            )
            p.location == TokenLocation.CUSTOM_HEADER -> setHeader(
                stripForHeader(req, p, keep = p.headerName),
                p.headerName,
                p.token,
            )
            else -> req
        }
    }

    private fun applyCookieBundle(req: HttpRequest, p: Persona): HttpRequest {
        var out = removeAuthCookies(req, p)
        out = removeHeader(out, "Authorization")
        if (!p.headerName.equals("Authorization", ignoreCase = true)) out = removeHeader(out, p.headerName)
        out = removeJwtHeaders(out, keep = null)

        val bundle = if (p.cookies.isNotEmpty()) p.cookies else mapOf(p.cookieName to p.token)
        val existing = CookieHeader.parse(out.headerValue("Cookie"))
        val merged = CookieHeader.replaceAuthCookies(existing, bundle)
        return if (merged.isEmpty()) removeHeader(out, "Cookie")
        else setHeader(out, "Cookie", CookieHeader.serialize(merged))
    }

    private fun strip(req: HttpRequest, p: Persona): HttpRequest {
        var out = req
        out = removeHeader(out, "Authorization")
        out = removeHeader(out, p.headerName)
        out = removeJwtHeaders(out, keep = null)
        return removeAuthCookies(out, p)
    }

    /** Remove the original auth slots except [keep], plus JWT-bearing headers. */
    private fun stripForHeader(req: HttpRequest, p: Persona, keep: String): HttpRequest {
        var out = removeAuthCookies(req, p)
        when {
            keep.equals("Authorization", ignoreCase = true) -> {
                if (!p.headerName.equals("Authorization", ignoreCase = true)) out = removeHeader(out, p.headerName)
            }
            else -> out = removeHeader(out, "Authorization")
        }
        return removeJwtHeaders(out, keep)
    }

    private fun removeAuthCookies(req: HttpRequest, p: Persona): HttpRequest {
        val existing = CookieHeader.parse(req.headerValue("Cookie"))
        if (existing.isEmpty()) return req
        val kept = CookieHeader.stripAuthCookies(existing, p.cookies.keys + p.cookieName)
        return if (kept.isEmpty()) removeHeader(req, "Cookie")
        else setHeader(req, "Cookie", CookieHeader.serialize(kept))
    }

    /** Remove every header carrying a JWT-looking value, except [keep] and the Cookie header. */
    private fun removeJwtHeaders(req: HttpRequest, keep: String?): HttpRequest {
        val names = req.headers()
            .filter { h ->
                !h.name().equals("Cookie", ignoreCase = true) &&
                    (keep == null || !h.name().equals(keep, ignoreCase = true)) &&
                    Jwt.findAll(h.value()).isNotEmpty()
            }
            .map { it.name() }
            .distinct()
        var out = req
        for (name in names) out = removeHeader(out, name)
        return out
    }

    private fun setHeader(req: HttpRequest, name: String, value: String): HttpRequest =
        if (req.hasHeader(name)) req.withUpdatedHeader(name, value) else req.withAddedHeader(name, value)

    private fun removeHeader(req: HttpRequest, name: String): HttpRequest =
        if (req.hasHeader(name)) req.withRemovedHeader(name) else req
}
