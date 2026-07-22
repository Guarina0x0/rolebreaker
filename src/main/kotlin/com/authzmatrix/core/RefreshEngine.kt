package com.authzmatrix.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.responses.HttpResponse
import com.authzmatrix.model.Persona
import org.json.JSONObject

/**
 * Re-logs a persona in by replaying its saved login/refresh request and extracting the new
 * token from the response (a dotted JSON field if configured, else the first JWT found).
 */
class RefreshEngine(private val api: MontoyaApi) {

    /** Returns the fresh token, or null if the refresh failed / yielded nothing. */
    fun refresh(p: Persona): String? {
        val req = p.refreshRequest ?: return null
        val resp = try {
            api.http().sendRequest(req).response()
        } catch (e: Exception) {
            api.logging().logToError("AuthZ Matrix: refresh de '${p.name}' falló: ${e.message}")
            return null
        } ?: return null
        return extract(resp, p.refreshTokenField)
    }

    private fun extract(resp: HttpResponse, field: String?): String? {
        if (!field.isNullOrBlank()) {
            fieldValue(resp.bodyToString(), field)?.let { return it }
        }
        // Fallback: any JWT in the response (body or Set-Cookie).
        return Jwt.findAll(resp.toString()).firstOrNull()
    }

    private fun fieldValue(body: String, dotted: String): String? {
        return try {
            var node: Any? = JSONObject(body)
            for (part in dotted.split(".")) {
                node = (node as? JSONObject)?.opt(part) ?: return null
            }
            // Only accept a scalar leaf; an object/array means the path is wrong → fall back.
            when (node) {
                null, org.json.JSONObject.NULL, is JSONObject, is org.json.JSONArray -> null
                else -> node.toString().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
