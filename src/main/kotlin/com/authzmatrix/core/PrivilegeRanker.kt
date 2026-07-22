package com.authzmatrix.core

import com.authzmatrix.model.Persona

/**
 * Best-effort automatic privilege ranking by role name (ES + EN keywords). Higher score = more
 * privilege. It's a heuristic starting point — the user can still reorder with the dialog.
 */
object PrivilegeRanker {

    private val TIERS: List<Pair<Int, List<String>>> = listOf(
        100 to listOf("admin", "administrador", "administrator", "root", "super", "superuser",
            "owner", "dueño", "sysadmin", "backoffice"),
        60 to listOf("manager", "gestor", "staff", "operator", "operador", "editor", "moderator",
            "moderador", "supervisor", "support", "soporte"),
        30 to listOf("user", "usuario", "member", "miembro", "customer", "cliente", "client",
            "basic", "basico", "básico", "standard", "premium"),
        5 to listOf("guest", "invitado", "anon", "anonimo", "anónimo", "public", "publico",
            "público", "readonly", "read-only", "lectura", "viewer"),
    )

    /** Privilege score for a role name (higher = more privilege); unknown → mid (40). */
    fun score(roleName: String): Int {
        val n = roleName.lowercase()
        for ((s, kws) in TIERS) if (kws.any { n.contains(it) }) return s
        return 40
    }

    private fun effectiveScore(p: Persona): Int = if (p.anonymous) -1 else score(p.name)

    /** Assign levels (0,10,20…) most-privileged first, based on the name heuristic. */
    fun assignLevels(personas: List<Persona>) {
        personas.sortedByDescending { effectiveScore(it) }
            .forEachIndexed { i, p -> p.level = i * 10 }
    }
}
