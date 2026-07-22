package com.authzmatrix.core

import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.persistence.Preferences
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation
import org.json.JSONArray
import org.json.JSONObject

/** Persists personas across Burp restarts via Burp's project [Preferences]. */
object PersonaPersistence {

    // v2: bumped so any personas cached by older builds (which had default admin/user/anon) are
    // abandoned automatically — the old key is never read again and is purged on load.
    private const val KEY = "authz.personas.v2"
    private const val LEGACY_KEY = "authz.personas"

    fun load(prefs: Preferences): List<Persona>? {
        // Purge legacy cache (old builds seeded admin/user/anon there) so it can never resurface.
        if (!prefs.getString(LEGACY_KEY).isNullOrEmpty()) prefs.setString(LEGACY_KEY, "")
        val json = prefs.getString(KEY)
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                val p = Persona(
                    name = o.optString("name"),
                    token = o.optString("token"),
                    location = runCatching { TokenLocation.valueOf(o.optString("location")) }
                        .getOrDefault(TokenLocation.BEARER_HEADER),
                    headerName = o.optString("headerName"),
                    cookieName = o.optString("cookieName"),
                    anonymous = o.optBoolean("anonymous"),
                    enabled = o.optBoolean("enabled", true),
                    ownerSub = o.optString("ownerSub").takeIf { it.isNotEmpty() },
                )
                p.level = o.optInt("level", 0)
                p.refreshTokenField = o.optString("refreshField").takeIf { it.isNotEmpty() }
                val raw = o.optString("refreshRaw").takeIf { it.isNotEmpty() }
                val host = o.optString("refreshHost").takeIf { it.isNotEmpty() }
                if (raw != null && host != null) {
                    p.refreshRequest = runCatching {
                        HttpRequest.httpRequest(
                            HttpService.httpService(host, o.optInt("refreshPort", 443), o.optBoolean("refreshTls", true)),
                            raw,
                        )
                    }.getOrNull()
                }
                p
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(prefs: Preferences, personas: List<Persona>) {
        val arr = JSONArray()
        for (p in personas) {
            val svc = p.refreshRequest?.httpService()
            val o = JSONObject()
            o.put("name", p.name)
            o.put("token", p.token)
            o.put("location", p.location.name)
            o.put("headerName", p.headerName)
            o.put("cookieName", p.cookieName)
            o.put("anonymous", p.anonymous)
            o.put("enabled", p.enabled)
            p.ownerSub?.let { o.put("ownerSub", it) }
            p.refreshRequest?.let { o.put("refreshRaw", it.toString()) }
            svc?.let {
                o.put("refreshHost", it.host())
                o.put("refreshPort", it.port())
                o.put("refreshTls", it.secure())
            }
            p.refreshTokenField?.let { o.put("refreshField", it) }
            o.put("level", p.level)
            arr.put(o)
        }
        prefs.setString(KEY, arr.toString())
    }
}
