package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.DashboardModule
import com.authzmatrix.core.I18n
import com.authzmatrix.core.ModuleStatus
import com.authzmatrix.core.PayloadLibrary
import com.authzmatrix.core.PayloadLibrary.Payload
import com.authzmatrix.core.StatChip
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * A browsable, offline arsenal of common test payloads (XSS, SSTI, SQLi, WAF bypasses, …) with
 * category filter, free-text search and copy-to-clipboard, plus a persisted "Custom" bucket — so
 * you don't have to dig through GitHub mid-engagement. Data lives in [PayloadLibrary]; custom
 * entries persist via Burp preferences.
 */
class PayloadsModule(private val ctx: AppContext) : DashboardModule {

    private val root = JPanel(BorderLayout())
    private val search = JTextField()
    private val tableModel = PayloadTableModel()
    private val table = JTable(tableModel)
    private val status = JLabel(" ")
    private val categoryList = JList<String>()

    private val custom = ArrayList<Payload>()

    init {
        loadCustom()
        wireListeners()   // once — persistent components keep a single set of listeners
        build()           // layout (re-callable on language change without duplicating listeners)
        applyFilter()
        I18n.onChange { SwingUtilities.invokeLater { build(); applyFilter() } }
    }

    override val id = "payloads"
    override fun title() = I18n.t("mod.payloads")
    override fun icon() = "🧨"
    override fun component(): JComponent = root

    override fun status() = ModuleStatus(
        chips = listOf(
            StatChip(I18n.t("home.payloads"), (PayloadLibrary.total + custom.size).toString()),
            StatChip(I18n.t("home.categories"), (PayloadLibrary.categories.size + 1).toString()),
        ),
    )

    // ---- layout ---------------------------------------------------------------

    private fun categoryNames(): Array<String> =
        (listOf(I18n.t("pl.all")) + PayloadLibrary.categories.keys + I18n.t("pl.custom")).toTypedArray()

