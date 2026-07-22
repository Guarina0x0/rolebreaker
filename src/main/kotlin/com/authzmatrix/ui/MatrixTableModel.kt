package com.authzmatrix.ui

import com.authzmatrix.core.PersonaStore
import com.authzmatrix.model.AccessResult
import com.authzmatrix.model.MatrixRow
import com.authzmatrix.model.Persona
import com.authzmatrix.model.Verdict
import javax.swing.table.AbstractTableModel

/**
 * Columns: [Request | Base] + one per enabled persona. Rebuilds its columns whenever the
 * persona set changes.
 */
class MatrixTableModel(private val store: PersonaStore) : AbstractTableModel() {

    private val rows = ArrayList<MatrixRow>()
    private var personaCols: List<Persona> = store.enabledPersonas()

    private val FIXED = 2 // Request, Base

    fun rebuildColumns() {
        personaCols = computeColumns()
        fireTableStructureChanged()
    }

    /** Columns = enabled personas ∪ any persona that has a result in some row (so a role tested
     *  explicitly — even if disabled — is never hidden from the matrix or exports). */
    private fun computeColumns(): List<Persona> {
        val idsWithResults = rows.flatMap { it.results.keys }.toSet()
        return store.personas.filter { it.enabled || it.id in idsWithResults }
    }

    fun addRow(row: MatrixRow) {
        rows += row
        // A newly tested persona (e.g. a disabled one chosen in "probar como…") needs a column.
        if (row.results.keys.any { id -> personaCols.none { it.id == id } }) {
            personaCols = computeColumns()
            fireTableStructureChanged()
        } else {
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }
    }

    fun clear() {
        val n = rows.size
        rows.clear()
        if (n > 0) fireTableRowsDeleted(0, n - 1)
    }

    fun rowAt(index: Int): MatrixRow? = rows.getOrNull(index)

    fun rowsSnapshot(): List<MatrixRow> = rows.toList()

    fun personaForColumn(col: Int): Persona? = personaCols.getOrNull(col - FIXED)

    fun resultAt(row: Int, col: Int): AccessResult? {
        val p = personaForColumn(col) ?: return null
        return rows.getOrNull(row)?.results?.get(p.id)
    }

    fun verdictAt(row: Int, col: Int): Verdict? = resultAt(row, col)?.verdict

    override fun getRowCount() = rows.size

    override fun getColumnCount() = FIXED + personaCols.size

    override fun getColumnName(col: Int): String = when (col) {
        0 -> "Request"
        1 -> "Base"
        else -> personaForColumn(col)?.name ?: "?"
    }

    override fun getValueAt(rowIndex: Int, col: Int): Any {
        val row = rows[rowIndex]
        return when (col) {
            0 -> row.label
            1 -> if (row.baselineStatus > 0) row.baselineStatus.toString() else "-"
            else -> resultAt(rowIndex, col)?.cellText() ?: "-"
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false

    // ---- export ---------------------------------------------------------------

    fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append(listOf("Request", "Base").plus(personaCols.map { it.name }).joinToString(",") { csv(it) })
        sb.append("\n")
        rows.forEach { row ->
            val cells = ArrayList<String>()
            cells += row.label
            cells += if (row.baselineStatus > 0) row.baselineStatus.toString() else "-"
            personaCols.forEach { p ->
                val res = row.results[p.id]
                cells += if (res == null) "-" else "${res.cellText()} ${res.verdict}"
            }
            sb.append(cells.joinToString(",") { csv(it) }).append("\n")
        }
        return sb.toString()
    }

    fun exportHtml(): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><style>")
        sb.append("table{border-collapse:collapse;font-family:sans-serif;font-size:13px}")
        sb.append("td,th{border:1px solid #999;padding:4px 8px}th{background:#eee}</style></head><body>")
        sb.append("<h2>AuthZ Matrix</h2><table><tr><th>Request</th><th>Base</th>")
        personaCols.forEach { sb.append("<th>").append(esc(it.name)).append("</th>") }
        sb.append("</tr>")
        rows.forEach { row ->
            sb.append("<tr><td>").append(esc(row.label)).append("</td>")
            sb.append("<td>").append(if (row.baselineStatus > 0) row.baselineStatus else "-").append("</td>")
            personaCols.forEach { p ->
                val res = row.results[p.id]
                if (res == null) {
                    sb.append("<td>-</td>")
                } else {
                    sb.append("<td style='background:").append(hex(res.verdict.background())).append("'>")
                        .append(esc(res.cellText())).append("<br><small>").append(res.verdict).append("</small></td>")
                }
            }
            sb.append("</tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"${s.replace("\"", "\"\"")}\"" else s

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun hex(c: java.awt.Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}
