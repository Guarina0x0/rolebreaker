package com.authzmatrix.core

import burp.api.montoya.http.message.responses.HttpResponse
import com.authzmatrix.model.Verdict
import kotlin.math.abs

/**
 * Turns a replayed response into an access [Verdict] relative to a normalized baseline.
 *
 * False-positive reduction:
 *  - **normalize**: strips volatile fields (CSRF/nonce/timestamp/iat) before size comparison, so
 *    dynamic content doesn't look like "different access".
 *  - **login-wall detection**: a 2xx whose body is a login form (or a 3xx redirect to an auth URL)
 *    is treated as DENIED, not as access — the classic SPA "200 with the login page" trap.
 */
class EnforcementDetector {

    /** Body matches → DENIED regardless of status. */
    var denyRegexes: List<Regex> = DEFAULT_DENY

    /** Body looks like a login/auth wall → DENIED even on 2xx. */
    var loginRegexes: List<Regex> = DEFAULT_LOGIN

    /** Volatile fragments removed before size/hash comparison. */
    var ignoreRegexes: List<Regex> = DEFAULT_IGNORE

    var sizeTolerancePct: Int = 5

    /** Body with volatile fragments stripped, for stable comparison. */
    fun normalize(body: String): String {
        var s = body
        for (r in ignoreRegexes) s = r.replace(s, "")
        return s
    }

    fun normalizedLength(body: String): Int = normalize(body).length

    fun classify(baselineStatus: Int, baselineNormLen: Int, resp: HttpResponse?, baselineBody: String = ""): Verdict {
        if (resp == null) return Verdict.ERROR
        val code = resp.statusCode().toInt()
        if (code in 300..399) {
            val loc = resp.headerValue("Location").orEmpty()
            if (LOGIN_URL.containsMatchIn(loc)) return Verdict.DENIED
        }
        return classify(baselineStatus, baselineNormLen, code, resp.bodyToString(), baselineBody)
    }

    /**
     * Pure classification core (no Burp types) — unit-testable. [baselineNormLen] is the normalized
     * length of the baseline body; [baselineBody] is the baseline itself so that deny/login markers
     * which ALSO appear in the baseline (e.g. an admin change-password page) are treated as normal
     * content, not as a denial — avoiding false DENIED (missed findings).
     */
    fun classify(baselineStatus: Int, baselineNormLen: Int, code: Int, body: String, baselineBody: String = ""): Verdict {
        if (denyRegexes.any { it.containsMatchIn(body) } && denyRegexes.none { it.containsMatchIn(baselineBody) }) {
            return Verdict.DENIED
        }
        if (loginRegexes.any { it.containsMatchIn(body) } && loginRegexes.none { it.containsMatchIn(baselineBody) }) {
            return Verdict.DENIED // auth wall / login page shown only to this role
        }
        if (code == 401 || code == 403) return Verdict.DENIED

        if (code in 200..299) {
            if (baselineStatus < 0) return Verdict.CHECK // no valid baseline → can't call it access
            val len = normalize(body).length
            val tolerance = maxOf(baselineNormLen * sizeTolerancePct / 100, 16)
            val same = code == baselineStatus && baselineNormLen > 0 && abs(len - baselineNormLen) <= tolerance
            return if (same) Verdict.SAME_AS_BASELINE else Verdict.ALLOWED
        }
        return Verdict.CHECK
    }

    companion object {
        val DEFAULT_DENY: List<Regex> = listOf(
            Regex("(?i)\\b(unauthori[sz]ed|forbidden|access denied|permission denied|not allowed|acceso denegado|no autorizado)\\b"),
        )

        // Strong login-form signals; precise on purpose to avoid marking real content as a wall.
        val DEFAULT_LOGIN: List<Regex> = listOf(
            Regex("""(?i)<input[^>]+type=["']password["']"""),
            Regex("""(?i)\bname=["']password["']"""),
            Regex("(?i)(iniciar sesión|introduce tus credenciales|please (log|sign) ?in|authentication required)"),
        )

        val DEFAULT_IGNORE: List<Regex> = listOf(
            Regex("""(?i)"(nonce|timestamp|ts|iat|csrf[_-]?token|_csrf|xsrf[_-]?token|request_?id|trace_?id)"\s*:\s*("[^"]*"|\d+)"""),
            Regex("""(?i)(name|id)=["'](_?csrf[^"']*|xsrf[^"']*|authenticity_token)["'][^>]*value=["'][^"']*["']"""),
        )

        val LOGIN_URL: Regex = Regex("(?i)/(login|log-in|signin|sign-in|sign_in|auth|sso|account/login|users/sign_in)")
    }
}
