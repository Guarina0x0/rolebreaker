package com.authzmatrix.ui

import com.authzmatrix.model.ActivityEntry
import com.authzmatrix.model.Verdict
import javax.swing.table.AbstractTableModel

/** Live log of every request the extension launches. */
class ActivityTableModel : AbstractTableModel() {

    private val rows = ArrayList<ActivityEntry>()
    private val cols = arrayOf("Hora", "Qué", "Método", "URL", "Status", "Bytes", "Veredicto")

    fun add(e: ActivityEntry) {
        rows += e
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun clear() {
        val n = rows.size
        rows.clear()
        if (n > 0) fireTableRowsDeleted(0, n - 1)
    }

    fun entryAt(i: Int): ActivityEntry? = rows.getOrNull(i)

    fun entriesSnapshot(): List<ActivityEntry> = rows.toList()

    fun verdictAt(i: Int): Verdict? = rows.getOrNull(i)?.verdict

    override fun getRowCount() = rows.size
    override fun getColumnCount() = cols.size
    override fun getColumnName(c: Int) = cols[c]
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getValueAt(r: Int, c: Int): Any {
        val e = rows[r]
        return when (c) {
            0 -> e.time
            1 -> e.what
            2 -> e.method
            3 -> e.url
            4 -> if (e.statusCode > 0) e.statusCode.toString() else "ERR"
            5 -> e.bodyLength.toString()
            6 -> e.verdict?.name ?: ""
            else -> ""
        }
    }
}
