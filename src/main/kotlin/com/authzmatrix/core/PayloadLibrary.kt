package com.authzmatrix.core

import org.json.JSONArray

/**
 * A bundled, offline arsenal of common web-security test payloads so you don't have to go hunting
 * on GitHub mid-engagement. Data lives in the `payloads.json` classpath resource (loaded once);
 * keeping it out of Kotlin avoids escaping hell for payloads full of `$`, `{`, `"` and `\`.
 *
 * These are ubiquitous probe strings — use only against systems you are authorised to test.
 * Notes use `OOB` for an out-of-band/collaborator host and `id`/`whoami` as sample commands.
 */
object PayloadLibrary {

    data class Payload(val value: String, val note: String = "")

    /** Ordered categories → payloads (order preserved from the JSON array). */
    val categories: Map<String, List<Payload>> by lazy { load() }

    val total: Int get() = categories.values.sumOf { it.size }

    private fun load(): Map<String, List<Payload>> {
        val stream = PayloadLibrary::class.java.getResourceAsStream("/payloads.json")
            ?: return emptyMap()
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val out = LinkedHashMap<String, List<Payload>>()
        val arr = JSONArray(text)
        for (i in 0 until arr.length()) {
            val cat = arr.getJSONObject(i)
            val name = cat.getString("name")
            val items = cat.getJSONArray("payloads")
            val list = ArrayList<Payload>(items.length())
            for (j in 0 until items.length()) {
                val p = items.getJSONObject(j)
                list.add(Payload(p.getString("value"), p.optString("note", "")))
            }
            out[name] = list
        }
        return out
    }
}
