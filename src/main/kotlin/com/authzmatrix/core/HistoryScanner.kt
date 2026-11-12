package com.authzmatrix.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import java.time.ZonedDateTime

/** Scans Burp's recent Proxy HTTP history for JWTs (on-demand). */
class HistoryScanner(
    private val api: MontoyaApi,
    private val capture: TokenCapture,
    private val store: PersonaStore,
) {

    /**
     * Harvest JWTs seen in the last [withinMinutes] minutes of proxy history. With [onlyActive],
     * expired tokens are skipped. Returns the number of new tokens discovered.
     */
    fun scan(withinMinutes: Long, onlyActive: Boolean): Int = store.batch {
        val cutoff = ZonedDateTime.now().minusMinutes(withinMinutes)
        var found = 0
        var scanned = 0
        for (item in api.proxy().history()) {
            val t = runCatching { item.time() }.getOrNull()
            if (t != null && t.isBefore(cutoff)) continue
            scanned++
            val req = item.finalRequest()
            found += capture.harvest(req.toString(), "history: ${req.method()} ${req.path()}", onlyActive)
            item.response()?.let { resp ->
                found += capture.harvest(resp.toString(), "history resp: ${req.method()} ${req.path()}", onlyActive)
            }
        }
        api.logging().logToOutput(
            "RoleBreaker: history scan (last ${withinMinutes}m, $scanned items) → $found new token(s)")
        found
    }

    /**
     * Recent in-scope, non-static requests. With [collapseIds], `/users/1` and `/users/2` dedupe to
     * the same endpoint key; callers still receive the first real request seen for that key.
     */
    fun recentRequests(withinMinutes: Long, collapseIds: Boolean = false): List<HttpRequest> {
        val cutoff = ZonedDateTime.now().minusMinutes(withinMinutes)
        val seen = HashSet<String>()
        val out = ArrayList<HttpRequest>()
        for (item in api.proxy().history()) {
            val t = runCatching { item.time() }.getOrNull()
            if (t != null && t.isBefore(cutoff)) continue
            val r = item.finalRequest()
            if (!api.scope().isInScope(r.url())) continue
            if (StaticAssets.isStatic(r.url())) continue
            val key = "${r.method()} ${if (collapseIds) EndpointKey.normalize(r.url()) else r.url()}"
            if (seen.add(key)) out += r
        }
        return out
    }
}
