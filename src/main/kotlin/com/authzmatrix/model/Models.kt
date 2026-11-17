package com.authzmatrix.model

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import java.awt.Color
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a persona's token lives inside a request, i.e. how [com.authzmatrix.core.TokenSwapper]
 * injects it.
 */
enum class TokenLocation {
    /** `Authorization: Bearer <jwt>` */
    BEARER_HEADER,

    /** A custom header, value = the raw token (see [Persona.headerName]). */
    CUSTOM_HEADER,

    /** A named cookie inside the `Cookie` header (see [Persona.cookieName]). */
    COOKIE,
}

/**
 * A role/identity we replay requests as. "anonymous" means: strip the auth material
 * entirely to test the unauthenticated case.
 */
class Persona(
    var name: String,
    var token: String = "",
    var location: TokenLocation = TokenLocation.BEARER_HEADER,
    var headerName: String = "Authorization",
    var cookieName: String = "session",
    var anonymous: Boolean = false,
    var enabled: Boolean = true,
    /** `sub` claim of this persona's token; used to auto-refresh with a fresher token of the same identity. */
    var ownerSub: String? = null,
) {
    val id: Int = nextId.getAndIncrement()

    /**
     * Cookie bundle for cookie-based sessions (name → value). Empty for bearer/custom-header
     * personas. A bundle lets us swap a whole session (session + CSRF + other auth cookies)
     * instead of a single cookie value.
     */
    val cookies: MutableMap<String, String> = LinkedHashMap()

    /** True when this persona is applied through the Cookie header. */
    val usesCookies: Boolean get() = location == TokenLocation.COOKIE

    /** Login/refresh request to replay when this persona's token expires (re-login). */
    var refreshRequest: HttpRequest? = null

    /** Optional dotted JSON field in the refresh response holding the token (else: first JWT found). */
    var refreshTokenField: String? = null

    val hasRefresh: Boolean get() = refreshRequest != null

    /** Privilege level: lower number = more privileged. Used by the auto "lower-privilege" test. */
    var level: Int = 0

    companion object {
        private val nextId = AtomicInteger(1)
    }
}

/** The access decision for one persona against one request. */
enum class Verdict {
    /** 2xx and clearly different from the reference response — got in, saw distinct content. */
    ALLOWED,

    /** 2xx and (status + size) match the reference/baseline response. */
    SAME_AS_BASELINE,

    /** 401/403 or matched a "denied" rule — access control enforced. */
    DENIED,

    /** Something else (3xx, 5xx, redirect to login, etc.) — needs a human. */
    CHECK,

    /** The replay itself failed (connection error, etc.). */
    ERROR;

    /** Background colour used to render this verdict in the matrix. */
    fun background(): Color = when (this) {
        ALLOWED -> Color(0xFF, 0xCD, 0xD2)          // red-ish: got access (interesting!)
        SAME_AS_BASELINE -> Color(0xFF, 0xE0, 0xB2) // amber: same as reference identity
        DENIED -> Color(0xC8, 0xE6, 0xC9)           // green: properly blocked
        CHECK -> Color(0xFF, 0xF9, 0xC4)            // yellow: ambiguous
        ERROR -> Color(0xEE, 0xEE, 0xEE)            // grey
    }
}

/** Result of replaying one request as one persona. */
class AccessResult(
    val personaId: Int,
    val statusCode: Int,
    val bodyLength: Int,
    val verdict: Verdict,
    val requestResponse: HttpRequestResponse?,
    /** Hash of the response body, for exact cross-persona comparison (all-vs-all). */
    val bodyHash: Int = 0,
) {
    /** Short cell text, e.g. "200 · 1.2 KB". */
    fun cellText(): String {
        if (verdict == Verdict.ERROR) return "ERR"
        return "$statusCode · ${humanBytes(bodyLength)}"
    }
}

/** One tested request and its per-persona results. */
class MatrixRow(
    val label: String,
    val method: String,
    val url: String,
    /** The reference response (original request replayed as-is), used for comparison. */
    val baseline: HttpRequestResponse?,
    val baselineStatus: Int,
    val baselineLength: Int,
    val results: MutableMap<Int, AccessResult> = LinkedHashMap(),
) {
    /** Base cell text, e.g. `200 · 1.2 KB` (or `-` when the baseline failed). */
    fun baselineText(): String =
        if (baselineStatus > 0) "$baselineStatus · ${humanBytes(baselineLength)}" else "-"
}

enum class Severity {
    CRITICAL, HIGH, MEDIUM, LOW;

    fun background(): Color = when (this) {
        CRITICAL -> Color(0xEF, 0x9A, 0x9A)
        HIGH -> Color(0xFF, 0xCC, 0x80)
        MEDIUM -> Color(0xFF, 0xF1, 0x8F)
        LOW -> Color(0xE0, 0xE0, 0xE0)
    }
}

/** A confirmed/likely finding surfaced to the "Findings" view. */
class Finding(
    val severity: Severity,
    val type: String,
    val summary: String,
    val requestResponse: HttpRequestResponse?,
    val row: MatrixRow? = null,
    val result: AccessResult? = null,
)

/** One launched request, for the live activity log. */
class ActivityEntry(
    val time: String,
    val what: String,
    val method: String,
    val url: String,
    val statusCode: Int,
    val bodyLength: Int,
    val verdict: Verdict?,
    val requestResponse: HttpRequestResponse?,
)

private fun humanBytes(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024))
}
