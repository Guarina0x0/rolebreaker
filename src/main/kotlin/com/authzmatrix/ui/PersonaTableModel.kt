package com.authzmatrix.ui

import com.authzmatrix.core.I18n
import com.authzmatrix.core.Jwt
import com.authzmatrix.core.PersonaStore
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation
import javax.swing.table.AbstractTableModel

class PersonaTableModel(private val store: PersonaStore) : AbstractTableModel() {

    private val colKeys = arrayOf("col.on", "col.name", "col.anon", "col.location", "col.headerCookie", "col.token", "col.level")

    fun personaAt(row: Int): Persona? = store.personas.getOrNull(row)

    fun refresh() = fireTableDataChanged()

    /** Re-read headers (e.g. after a language change). */
    fun structureChanged() = fireTableStructureChanged()

    override fun getRowCount() = store.personas.size
    override fun getColumnCount() = colKeys.size
    override fun getColumnName(c: Int) = I18n.t(colKeys[c])

    override fun getColumnClass(c: Int): Class<*> = when (c) {
        0, 2 -> java.lang.Boolean::class.java
        3 -> TokenLocation::class.java
        else -> String::class.java
    }

    override fun isCellEditable(r: Int, c: Int): Boolean {
        if (c == TOKEN_COLUMN) return false
        // A cookie bundle is edited through the "Set cookies…" dialog, not this table.
        if (c == 4 && store.personas[r].usesCookies && store.personas[r].cookies.isNotEmpty()) return false
        return true
    }

    override fun getValueAt(r: Int, c: Int): Any {
        val p = store.personas[r]
        val cookies = synchronized(p) { LinkedHashMap(p.cookies) }
        return when (c) {
            0 -> p.enabled
            1 -> p.name
            2 -> p.anonymous
            3 -> p.location
            4 -> when {
                p.location == TokenLocation.COOKIE && cookies.isNotEmpty() -> cookies.keys.joinToString(", ")
                p.location == TokenLocation.COOKIE -> p.cookieName
                else -> p.headerName
            }
            5 -> tokenSummary(p, cookies)
            6 -> p.level.toString()
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, r: Int, c: Int) {
        val p = store.personas[r]
        when (c) {
            0 -> p.enabled = value as? Boolean ?: true
            1 -> p.name = value?.toString() ?: p.name
            2 -> p.anonymous = value as? Boolean ?: false
            3 -> p.location = value as? TokenLocation ?: p.location
            4 -> {
                val s = value?.toString() ?: ""
                if (p.location == TokenLocation.COOKIE) p.cookieName = s else p.headerName = s
            }
            6 -> p.level = value?.toString()?.trim()?.toIntOrNull() ?: p.level
        }
        // Enabled / name / location changes reshape the matrix columns.
        store.fireChanged()
        fireTableRowsUpdated(r, r)
    }

    private fun tokenSummary(p: Persona, cookies: Map<String, String>): String {
        if (p.anonymous) return I18n.t("persona.anon")
        if (p.usesCookies) {
            if (cookies.isEmpty() && p.token.isBlank()) return I18n.t("persona.noCookies")
            val count = if (cookies.isEmpty()) 1 else cookies.size
            val names = if (cookies.isEmpty()) p.cookieName else cookies.keys.joinToString(", ")
            return "${I18n.t("persona.cookies", count)} · $names"
        }
        if (p.token.isBlank()) return I18n.t("persona.noToken")
        val info = Jwt.parse(p.token)
        val flag = when {
            info == null -> I18n.t("persona.opaque")
            info.isExpired() -> "${I18n.t("persona.expired")} · ${info.summary()}"
            else -> info.summary()
        }
        val refresh = if (p.hasRefresh) "  ·  ${I18n.t("persona.refresh")}" else ""
        return "…${p.token.takeLast(8)}  ·  $flag$refresh"
    }

    companion object {
        /** Read-only cell: it shows a summary, so editing it would overwrite the real token. */
        const val TOKEN_COLUMN = 5
    }
}
