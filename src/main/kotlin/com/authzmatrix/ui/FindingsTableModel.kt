package com.authzmatrix.ui

import com.authzmatrix.core.I18n
import com.authzmatrix.model.Finding
import com.authzmatrix.model.Severity
import javax.swing.table.AbstractTableModel

/** The "Findings" view: only the interesting, ranked results. */
class FindingsTableModel : AbstractTableModel() {

    private val items = ArrayList<Finding>()
    private val colKeys = arrayOf("col.severity", "col.type", "col.detail")

    fun set(list: List<Finding>) {
        items.clear()
        items.addAll(list)
        fireTableDataChanged()
    }

    fun findingAt(i: Int): Finding? = items.getOrNull(i)
    fun severityAt(i: Int): Severity? = items.getOrNull(i)?.severity

    /** Re-read headers (e.g. after a language change). */
    fun structureChanged() = fireTableStructureChanged()

    override fun getRowCount() = items.size
    override fun getColumnCount() = colKeys.size
    override fun getColumnName(c: Int) = I18n.t(colKeys[c])
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getValueAt(r: Int, c: Int): Any {
        val f = items[r]
        return when (c) {
            0 -> f.severity.name
            1 -> f.type
            2 -> f.summary
            else -> ""
        }
    }
}
