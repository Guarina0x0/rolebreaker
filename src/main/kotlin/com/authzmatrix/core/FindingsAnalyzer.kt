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
     *  less privileged than the request's own identity. Language-neutral so the check is stable. */
    const val LOWER_PRIV_MARKER = "[lower-priv]"

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
                out += Finding(Severity.CRITICAL, I18n.t("find.anonAccess"),
                    I18n.t("find.anonAccess.sum", row.url, r.statusCode), r.requestResponse, row, r)
            }

            val accessedRoles = accessed.filter { byId[it.personaId]?.anonymous != true }
            val isIdor = row.label.contains("→")
            val isLowerPrivTest = row.label.contains(LOWER_PRIV_MARKER)
            val endpointSev = EndpointRisk.severity(row.url)

            when {
                isIdor -> {
                    // 2) Horizontal access — a role reached a resource identified by another id.
                    accessedRoles.forEach { r ->
                        val name = byId[r.personaId]?.name ?: I18n.t("role.fallback")
                        out += Finding(Severity.HIGH, I18n.t("find.idor"),
                            I18n.t("find.idor.sum", name, row.label, r.statusCode),
                            r.requestResponse, row, r)
                    }
                }
                isLowerPrivTest -> {
                    // 2b) A role known to be LESS privileged than the request's identity got in —
                    //     a finding even if no role was denied (they all succeeded).
                    accessedRoles.forEach { r ->
                        val name = byId[r.personaId]?.name ?: I18n.t("role.fallback")
                        out += Finding(EndpointRisk.escalate(Severity.HIGH, endpointSev),
                            I18n.t("find.lowerPriv"),
                            I18n.t("find.lowerPriv.sum", name, row.method, row.url, r.statusCode),
                            r.requestResponse, row, r)
                    }
                }
                accessedRoles.isNotEmpty() && denied.isNotEmpty() -> {
                    // 3) Differential access — same endpoint, some roles in and some out.
                    val inNames = accessedRoles.mapNotNull { byId[it.personaId]?.name }.joinToString(", ")
                    val outNames = denied.mapNotNull { byId[it.personaId]?.name }.joinToString(", ")
                    val evidence = accessedRoles.first()
                    out += Finding(EndpointRisk.escalate(Severity.MEDIUM, endpointSev), I18n.t("find.differential"),
                        I18n.t("find.differential.sum", row.method, row.url, inNames, outNames),
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
                    out += Finding(EndpointRisk.escalate(Severity.MEDIUM, endpointSev), I18n.t("find.identical"),
                        I18n.t("find.identical.sum", row.method, row.url, names),
                        group.first().requestResponse, row, group.first())
                }
        }

        // 4) Forged JWT accepted — broken signature verification.
        entries.filter { it.what.startsWith("🔴 JWT") && it.verdict == Verdict.ALLOWED }.forEach { e ->
            out += Finding(Severity.CRITICAL, I18n.t("find.forged"),
                I18n.t("find.forged.sum", e.what, e.method, e.url, e.statusCode), e.requestResponse)
        }

        return out.sortedBy { it.severity.ordinal }
    }
}
