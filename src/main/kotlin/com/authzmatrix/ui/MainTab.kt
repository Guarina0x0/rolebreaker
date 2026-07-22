package com.authzmatrix.ui

import burp.api.montoya.ui.editor.EditorOptions
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.I18n
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer

/**
 * The whole Suite tab. Personas + discovered-token catalog on the left, the access matrix on the
 * right, and a live activity log + request/response viewers at the bottom.
 */
class MainTab(private val ctx: AppContext) {

    private val matrixModel = MatrixTableModel(ctx.store)
    private val personaModel = PersonaTableModel(ctx.store)
    private val catalogModel = TokenCatalogModel(ctx.store)
    private val activityModel = ActivityTableModel()
    private val findingsModel = FindingsTableModel()

    private val matrixTable = JTable(matrixModel)
    private val personaTable = JTable(personaModel)
    private val catalogTable = JTable(catalogModel)
    private val activityTable = JTable(activityModel)
    private val findingsTable = JTable(findingsModel)

    /** Findings not derivable from matrix/activity (e.g. a cracked HMAC secret). */
    private val manualFindings = ArrayList<com.authzmatrix.model.Finding>()

    private val reqEditor = ctx.api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
    private val respEditor = ctx.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)

    private val root = JPanel(BorderLayout())

    init {
        wireListeners()
        buildUi()

        // Rebuild the whole tab when the language changes so every label/header updates.
        I18n.onChange { SwingUtilities.invokeLater { buildUi() } }

        ctx.rowSink = { row ->
            SwingUtilities.invokeLater {
                matrixModel.addRow(row)
                recomputeFindings()
            }
        }
        ctx.activitySink = { e ->
            SwingUtilities.invokeLater {
                activityModel.add(e)
                val last = activityTable.rowCount - 1
                if (last >= 0) activityTable.scrollRectToVisible(activityTable.getCellRect(last, 0, true))
                recomputeFindings()
            }
        }
        ctx.store.onChange {
            SwingUtilities.invokeLater {
                matrixModel.rebuildColumns()
                widenMatrix()
                personaModel.refresh()
                catalogModel.refresh()
            }
        }
    }

    fun component(): Component = root

    /** (Re)build the whole tab. Called at startup and whenever the language changes. */
    private fun buildUi() {
        root.removeAll()
        // Re-read column headers in the new language.
        matrixModel.rebuildColumns()
        personaModel.structureChanged()
        activityModel.structureChanged()
        findingsModel.structureChanged()
        catalogModel.structureChanged()
        buildToolbar()
        buildCenter()
        catalogModel.refresh()
        widenMatrix()
        root.revalidate()
        root.repaint()
    }

    // ---- toolbar --------------------------------------------------------------

    private fun buildToolbar() {
        val bar = javax.swing.JToolBar()
        bar.isFloatable = false
        bar.isRollover = true

        val auto = JCheckBox(I18n.t("tb.autoAll"), ctx.autoMode.get())
        auto.toolTipText = I18n.t("tb.autoAll.tip")
        val lowerCont = JCheckBox(I18n.t("tb.lowerCont"), ctx.autoLowerMode.get())
        lowerCont.toolTipText = I18n.t("tb.lowerCont.tip")
        // Mutually exclusive: enabling one disables the other (avoids double-testing each request).
        auto.addActionListener {
            ctx.autoMode.set(auto.isSelected)
            if (auto.isSelected) { lowerCont.isSelected = false; ctx.autoLowerMode.set(false); ctx.resetAutoSeen() }
        }
        lowerCont.addActionListener {
            ctx.autoLowerMode.set(lowerCont.isSelected)
            if (lowerCont.isSelected) { auto.isSelected = false; ctx.autoMode.set(false); ctx.resetAutoSeen() }
        }
        val capture = JCheckBox(I18n.t("tb.capture"), ctx.capture.enabled)
        capture.addActionListener { ctx.capture.enabled = capture.isSelected }

        val clearResults = JButton(I18n.t("tb.clearMatrix"))
        clearResults.addActionListener { matrixModel.clear() }
        val clearActivity = JButton(I18n.t("tb.clearActivity"))
        clearActivity.addActionListener { activityModel.clear() }
        val exportCsv = JButton(I18n.t("tb.exportCsv"))
        exportCsv.addActionListener { export("csv") }
        val exportHtml = JButton(I18n.t("tb.exportHtml"))
        exportHtml.addActionListener { export("html") }

        val sweep = JButton(I18n.t("tb.sweep"))
        sweep.toolTipText = I18n.t("tb.sweep.tip")
        sweep.addActionListener { autoSweep() }

        // Grouped: Auto · Mode · View · Export · Language
        bar.add(JLabel(I18n.t("tb.auto")))
        bar.add(sweep)
        bar.addSeparator()
        bar.add(JLabel(I18n.t("tb.mode")))
        bar.add(auto); bar.add(lowerCont); bar.add(capture)
        bar.addSeparator()
        bar.add(JLabel(I18n.t("tb.view")))
        bar.add(clearResults); bar.add(clearActivity)
        bar.addSeparator()
        bar.add(JLabel(I18n.t("tb.export")))
        bar.add(exportCsv); bar.add(exportHtml)
        bar.addSeparator()
        bar.add(JLabel(I18n.t("tb.lang")))
        bar.add(buildLangCombo())
        root.add(bar, BorderLayout.NORTH)
    }

    private fun buildLangCombo(): JComboBox<LangItem> {
        val items = arrayOf(LangItem(I18n.Lang.EN, "English"), LangItem(I18n.Lang.ES, "Español"))
        val combo = JComboBox(items)
        combo.selectedItem = items.first { it.lang == I18n.lang }
        combo.maximumSize = combo.preferredSize
        combo.addActionListener {
            val sel = (combo.selectedItem as? LangItem)?.lang ?: return@addActionListener
            if (sel != I18n.lang) {
                ctx.persistLang(sel)
                I18n.setLang(sel) // triggers buildUi()
            }
        }
        return combo
    }

    private class LangItem(val lang: I18n.Lang, val label: String) {
        override fun toString() = label
    }

    // ---- center ---------------------------------------------------------------

    private fun buildCenter() {
        matrixTable.setDefaultRenderer(Any::class.java, ColorRenderer { row, col ->
            if (col >= 2) matrixModel.verdictAt(row, col)?.background() else null
        })
        matrixTable.cellSelectionEnabled = true
        matrixTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        matrixTable.rowHeight = 22
        matrixTable.componentPopupMenu = buildMatrixMenu()
        val matrixScroll = JScrollPane(matrixTable)
        matrixScroll.border = BorderFactory.createTitledBorder(I18n.t("center.matrix"))

        val topSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildConfigPanel(), matrixScroll)
        topSplit.resizeWeight = 0.45

        // Activity log (verdict-coloured).
        activityTable.setDefaultRenderer(Any::class.java, ColorRenderer { row, col ->
            if (col == 6) activityModel.verdictAt(row)?.background() else null
        })
        activityTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(activityTable, intArrayOf(70, 170, 60, 380, 60, 70, 120))
        val activityScroll = JScrollPane(activityTable)

        // Findings (severity-coloured).
        findingsTable.setDefaultRenderer(Any::class.java, ColorRenderer { row, col ->
            if (col == 0) findingsModel.severityAt(row)?.background() else null
        })
        findingsTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(findingsTable, intArrayOf(90, 190, 560))
        val findingsScroll = JScrollPane(findingsTable)

        val bottomTabs = javax.swing.JTabbedPane()
        bottomTabs.addTab(I18n.t("center.activity"), activityScroll)
        bottomTabs.addTab(I18n.t("center.findings"), findingsScroll)

        val detail = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            titled(reqEditor.uiComponent(), I18n.t("center.request")),
            titled(respEditor.uiComponent(), I18n.t("center.response")),
        )
        detail.resizeWeight = 0.5

        val bottom = JSplitPane(JSplitPane.VERTICAL_SPLIT, bottomTabs, detail)
        bottom.resizeWeight = 0.4

        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, bottom)
        mainSplit.resizeWeight = 0.5
        root.add(mainSplit, BorderLayout.CENTER)
    }

    private fun buildMatrixMenu(): JPopupMenu {
        val menu = JPopupMenu()
        val toRepeater = JMenuItem(I18n.t("menu.toRepeater"))
        toRepeater.addActionListener {
            matrixModel.rowAt(matrixTable.selectedRow)?.baseline?.request()?.let { ctx.api.repeater().sendToRepeater(it) }
        }
        menu.add(toRepeater)
        return menu
    }

    private fun buildConfigPanel(): JComponent {
        // Personas.
        personaTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        personaTable.getColumnModel().getColumn(3).cellEditor = DefaultCellEditor(JComboBox(TokenLocation.values()))
        personaTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(personaTable, intArrayOf(34, 100, 44, 120, 110, 230, 55))
        val personaScroll = JScrollPane(personaTable)
        personaScroll.border = BorderFactory.createTitledBorder(I18n.t("cfg.personas"))
        val personaBtns = JPanel(FlowLayout(FlowLayout.LEFT))
        val add = JButton(I18n.t("btn.add")); add.addActionListener { ctx.store.addPersona(Persona(name = "role${ctx.store.personas.size + 1}")) }
        val remove = JButton(I18n.t("btn.remove")); remove.addActionListener {
            personaModel.personaAt(personaTable.selectedRow)?.let { ctx.store.removePersona(it) }
        }
        val refreshExpired = JButton(I18n.t("btn.refreshExpired"))
        refreshExpired.toolTipText = I18n.t("btn.refreshExpired.tip")
        refreshExpired.addActionListener { ctx.refreshAllExpired() }
        val clearPersonas = JButton(I18n.t("btn.clearPersonas"))
        clearPersonas.toolTipText = I18n.t("btn.clearPersonas.tip")
        clearPersonas.addActionListener {
            if (JOptionPane.showConfirmDialog(root, I18n.t("msg.confirmClearPersonas"), I18n.t("dlg.title"),
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                ctx.store.clearPersonas()
            }
        }
        val order = JButton(I18n.t("btn.order"))
        order.toolTipText = I18n.t("btn.order.tip")
        order.addActionListener { PrivilegeOrderDialog.open(ctx) }
        personaBtns.add(add); personaBtns.add(remove); personaBtns.add(refreshExpired)
        personaBtns.add(clearPersonas); personaBtns.add(order)
        val personaBox = JPanel(BorderLayout())
        personaBox.add(personaScroll, BorderLayout.CENTER)
        personaBox.add(personaBtns, BorderLayout.SOUTH)

        // Token catalog.
        catalogTable.selectionMode(ListSelectionModel.SINGLE_SELECTION)
        catalogTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(catalogTable, intArrayOf(90, 150, 150, 60, 160, 230, 220))
        val catalogScroll = JScrollPane(catalogTable)
        catalogScroll.border = BorderFactory.createTitledBorder(I18n.t("cfg.catalog"))
        val catalogBtns = JPanel(FlowLayout(FlowLayout.LEFT))
        val scan = JButton(I18n.t("btn.scan")); scan.addActionListener { scanHistory() }
        val sync = JButton(I18n.t("btn.sync")); sync.addActionListener { syncPersonas() }
        val promote = JButton(I18n.t("btn.promote")); promote.addActionListener { promoteToken() }
        val crack = JButton(I18n.t("btn.crack")); crack.addActionListener { crackSelected() }
        crack.toolTipText = I18n.t("btn.crack.tip")
        val clearCat = JButton(I18n.t("btn.clear")); clearCat.addActionListener { ctx.store.clearCaptured() }
        listOf(scan, sync, promote, crack, clearCat).forEach { catalogBtns.add(it) }
        val catalogBox = JPanel(BorderLayout())
        catalogBox.add(catalogScroll, BorderLayout.CENTER)
        catalogBox.add(catalogBtns, BorderLayout.SOUTH)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, personaBox, catalogBox)
        split.resizeWeight = 0.5
        return split
    }

    private fun JTable.selectionMode(mode: Int) { selectionModel.selectionMode = mode }

    private fun applyWidths(table: JTable, widths: IntArray) {
        val cm = table.columnModel
        for (i in 0 until minOf(widths.size, cm.columnCount)) cm.getColumn(i).preferredWidth = widths[i]
    }

    private fun widenMatrix() {
        val cm = matrixTable.columnModel
        if (cm.columnCount > 0) cm.getColumn(0).preferredWidth = 300
        if (cm.columnCount > 1) cm.getColumn(1).preferredWidth = 50
        for (i in 2 until cm.columnCount) cm.getColumn(i).preferredWidth = 95
    }

    private fun titled(c: Component, title: String): JScrollPane =
        JScrollPane(c).apply { border = BorderFactory.createTitledBorder(title) }

    // ---- behaviour ------------------------------------------------------------

    /** Selection listeners attach to the persistent table objects — registered once. */
    private fun wireListeners() {
        matrixTable.selectionModel.addListSelectionListener { showMatrixCell() }
        matrixTable.columnModel.selectionModel.addListSelectionListener { showMatrixCell() }
        activityTable.selectionModel.addListSelectionListener {
            activityModel.entryAt(activityTable.selectedRow)?.requestResponse?.let { show(it) }
        }
        findingsTable.selectionModel.addListSelectionListener {
            findingsModel.findingAt(findingsTable.selectedRow)?.requestResponse?.let { show(it) }
        }
    }

    private fun showMatrixCell() {
        val row = matrixTable.selectedRow
        val col = matrixTable.selectedColumn
        if (row < 0 || col < 0) return
        val rr = if (col >= 2) matrixModel.resultAt(row, col)?.requestResponse else matrixModel.rowAt(row)?.baseline
        rr?.let { show(it) }
    }

    private fun show(rr: burp.api.montoya.http.message.HttpRequestResponse) {
        rr.request()?.let { reqEditor.request = it }
        rr.response()?.let { respEditor.response = it }
    }

    private fun recomputeFindings() {
        val derived = com.authzmatrix.core.FindingsAnalyzer.analyze(
            matrixModel.rowsSnapshot(), activityModel.entriesSnapshot(), ctx.store,
        )
        findingsModel.set((derived + manualFindings).sortedBy { it.severity.ordinal })
    }

    /** Run [body] on a background thread with try/catch (Burp doesn't catch bg-thread exceptions). */
    private fun bg(name: String, body: () -> Unit) {
        Thread({
            try {
                body()
            } catch (e: Throwable) {
                ctx.api.logging().logToError("RoleBreaker: background task '$name' failed: ${e.message}")
            }
        }, name).start()
    }

    private fun autoSweep() {
        val input = JOptionPane.showInputDialog(root, I18n.t("sweep.prompt"), "20") ?: return
        val minutes = input.trim().toLongOrNull()?.coerceAtLeast(1) ?: 20L
        bg("rolebreaker-auto-sweep") {
            ctx.historyScanner.scan(minutes, true)
            ctx.store.syncPersonasFromTokens()
            com.authzmatrix.core.PrivilegeRanker.assignLevels(ctx.store.personas)
            ctx.store.fireChanged() // persist levels + refresh UI
            val reqs = ctx.historyScanner.recentRequests(minutes)
            SwingUtilities.invokeLater {
                catalogModel.refresh()
                val roles = ctx.store.personas.filter { !it.anonymous }
                if (roles.isEmpty()) {
                    JOptionPane.showMessageDialog(root, I18n.t("sweep.noRoles", minutes),
                        I18n.t("sweep.title"), JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                if (reqs.isEmpty()) {
                    JOptionPane.showMessageDialog(root, I18n.t("sweep.noReqs", minutes),
                        I18n.t("sweep.title"), JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                com.authzmatrix.ui.AutoLowerPrivTester.run(ctx, reqs)
                val order = ctx.store.personas.sortedBy { it.level }
                    .joinToString(" > ") { "${it.name}(${it.level})" }
                JOptionPane.showMessageDialog(root, I18n.t("sweep.launched", reqs.size, minutes, order),
                    I18n.t("sweep.title"), JOptionPane.INFORMATION_MESSAGE)
            }
        }
    }

    private fun scanHistory() {
        val input = JOptionPane.showInputDialog(root, I18n.t("scan.prompt"), "60") ?: return
        val minutes = input.trim().toLongOrNull()?.coerceAtLeast(1) ?: 60L
        bg("rolebreaker-history-scan") {
            val n = ctx.historyScanner.scan(minutes, true)
            val created = ctx.store.syncPersonasFromTokens()
            SwingUtilities.invokeLater {
                catalogModel.refresh()
                JOptionPane.showMessageDialog(root, I18n.t("scan.result", n, minutes, created),
                    I18n.t("dlg.title"), JOptionPane.INFORMATION_MESSAGE)
                offerPrivilegeOrder()
            }
        }
    }

    private fun syncPersonas() {
        val created = ctx.store.syncPersonasFromTokens()
        JOptionPane.showMessageDialog(root, I18n.t("sync.result", created, ctx.store.personas.size),
            I18n.t("dlg.title"), JOptionPane.INFORMATION_MESSAGE)
        offerPrivilegeOrder()
    }

    private fun offerPrivilegeOrder() {
        if (ctx.store.personas.size >= 2 &&
            JOptionPane.showConfirmDialog(root, I18n.t("order.offer"),
                I18n.t("dlg.title"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
        ) {
            PrivilegeOrderDialog.open(ctx)
        }
    }

    private fun promoteToken() {
        val t = catalogModel.tokenAt(catalogTable.selectedRow) ?: return
        val name = t.info?.role ?: t.info?.owner ?: "token"
        ctx.store.addPersona(Persona(name = name, token = t.token, ownerSub = t.info?.sub))
    }

    private fun crackSelected() {
        val t = catalogModel.tokenAt(catalogTable.selectedRow)
        if (t == null) {
            JOptionPane.showMessageDialog(root, I18n.t("crack.selectToken"), I18n.t("crack.title"),
                JOptionPane.WARNING_MESSAGE)
            return
        }
        if (!com.authzmatrix.core.JwtCracker.isHmac(t.info?.alg)) {
            JOptionPane.showMessageDialog(root, I18n.t("crack.onlyHmac", t.info?.alg ?: "?"),
                I18n.t("crack.title"), JOptionPane.INFORMATION_MESSAGE)
            return
        }
        bg("rolebreaker-jwt-crack") {
            val hit = com.authzmatrix.core.JwtCracker.crack(t.token, com.authzmatrix.core.JwtCracker.COMMON_SECRETS)
            SwingUtilities.invokeLater { reportCrack(t.token, hit, I18n.t("crack.commonList"), offerWordlist = true) }
        }
    }

    private fun reportCrack(token: String, hit: String?, source: String, offerWordlist: Boolean) {
        if (hit != null) {
            ctx.api.logging().logToOutput(I18n.t("crack.weakLog", source, hit))
            manualFindings += com.authzmatrix.model.Finding(
                com.authzmatrix.model.Severity.CRITICAL, I18n.t("finding.weakSecret.type"),
                I18n.t("finding.weakSecret.summary", source, hit), null,
            )
            recomputeFindings()
            JOptionPane.showMessageDialog(root, I18n.t("crack.weakDialog", hit),
                I18n.t("crack.weakTitle"), JOptionPane.WARNING_MESSAGE)
            return
        }
        if (!offerWordlist) {
            JOptionPane.showMessageDialog(root, I18n.t("crack.notCracked", source), I18n.t("crack.title"),
                JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val loadCustom = JOptionPane.showConfirmDialog(root, I18n.t("crack.tryWordlist", source),
            I18n.t("crack.title"), JOptionPane.YES_NO_OPTION)
        if (loadCustom != JOptionPane.YES_OPTION) return
        val fc = JFileChooser()
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return
        val file = fc.selectedFile
        bg("rolebreaker-jwt-crack-wl") {
            val words = try {
                java.nio.file.Files.readAllLines(file.toPath()).map { it.trim() }.filter { it.isNotEmpty() }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(root, I18n.t("crack.wordlistErr", ex.message ?: ""), I18n.t("crack.title"),
                        JOptionPane.ERROR_MESSAGE)
                }
                return@bg
            }
            val hit2 = com.authzmatrix.core.JwtCracker.crack(token, words)
            SwingUtilities.invokeLater { reportCrack(token, hit2, I18n.t("crack.wordlist", words.size), offerWordlist = false) }
        }
    }

    private fun export(kind: String) {
        val fc = JFileChooser()
        fc.selectedFile = File("rolebreaker-matrix.$kind")
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return
        val text = if (kind == "csv") matrixModel.exportCsv() else matrixModel.exportHtml()
        try {
            java.nio.file.Files.writeString(fc.selectedFile.toPath(), text)
            ctx.api.logging().logToOutput("RoleBreaker: exported ${fc.selectedFile}")
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(root, I18n.t("export.error", ex.message ?: ""), "Export", JOptionPane.ERROR_MESSAGE)
        }
    }

    // ---- renderer -------------------------------------------------------------

    /** Colours a cell by the (light pastel) colour returned by [colorOf] (null = default). */
    private class ColorRenderer(
        private val colorOf: (row: Int, col: Int) -> Color?,
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val color = colorOf(row, column)
            when {
                isSelected -> {
                    background = table.selectionBackground
                    foreground = table.selectionForeground
                }
                color != null -> {
                    background = color
                    foreground = Color(0x21, 0x21, 0x21) // dark text on pastel → readable on any theme
                }
                else -> {
                    background = table.background
                    foreground = table.foreground
                }
            }
            return c
        }
    }
}
