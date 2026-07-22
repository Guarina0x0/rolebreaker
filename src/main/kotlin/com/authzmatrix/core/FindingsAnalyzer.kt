package com.authzmatrix.core

import com.authzmatrix.model.AccessResult
import com.authzmatrix.model.ActivityEntry
import com.authzmatrix.model.Finding
import com.authzmatrix.model.MatrixRow
import com.authzmatrix.model.Severity
import com.authzmatrix.model.Verdict

/**
 * Derives the *interesting* results from the raw matrix + activity log, so the auditor sees only
 * likely broken-access findings instead of eyeballing the whole grid.
 */
object FindingsAnalyzer {

    /** Row-label marker set by the auto lower-privilege test; every tested role is, by construction,
     *  less privileged than the request's own identity. */
    const val LOWER_PRIV_MARKER = "[menor-priv]"

    private fun gotIn(v: Verdict) = v == Verdict.ALLOWED || v == Verdict.SAME_AS_BASELINE

    fun analyze(rows: List<MatrixRow>, entries: List<ActivityEntry>, store: PersonaStore): List<Finding> {
        val byId = store.personas.associateBy { it.id }
        val out = ArrayList<Finding>()

        for (row in rows) {
            val results = row.results.values.toList()
            val accessed = results.filter { gotIn(it.verdict) }
            val denied = results.filter { it.verdict == Verdict.DENIED }

            // 1) Anonymous access — unauthenticated identity got in.
            accessed.filter { byId[it.personaId]?.anonymous == true }.forEach { r ->
                out += Finding(Severity.CRITICAL, "Acceso anónimo",
                    "Sin token se accede a ${row.url} (HTTP ${r.statusCode})", r.requestResponse, row, r)
            }

            val accessedRoles = accessed.filter { byId[it.personaId]?.anonymous != true }
            val isIdor = row.label.contains("→")
            val isLowerPrivTest = row.label.contains(LOWER_PRIV_MARKER)
            val endpointSev = EndpointRisk.severity(row.url)

            when {
                isIdor -> {
                    // 2) Horizontal access — a role reached a resource identified by another id.
                    accessedRoles.forEach { r ->
                        val name = byId[r.personaId]?.name ?: "rol"
                        out += Finding(Severity.HIGH, "IDOR / acceso horizontal",
                            "«$name» accede a recurso ajeno: ${row.label} (HTTP ${r.statusCode})",
                            r.requestResponse, row, r)
                    }
                }
                isLowerPrivTest -> {
                    // 2b) A role known to be LESS privileged than the request's identity got in —
                    //     a finding even if no role was denied (they all succeeded).
                    accessedRoles.forEach { r ->
                        val name = byId[r.personaId]?.name ?: "rol"
                        out += Finding(EndpointRisk.escalate(Severity.HIGH, endpointSev),
                            "Rol de menor privilegio con acceso",
                            "«$name» (menor privilegio) accede a ${row.method} ${row.url} (HTTP ${r.statusCode})",
                            r.requestResponse, row, r)
                    }
                }
                accessedRoles.isNotEmpty() && denied.isNotEmpty() -> {
                    // 3) Differential access — same endpoint, some roles in and some out.
                    val inNames = accessedRoles.mapNotNull { byId[it.personaId]?.name }.joinToString(", ")
                    val outNames = denied.mapNotNull { byId[it.personaId]?.name }.joinToString(", ")
                    val evidence = accessedRoles.first()
                    out += Finding(EndpointRisk.escalate(Severity.MEDIUM, endpointSev), "Acceso diferencial",
                        "${row.method} ${row.url}: acceden [$inNames] · deniegan [$outNames]",
                        evidence.requestResponse, row, evidence)
                }
            }

            // 5) All-vs-all — two distinct roles get a byte-identical 2xx response (possible
            //    horizontal BAC / un-segregated resource). Skip responses equal to anon's (public).
            val anonKey = results.firstOrNull {
                byId[it.personaId]?.anonymous == true && it.statusCode in 200..299 && it.bodyHash != 0
            }?.let { it.statusCode to it.bodyHash }
            accessedRoles
                .filter { it.statusCode in 200..299 && it.bodyHash != 0 }
                .groupBy { it.statusCode to it.bodyHash }
                .filter { it.value.size >= 2 && it.key != anonKey }
                .forEach { (_, group) ->
                    val names = group.mapNotNull { byId[it.personaId]?.name }.joinToString(", ")
                    out += Finding(EndpointRisk.escalate(Severity.MEDIUM, endpointSev), "Respuesta idéntica entre roles",
                        "${row.method} ${row.url}: [$names] reciben la MISMA respuesta 2xx — posible acceso horizontal / recurso no segregado",
                        group.first().requestResponse, row, group.first())
                }
        }

        // 4) Forged JWT accepted — broken signature verification.
        entries.filter { it.what.startsWith("🔴 JWT") && it.verdict == Verdict.ALLOWED }.forEach { e ->
            out += Finding(Severity.CRITICAL, "JWT forjado aceptado",
                "${e.what} aceptado en ${e.method} ${e.url} (HTTP ${e.statusCode})", e.requestResponse)
        }

        return out.sortedBy { it.severity.ordinal }
    }
}
