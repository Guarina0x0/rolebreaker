package com.authzmatrix.core

import com.authzmatrix.model.Finding
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the Findings view for reporting. Uses the finding's own request/response when
 * available, falling back to the matrix row for the endpoint metadata.
 */
object FindingsExport {

    private val CSV_HEADER = listOf("severity", "type", "detail", "method", "url", "status")

    fun csv(items: List<Finding>): String {
        val sb = StringBuilder(CSV_HEADER.joinToString(",")).append('\n')
        for (f in items) {
            val cells = listOf(
                f.severity.name,
                f.type,
                f.summary,
                method(f),
                url(f),
                status(f)?.toString() ?: "",
            )
            sb.append(cells.joinToString(",") { csvCell(it) }).append('\n')
        }
        return sb.toString()
    }

    fun json(items: List<Finding>): String {
        val arr = JSONArray()
        for (f in items) {
            arr.put(
                JSONObject()
                    .put("severity", f.severity.name)
                    .put("type", f.type)
                    .put("summary", f.summary)
                    .put("method", method(f))
                    .put("url", url(f))
                    .put("status", status(f) ?: JSONObject.NULL),
            )
        }
        return arr.toString(2)
    }

    private fun method(f: Finding): String =
        f.requestResponse?.request()?.method() ?: f.row?.method ?: ""

    private fun url(f: Finding): String =
        f.requestResponse?.request()?.url() ?: f.row?.url ?: ""

    private fun status(f: Finding): Int? =
        f.result?.statusCode?.takeIf { it > 0 }
            ?: f.requestResponse?.response()?.statusCode()?.toInt()?.takeIf { it > 0 }
            ?: f.row?.baselineStatus?.takeIf { it > 0 }

    private fun csvCell(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }
}
