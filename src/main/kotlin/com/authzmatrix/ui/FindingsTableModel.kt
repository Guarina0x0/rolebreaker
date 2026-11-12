package com.authzmatrix.ui

import com.authzmatrix.core.I18n
import com.authzmatrix.model.Finding
import com.authzmatrix.model.Severity
import javax.swing.table.AbstractTableModel

/**
 * The "Findings" view: only the interesting, ranked results. Keeps the full list in [items] and
 * exposes a filtered view in [visible] so search/severity filters don't discard data used by the
 * Home dashboard counters.
 */
class FindingsTableModel : AbstractTableModel() {

    private val items = ArrayList<Finding>()
    private val visible = ArrayList<Finding>()
    private val colKeys = arrayOf("col.severity", "col.type", "col.detail")

    private var query: String = ""
    private var severity: Severity? = null

    fun set(list: List<Finding>) {
        items.clear()
        items.addAll(list)
        applyFilter()
    }

    /** Apply a case-insensitive free-text filter and an optional severity filter. */
    fun setFilter(query: String, severity: Severity?) {
        this.query = query.trim()
        this.severity = severity
        applyFilter()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        visible.clear()
        visible.addAll(
            items.filter { f ->
                (severity == null || f.severity == severity) &&
                    (q.isEmpty() || searchable(f).any { it.lowercase().contains(q) })
            },
        )
        fireTableDataChanged()
    }

    private fun searchable(f: Finding): List<String> = listOf(
        f.severity.name,
        f.type,
        f.summary,
        f.row?.method.orEmpty(),
        f.row?.url.orEmpty(),
        f.result?.statusCode?.toString().orEmpty(),
    )

    fun findingAt(i: Int): Finding? = visible.getOrNull(i)
    fun severityAt(i: Int): Severity? = findingAt(i)?.severity

    /** All findings, independent of the active filter (used by the Home dashboard). */
    fun all(): List<Finding> = items.toList()

    /** Findings currently shown after applying the filter. */
    fun visible(): List<Finding> = visible.toList()

    /** Index of an equivalent finding in the visible list, or -1. */
    fun indexOf(f: Finding): Int = visible.indexOfFirst {
        it.severity == f.severity && it.type == f.type && it.summary == f.summary
    }

    /** Re-read headers (e.g. after a language change). */
    fun structureChanged() = fireTableStructureChanged()

    override fun getRowCount() = visible.size
    override fun getColumnCount() = colKeys.size
    override fun getColumnName(c: Int) = I18n.t(colKeys[c])
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getValueAt(r: Int, c: Int): Any {
        val f = visible[r]
        return when (c) {
            0 -> f.severity.name
            1 -> f.type
            2 -> f.summary
            else -> ""
        }
    }
}
