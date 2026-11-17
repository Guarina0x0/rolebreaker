package com.authzmatrix.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.model.AccessResult
import com.authzmatrix.model.ActivityEntry
import com.authzmatrix.model.MatrixRow
import com.authzmatrix.model.Verdict
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Replays one request as every enabled persona and classifies each response.
 * Emits an [ActivityEntry] per launched request (live log). Runs on a worker thread.
 */
class ReplayEngine(
    private val api: MontoyaApi,
    private val store: PersonaStore,
    private val detector: EnforcementDetector,
) {

    /** Called for every request actually sent (baseline + each persona). */
    @Volatile
    var onActivity: (ActivityEntry) -> Unit = {}

    /** Called before replaying as a persona — a chance to re-login if its token expired. */
    @Volatile
    var refresher: (com.authzmatrix.model.Persona) -> Unit = {}

    fun test(
        original: HttpRequest,
        label: String? = null,
        personas: List<com.authzmatrix.model.Persona> = store.enabledPersonas(),
    ): MatrixRow {
        val baseRR = safeSend(original)
        val baseResp = baseRR?.response()
        val baseBody = baseResp?.bodyToString() ?: ""
        val baseStatus = baseResp?.statusCode()?.toInt() ?: -1
        val baseRawLen = baseBody.length
        val baseNormLen = if (baseResp != null) detector.normalizedLength(baseBody) else 0

        emit("baseline", original.method(), original.url(), baseStatus, baseRawLen, null, baseRR)

        val row = MatrixRow(
            label = label ?: "${original.method()} ${original.path()}",
            method = original.method(),
            url = original.url(),
            baseline = baseRR,
            baselineStatus = baseStatus,
            baselineLength = baseRawLen,
        )

        for (p in personas) {
            refresher(p) // may re-login / swap in a fresher token
            // Every request must carry a valid-in-time identity. Anon is the only deliberately
            // token-less identity; any other persona without credentials, or with an EXPIRED JWT
            // (in a header or cookie) that refresh couldn't renew, is skipped rather than sent.
            if (!p.anonymous) {
                if (p.usesCookies) {
                    val cookies = synchronized(p) { LinkedHashMap(p.cookies) }
                    if (cookies.isEmpty() && p.token.isBlank()) {
                        api.logging().logToOutput("RoleBreaker: '${p.name}' has no cookies — skipped (never sent without credentials)")
                        continue
                    }
                    val identity = CookieSession.identitySubset(cookies)
                    val cookiesToCheck = if (identity.isNotEmpty()) identity.values else cookies.values
                    val expired = cookiesToCheck.any { Jwt.parse(it)?.isExpired() == true }
                    if (expired) {
                        api.logging().logToOutput("RoleBreaker: '${p.name}' has an expired JWT cookie — skipped (never sends an expired JWT)")
                        continue
                    }
                } else {
                    if (p.token.isBlank()) {
                        api.logging().logToOutput("RoleBreaker: '${p.name}' has no token — skipped (never sent without a JWT)")
                        continue
                    }
                    if (com.authzmatrix.core.Jwt.parse(p.token)?.isExpired() == true) {
                        api.logging().logToOutput("RoleBreaker: '${p.name}' has an expired token — skipped (never sends an expired JWT)")
                        continue
                    }
                }
            }
            val swapped = TokenSwapper.apply(original, p)
            val rr = safeSend(swapped)
            val resp = rr?.response()
            val result = if (resp == null) {
                AccessResult(p.id, -1, 0, Verdict.ERROR, rr)
            } else {
                val body = resp.bodyToString()
                AccessResult(
                    personaId = p.id,
                    statusCode = resp.statusCode().toInt(),
                    bodyLength = body.length,
                    verdict = detector.classify(baseStatus, baseNormLen, resp, baseBody),
                    requestResponse = rr,
                    bodyHash = detector.normalize(body).hashCode(),
                )
            }
            row.results[p.id] = result
            emit(p.name, swapped.method(), swapped.url(), result.statusCode, result.bodyLength, result.verdict, rr)
        }
        return row
    }

    /** Send a request once as-is (no persona swap) and log it — used for JWT attack variants. */
    fun sendOnce(req: HttpRequest, what: String) {
        val rr = safeSend(req)
        val resp = rr?.response()
        val status = resp?.statusCode()?.toInt() ?: -1
        val len = resp?.bodyToString()?.length ?: 0
        // No baseline exists for a forged-token probe: a 2xx must be read as "accepted" or the
        // Findings analyzer would never surface the CRITICAL forged-JWT finding.
        emit(what, req.method(), req.url(), status, len, detector.classifyStandalone(resp), rr)
    }

    private fun emit(what: String, method: String, url: String, status: Int, len: Int,
                     verdict: Verdict?, rr: burp.api.montoya.http.message.HttpRequestResponse?) {
        onActivity(ActivityEntry(LocalTime.now().format(TIME), what, method, url, status, len, verdict, rr))
    }

    private fun safeSend(req: HttpRequest) = try {
        api.http().sendRequest(req)
    } catch (e: Exception) {
        api.logging().logToError("Replay failed: ${e.message}")
        null
    }

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