    private fun build() {
        root.removeAll()

        val keepSel = categoryList.selectedValue
        categoryList.setListData(categoryNames())
        categoryList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        categoryList.setSelectedValue(keepSel ?: I18n.t("pl.all"), true)
        if (categoryList.selectedIndex < 0) categoryList.selectedIndex = 0
        val catScroll = JScrollPane(categoryList)
        catScroll.border = BorderFactory.createTitledBorder(I18n.t("pl.categories"))

        search.toolTipText = I18n.t("pl.search")

        tableModel.structureChanged() // re-read localized column headers
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.rowHeight = 22
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        val cols = table.columnModel
        cols.getColumn(0).preferredWidth = 170
        cols.getColumn(1).preferredWidth = 440
        cols.getColumn(2).preferredWidth = 320

        val searchRow = JPanel(BorderLayout(6, 0))
        searchRow.border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        searchRow.add(JLabel("🔎"), BorderLayout.WEST)
        searchRow.add(search, BorderLayout.CENTER)

        val right = JPanel(BorderLayout())
        right.add(searchRow, BorderLayout.NORTH)
        right.add(JScrollPane(table), BorderLayout.CENTER)
        right.add(buttonBar(), BorderLayout.SOUTH)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, catScroll, right)
        split.resizeWeight = 0.22
        root.add(split, BorderLayout.CENTER)
        root.revalidate(); root.repaint()
    }

    private fun buttonBar(): JComponent {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT))
        val copy = JButton(I18n.t("pl.copy")); copy.addActionListener { copySelected() }
        val add = JButton(I18n.t("pl.add")); add.addActionListener { addCustom() }
        val remove = JButton(I18n.t("pl.remove")); remove.addActionListener { removeSelectedCustom() }
        bar.add(copy); bar.add(add); bar.add(remove); bar.add(status)
        return bar
    }

    /** Listeners on the persistent field components — attached exactly once. */
    private fun wireListeners() {
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        categoryList.addListSelectionListener { if (!it.valueIsAdjusting) applyFilter() }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybePopup(e)
            override fun mouseReleased(e: MouseEvent) = maybePopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !e.isPopupTrigger) { selectRowAt(e); rowMenu().show(table, e.x, e.y) }
            }
        })
    }

    private fun maybePopup(e: MouseEvent) {
        if (e.isPopupTrigger) { selectRowAt(e); rowMenu().show(table, e.x, e.y) }
    }

    private fun selectRowAt(e: MouseEvent) {
        val r = table.rowAtPoint(e.point)
        if (r >= 0) table.setRowSelectionInterval(r, r)
    }

    /** Right-click / double-click menu (rebuilt each time so labels track the language). */
    private fun rowMenu(): JPopupMenu {
        val menu = JPopupMenu()
        val copy = JMenuItem(I18n.t("pl.copy")); copy.addActionListener { copySelected(withNote = false) }
        val copyNote = JMenuItem(I18n.t("pl.copyNote")); copyNote.addActionListener { copySelected(withNote = true) }
        val copyAll = JMenuItem(I18n.t("pl.copyAll")); copyAll.addActionListener { copyAllShown() }
        menu.add(copy); menu.add(copyNote); menu.addSeparator(); menu.add(copyAll)
        return menu
    }

    // ---- behaviour ------------------------------------------------------------

    private fun allRows(): List<Pair<String, Payload>> {
        val builtin = PayloadLibrary.categories.flatMap { (cat, list) -> list.map { cat to it } }
        val customCat = I18n.t("pl.custom")
        return builtin + custom.map { customCat to it }
    }

    private fun applyFilter() {
        val selectedCat = categoryList.selectedValue
        val all = I18n.t("pl.all")
        val q = search.text.trim().lowercase()
        val rows = allRows().filter { (cat, p) ->
            (selectedCat == null || selectedCat == all || selectedCat == cat) &&
                (q.isEmpty() || p.value.lowercase().contains(q) || p.note.lowercase().contains(q) ||
                    cat.lowercase().contains(q))
        }
        tableModel.set(rows)
    }

    private fun copySelected(withNote: Boolean = false) {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val value = tableModel.valueAt(row) ?: return
        val note = tableModel.noteAt(row).orEmpty()
        val text = if (withNote && note.isNotBlank()) "$value\t# $note" else value
        toClipboard(text)
        val shown = if (value.length > 60) value.take(57) + "…" else value
        status.text = I18n.t("pl.copied", shown)
    }

    private fun copyAllShown() {
        val all = tableModel.allValues()
        if (all.isEmpty()) return
        toClipboard(all.joinToString("\n"))
        status.text = I18n.t("pl.copiedAll", all.size)
    }

    private fun toClipboard(text: String) =
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

    private fun addCustom() {
        val value = JOptionPane.showInputDialog(ctx.uiFrame(), I18n.t("pl.addValue"),
            I18n.t("pl.addTitle"), JOptionPane.PLAIN_MESSAGE) ?: return
        if (value.isBlank()) return
        val note = JOptionPane.showInputDialog(ctx.uiFrame(), I18n.t("pl.addNote"),
            I18n.t("pl.addTitle"), JOptionPane.PLAIN_MESSAGE) ?: ""
        custom.add(Payload(value.trim(), note.trim()))
        saveCustom()
        categoryList.setListData(categoryNames())
        categoryList.setSelectedValue(I18n.t("pl.custom"), true)
        applyFilter()
        ctx.store.fireChanged() // refresh Home card counts
    }

    private fun removeSelectedCustom() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        if (tableModel.categoryAt(row) != I18n.t("pl.custom")) {
            status.text = I18n.t("pl.onlyCustom")
            return
        }
        val value = tableModel.valueAt(row)
        custom.removeAll { it.value == value }
        saveCustom()
        applyFilter()
        ctx.store.fireChanged()
    }

    // ---- persistence ----------------------------------------------------------

    private fun loadCustom() {
        val raw = ctx.api.persistence().preferences().getString(CUSTOM_KEY) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                custom.add(Payload(o.optString("value"), o.optString("note")))
            }
        }
    }

    private fun saveCustom() {
        val arr = JSONArray()
        custom.forEach { arr.put(JSONObject().put("value", it.value).put("note", it.note)) }
        ctx.api.persistence().preferences().setString(CUSTOM_KEY, arr.toString())
    }

    private companion object {
        const val CUSTOM_KEY = "rolebreaker.payloads.custom"
    }

    // ---- table model ----------------------------------------------------------

    private class PayloadTableModel : AbstractTableModel() {
        private val rows = ArrayList<Pair<String, Payload>>()
        private val colKeys = arrayOf("col.category", "col.payload", "col.note")

        fun set(list: List<Pair<String, Payload>>) { rows.clear(); rows.addAll(list); fireTableDataChanged() }
        fun valueAt(r: Int): String? = rows.getOrNull(r)?.second?.value
        fun noteAt(r: Int): String? = rows.getOrNull(r)?.second?.note
        fun categoryAt(r: Int): String? = rows.getOrNull(r)?.first
        fun allValues(): List<String> = rows.map { it.second.value }
        fun structureChanged() = fireTableStructureChanged()

        override fun getRowCount() = rows.size
        override fun getColumnCount() = colKeys.size
        override fun getColumnName(c: Int) = I18n.t(colKeys[c])
        override fun isCellEditable(r: Int, c: Int) = false
        override fun getValueAt(r: Int, c: Int): Any {
            val (cat, p) = rows[r]
            return when (c) { 0 -> cat; 1 -> p.value; else -> p.note }
        }
    }
}
