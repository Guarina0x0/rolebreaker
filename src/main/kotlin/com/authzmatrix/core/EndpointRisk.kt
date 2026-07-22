package com.authzmatrix.core

import com.authzmatrix.model.Severity

/** Heuristic risk of an endpoint by its shape (privileged path, ids, IDOR-ish params). */
object EndpointRisk {

    private val PRIVILEGED = Regex("""/(admin|internal|manage|management|console|config|debug|actuator)(/|$)""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ID = Regex("""/\d{2,}(/|$|\?)""")
    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val IDOR_PARAM = Regex("""[?&](id|user_?id|account(_?id)?|uid|pid|customer(_?id)?|order(_?id)?)=""", RegexOption.IGNORE_CASE)
    private val NUMERIC_PARAM = Regex("""[?&][a-z_]+=\d{2,}""", RegexOption.IGNORE_CASE)

    /** null = nothing notable about the endpoint. */
    fun severity(url: String): Severity? = when {
        PRIVILEGED.containsMatchIn(url) -> Severity.HIGH
        NUMERIC_ID.containsMatchIn(url) -> Severity.HIGH
        UUID.containsMatchIn(url) -> Severity.HIGH
        IDOR_PARAM.containsMatchIn(url) -> Severity.HIGH
        NUMERIC_PARAM.containsMatchIn(url) -> Severity.MEDIUM
        else -> null
    }

    /** The more severe of two severities (lower ordinal wins); null-safe. */
    fun escalate(base: Severity, endpoint: Severity?): Severity =
        if (endpoint != null && endpoint.ordinal < base.ordinal) endpoint else base
}
