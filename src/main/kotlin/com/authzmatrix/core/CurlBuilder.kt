package com.authzmatrix.core

import burp.api.montoya.http.message.requests.HttpRequest

/**
 * Builds a shell-ready `curl` command for a replayed request, so a finding can be reproduced
 * outside Burp (or pasted into a report). Single quotes are escaped the POSIX way, which also
 * covers JSON bodies and header values containing quotes.
 */
object CurlBuilder {

    fun build(req: HttpRequest): String {
        val sb = StringBuilder("curl -sS -X ").append(req.method()).append(" '").append(quote(req.url())).append("'")
        for (h in req.headers()) {
            if (h.name().equals("Content-Length", ignoreCase = true)) continue
            sb.append(" \\\n  -H '").append(quote("${h.name()}: ${h.value()}")).append("'")
        }
        val body = req.bodyToString()
        if (body.isNotEmpty()) {
            sb.append(" \\\n  --data-raw '").append(quote(body)).append("'")
        }
        return sb.toString()
    }

    /** POSIX single-quote escaping: `it's` → `it'\''s`. */
    internal fun quote(s: String): String = s.replace("'", "'\\''")
}
