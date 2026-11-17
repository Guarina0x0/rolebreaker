package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.editor.EditorOptions
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.CookieHeader
import com.authzmatrix.core.CurlBuilder
import com.authzmatrix.core.FindingsExport
import com.authzmatrix.core.I18n
import com.authzmatrix.core.Jwt
import com.authzmatrix.core.SweepProgress
import com.authzmatrix.core.SweepToken
import com.authzmatrix.model.Persona
import com.authzmatrix.model.Severity
import com.authzmatrix.model.TokenLocation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
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
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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

    private val findingsFilter = JTextField()
    private val findingsSeverity = JComboBox<SeverityFilter>()
    private val findingsCount = JLabel(" ")
    private val findingsTimer = javax.swing.Timer(250) { recomputeFindings() }.apply { isRepeats = false }
    private var updatingFindingsFilter = false

    // Sweep progress bar (hidden when idle).
    private val sweepBar = JPanel(BorderLayout(8, 0))
    private val sweepProgress = JProgressBar()
    private val sweepStatus = JLabel(" ")
    private val sweepCancel = JButton()
    private val sweepHideTimer = javax.swing.Timer(5_000) { sweepBar.isVisible = false }.apply { isRepeats = false }
    private var activeSweep: SweepToken? = null
    private var includeUnsafeCheck: JCheckBox? = null

    /** Findings not derivable from matrix/activity (e.g. a cracked HMAC secret). */
    private val manualFindings = ArrayList<com.authzmatrix.model.Finding>()

    private val reqEditor = ctx.api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
    private val respEditor = ctx.api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)

    private val root = JPanel(BorderLayout())

    init {
        configureSweepBar()
        wireListeners()
        buildUi()

        // Rebuild the whole tab when the language changes so every label/header updates.
        I18n.onChange { SwingUtilities.invokeLater { buildUi() } }

        ctx.rowSink = { row ->
            SwingUtilities.invokeLater {
                matrixModel.addRow(row)
                scheduleFindingsRecompute()
            }
        }
        ctx.activitySink = { e ->
            SwingUtilities.invokeLater {
                activityModel.add(e)
                val last = activityTable.rowCount - 1
                if (last >= 0) activityTable.scrollRectToVisible(activityTable.getCellRect(last, 0, true))
                scheduleFindingsRecompute()
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
        sweepCancel.text = I18n.t("sweep.cancel")
        root.add(sweepBar, BorderLayout.SOUTH)
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
        val includeUnsafe = JCheckBox(I18n.t("tb.includeUnsafe"), ctx.allowStateChanging.get())
        includeUnsafe.toolTipText = I18n.t("tb.includeUnsafe.tip")
        includeUnsafe.addActionListener { ctx.allowStateChanging.set(includeUnsafe.isSelected) }
        includeUnsafeCheck = includeUnsafe

        val clearResults = JButton(I18n.t("tb.clearMatrix"))
        clearResults.addActionListener {
            matrixModel.clear()
            ctx.resetAutoSeen() // allow the same endpoints to be auto-tested again
            recomputeFindingsNow()
        }
        val clearActivity = JButton(I18n.t("tb.clearActivity"))
        clearActivity.addActionListener {
            activityModel.clear()
            recomputeFindingsNow()
        }
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
        bar.add(auto); bar.add(lowerCont); bar.add(capture); bar.add(includeUnsafe)
        bar.addSeparator()
        bar.add(JLabel(I18n.t("tb.view")))
        bar.add(clearResults); bar.add(clearActivity)
        bar.addSeparator()
        bar.add(JLabel(I18n.t("tb.export")))
        bar.add(exportCsv); bar.add(exportHtml)
        root.add(bar, BorderLayout.NORTH)
    }

    // ---- status (for the Home dashboard) --------------------------------------

    /** Non-anonymous personas (i.e. real roles) configured. */
    fun roleCount(): Int = ctx.store.personas.count { !it.anonymous }

    /** Rows currently in the access matrix (endpoints under test). */
    fun matrixRowCount(): Int = matrixModel.rowCount

    /** All ranked findings currently shown. */
    fun findingCount(): Int = findingsModel.all().size

    /** How many of the findings are CRITICAL. */
    fun criticalCount(): Int =
        findingsModel.all().count { it.severity == com.authzmatrix.model.Severity.CRITICAL }

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

        // Findings (severity-coloured) with search/severity filter and export actions.
        findingsTable.setDefaultRenderer(Any::class.java, ColorRenderer { row, col ->
            if (col == 0) findingsModel.severityAt(row)?.background() else null
        })
        findingsTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(findingsTable, intArrayOf(90, 190, 560))
        val findingsScroll = JScrollPane(findingsTable)

        findingsFilter.columns = 22
        findingsFilter.toolTipText = I18n.t("find.search")
        updatingFindingsFilter = true
        try {
            val selected = (findingsSeverity.selectedItem as? SeverityFilter)?.severity
            findingsSeverity.model = javax.swing.DefaultComboBoxModel(severityOptions().toTypedArray())
            findingsSeverity.selectedItem = severityOptions().first { it.severity == selected }
        } finally {
            updatingFindingsFilter = false
        }
        applyFindingsFilter()

        val findingsBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        findingsBar.add(JLabel(I18n.t("find.filter")))
        findingsBar.add(findingsFilter)
        findingsBar.add(findingsSeverity)
        findingsBar.add(findingsCount)
        val exportFindingsCsv = JButton(I18n.t("find.exportCsv"))
        exportFindingsCsv.addActionListener { exportFindings("csv") }
        val exportFindingsJson = JButton(I18n.t("find.exportJson"))
        exportFindingsJson.addActionListener { exportFindings("json") }
        findingsBar.add(exportFindingsCsv)
        findingsBar.add(exportFindingsJson)
        val findingsPanel = JPanel(BorderLayout())
        findingsPanel.add(findingsBar, BorderLayout.NORTH)
        findingsPanel.add(findingsScroll, BorderLayout.CENTER)

        val bottomTabs = javax.swing.JTabbedPane()
        bottomTabs.addTab(I18n.t("center.activity"), activityScroll)
        bottomTabs.addTab(I18n.t("center.findings"), findingsPanel)

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
        // Rebuilt each time it opens so optional modules registered after startup (e.g. PwnDoc on
        // the private build) still contribute their actions, and labels track the language.
        menu.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                menu.removeAll()
                val toRepeater = JMenuItem(I18n.t("menu.toRepeater"))
                toRepeater.addActionListener {
                    matrixModel.rowAt(matrixTable.selectedRow)?.baseline?.request()
                        ?.let { ctx.api.repeater().sendToRepeater(it) }
                }
                menu.add(toRepeater)
                val curl = JMenuItem(I18n.t("menu.copyCurl"))
                curl.addActionListener {
                    matrixModel.rowAt(matrixTable.selectedRow)?.baseline?.request()?.let { copyCurl(it) }
                }
                menu.add(curl)
                for (action in ctx.matrixRowActions) {
                    val item = JMenuItem(action.label())
                    item.addActionListener {
                        val row = matrixModel.rowAt(matrixTable.selectedRow) ?: return@addActionListener
                        val result = matrixModel.resultAt(matrixTable.selectedRow, matrixTable.selectedColumn)
                        action.run(row, result)
                    }
                    menu.add(item)
                }
            }
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {}
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {}
        })
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
        val fromCookies = JButton(I18n.t("btn.fromCookies"))
        fromCookies.toolTipText = I18n.t("btn.fromCookies.tip")
        fromCookies.addActionListener { SessionPersonaDialog.openNew(ctx) }
        val setToken = JButton(I18n.t("btn.setToken"))
        setToken.toolTipText = I18n.t("btn.setToken.tip")
        setToken.addActionListener { setPersonaToken() }
        val setCookies = JButton(I18n.t("btn.setCookies"))
        setCookies.toolTipText = I18n.t("btn.setCookies.tip")
        setCookies.addActionListener { setPersonaCookies() }
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
        personaBtns.add(add); personaBtns.add(remove); personaBtns.add(fromCookies)
        personaBtns.add(setToken)
        personaBtns.add(setCookies)
        personaBtns.add(refreshExpired)
        personaBtns.add(clearPersonas); personaBtns.add(order)
        val personaBox = JPanel(BorderLayout())
        personaBox.add(personaScroll, BorderLayout.CENTER)
        personaBox.add(personaBtns, BorderLayout.SOUTH)

        // Token catalog.
        catalogTable.selectionMode(ListSelectionModel.SINGLE_SELECTION)
        catalogTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(catalogTable, intArrayOf(60, 80, 140, 140, 55, 150, 220, 200))
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
        if (cm.columnCount > 1) cm.getColumn(1).preferredWidth = 90
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
        findingsFilter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFindingsFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFindingsFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFindingsFilter()
        })
        findingsSeverity.addActionListener { applyFindingsFilter() }
        findingsTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeFindingsPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeFindingsPopup(e)
        })
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

    /** Coalesce bursts of results into one findings recomputation (keeps the EDT responsive). */
    private fun scheduleFindingsRecompute() = findingsTimer.restart()

    private fun recomputeFindingsNow() {
        findingsTimer.stop()
        recomputeFindings()
    }

    private fun applyFindingsFilter() {
        if (updatingFindingsFilter) return
        val severity = (findingsSeverity.selectedItem as? SeverityFilter)?.severity
        findingsModel.setFilter(findingsFilter.text, severity)
        findingsCount.text = "${findingsModel.rowCount} / ${findingsModel.all().size}"
    }

    private fun severityOptions(): List<SeverityFilter> =
        listOf(SeverityFilter(null)) + Severity.values().map { SeverityFilter(it) }

    private fun maybeFindingsPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = findingsTable.rowAtPoint(e.point)
        if (row < 0) return
        findingsTable.setRowSelectionInterval(row, row)
        buildFindingsMenu().show(findingsTable, e.x, e.y)
    }

    private fun buildFindingsMenu(): JPopupMenu {
        val menu = JPopupMenu()
        val req = findingsModel.findingAt(findingsTable.selectedRow)?.requestResponse?.request()

        val toRepeater = JMenuItem(I18n.t("menu.findingToRepeater"))
        toRepeater.isEnabled = req != null
        toRepeater.addActionListener { req?.let { ctx.api.repeater().sendToRepeater(it) } }

        val curl = JMenuItem(I18n.t("menu.copyCurl"))
        curl.isEnabled = req != null
        curl.addActionListener { req?.let { copyCurl(it) } }

        menu.add(toRepeater)
        menu.add(curl)
        return menu
    }

    private fun copyCurl(req: HttpRequest) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(CurlBuilder.build(req)), null)
        ctx.api.logging().logToOutput("RoleBreaker: request copied as cURL")
    }

    /** Paste/replace a token without editing the read-only summary column in the table. */
    private fun setPersonaToken() {
        val persona = personaModel.personaAt(personaTable.selectedRow)
        if (persona == null) {
            JOptionPane.showMessageDialog(root, I18n.t("setToken.noPersona"), I18n.t("dlg.title"),
                JOptionPane.WARNING_MESSAGE)
            return
        }
        val area = JTextArea(5, 60)
        area.lineWrap = true
        area.wrapStyleWord = true
        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JLabel(I18n.t("setToken.persona", persona.name)), BorderLayout.NORTH)
        panel.add(JScrollPane(area), BorderLayout.CENTER)
        panel.add(JLabel(I18n.t("setToken.hint")), BorderLayout.SOUTH)

        if (JOptionPane.showConfirmDialog(root, panel, I18n.t("setToken.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return

        val token = area.text.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        synchronized(persona) {
            persona.token = token
            persona.cookies.clear() // a token replaces any previous cookie bundle
            persona.ownerSub = Jwt.parse(token)?.sub
        }
        ctx.store.fireChanged()
    }

    /** Edit a full Cookie header bundle for the selected persona. */
    private fun setPersonaCookies() {
        val persona = personaModel.personaAt(personaTable.selectedRow)
        if (persona == null) {
            JOptionPane.showMessageDialog(root, I18n.t("setCookies.noPersona"), I18n.t("dlg.title"),
                JOptionPane.WARNING_MESSAGE)
            return
        }
        val area = JTextArea(CookieHeader.serialize(persona.cookies), 5, 60)
        area.lineWrap = true
        area.wrapStyleWord = true
        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JLabel(I18n.t("setCookies.persona", persona.name)), BorderLayout.NORTH)
        panel.add(JScrollPane(area), BorderLayout.CENTER)
        panel.add(JLabel(I18n.t("setCookies.hint")), BorderLayout.SOUTH)

        if (JOptionPane.showConfirmDialog(root, panel, I18n.t("setCookies.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return

        val parsed = CookieHeader.parse(area.text)
        synchronized(persona) {
            persona.cookies.clear()
            persona.cookies.putAll(parsed)
            persona.location = TokenLocation.COOKIE
            persona.token = ""
            persona.ownerSub = null
        }
        ctx.store.fireChanged()
    }

    private fun exportFindings(kind: String) {
        val items = findingsModel.visible()
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(root, I18n.t("find.exportEmpty"), I18n.t("dlg.title"),
                JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val fc = JFileChooser()
        fc.selectedFile = File("rolebreaker-findings.$kind")
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return
        val text = if (kind == "csv") FindingsExport.csv(items) else FindingsExport.json(items)
        try {
            java.nio.file.Files.writeString(fc.selectedFile.toPath(), text)
            ctx.api.logging().logToOutput("RoleBreaker: exported ${items.size} finding(s) to ${fc.selectedFile}")
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(root, I18n.t("export.error", ex.message ?: ""), "Export",
                JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun recomputeFindings() {
        val selected = findingsModel.findingAt(findingsTable.selectedRow)
        val derived = com.authzmatrix.core.FindingsAnalyzer.analyze(
            matrixModel.rowsSnapshot(), activityModel.entriesSnapshot(), ctx.store,
        )
        findingsModel.set((derived + manualFindings).sortedBy { it.severity.ordinal })
        selected?.let { old ->
            val idx = findingsModel.indexOf(old)
            if (idx >= 0) findingsTable.setRowSelectionInterval(idx, idx)
        }
        findingsCount.text = "${findingsModel.rowCount} / ${findingsModel.all().size}"
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

    private data class SweepOptions(
        val minutes: Long,
        val includeUnsafe: Boolean,
        val collapseIds: Boolean,
    )

    private fun autoSweep() {
        if (activeSweep != null) {
            JOptionPane.showMessageDialog(root, I18n.t("sweep.running"), I18n.t("sweep.title"),
                JOptionPane.WARNING_MESSAGE)
            return
        }
        val opts = showSweepOptions() ?: return
        ctx.allowStateChanging.set(opts.includeUnsafe)
        includeUnsafeCheck?.isSelected = opts.includeUnsafe

        bg("rolebreaker-auto-sweep") {
            ctx.historyScanner.scan(opts.minutes, true)
            ctx.store.syncPersonasFromTokens()
            if (ctx.store.personas.any { !it.anonymous } && ctx.store.ensureAnonymousPersona()) {
                ctx.api.logging().logToOutput(
                    "RoleBreaker: no anonymous persona found — added one so unauthenticated access is tested",
                )
            }
            com.authzmatrix.core.PrivilegeRanker.assignLevels(ctx.store.personas)
            ctx.store.fireChanged() // persist levels + refresh UI

            val all = ctx.historyScanner.recentRequests(opts.minutes, opts.collapseIds)
            val targets = all.filter { ctx.isAutoTargetAllowed(it) }
            val skipped = all.size - targets.size

            SwingUtilities.invokeLater {
                catalogModel.refresh()
                val roles = ctx.store.personas.filter { !it.anonymous }
                if (roles.isEmpty()) {
                    val msg = if (ctx.store.capturedTokens.any { it.isCookie }) {
                        I18n.t("sweep.cookiesFound")
                    } else {
                        I18n.t("sweep.noRoles", opts.minutes)
                    }
                    JOptionPane.showMessageDialog(root, msg, I18n.t("sweep.title"),
                        JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                if (targets.isEmpty()) {
                    val msg = if (skipped > 0) I18n.t("sweep.onlyUnsafe", skipped)
                    else I18n.t("sweep.noReqs", opts.minutes)
                    JOptionPane.showMessageDialog(root, msg, I18n.t("sweep.title"),
                        JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                confirmAndStart(targets, skipped, opts.minutes)
            }
        }
    }

    private fun showSweepOptions(): SweepOptions? {
        val minutes = JTextField("20", 6)
        val includeUnsafe = JCheckBox(I18n.t("sweep.includeUnsafe"), ctx.allowStateChanging.get())
        val collapse = JCheckBox(I18n.t("sweep.collapseIds"), true)

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(JLabel(I18n.t("sweep.minutes")))
        panel.add(minutes)
        panel.add(Box.createVerticalStrut(8))
        panel.add(includeUnsafe)
        panel.add(collapse)
        panel.add(Box.createVerticalStrut(8))
        panel.add(JLabel(I18n.t("sweep.unsafeWarning")))

        val ok = JOptionPane.showConfirmDialog(root, panel, I18n.t("sweep.title"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return null
        val mins = minutes.text.trim().toLongOrNull()?.coerceAtLeast(1) ?: 20L
        return SweepOptions(mins, includeUnsafe.isSelected, collapse.isSelected)
    }

    private fun confirmAndStart(reqs: List<HttpRequest>, skipped: Int, minutes: Long) {
        val text = JTextArea()
        text.isEditable = false
        text.isOpaque = false
        text.lineWrap = true
        text.wrapStyleWord = true
        val scroll = JScrollPane(text)
        scroll.preferredSize = java.awt.Dimension(560, 220)
        val options = arrayOf(I18n.t("sweep.run"), I18n.t("btn.order"), I18n.t("sweep.cancel"))

        fun hierarchy() = ctx.store.personas.sortedBy { it.level }
            .joinToString(" > ") { "${it.name}(${it.level})" }
        fun methods() = reqs.groupingBy { it.method() }.eachCount()
            .entries.joinToString(", ") { "${it.key}: ${it.value}" }
        fun message() = I18n.t("sweep.confirm", reqs.size, minutes, skipped, hierarchy(), methods())

        text.text = message()
        while (true) {
            val choice = JOptionPane.showOptionDialog(
                root, scroll, I18n.t("sweep.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0],
            )
            when (choice) {
                0 -> { startSweep(reqs); return }
                1 -> { PrivilegeOrderDialog.open(ctx); text.text = message() }
                else -> return
            }
        }
    }

    private fun startSweep(reqs: List<HttpRequest>) {
        val token = SweepToken()
        activeSweep = token
        sweepHideTimer.stop()
        sweepBar.isVisible = true
        sweepProgress.minimum = 0
        sweepProgress.maximum = reqs.size
        sweepProgress.value = 0
        sweepProgress.string = "0 / ${reqs.size}"
        sweepStatus.text = I18n.t("sweep.progress", 0, reqs.size)
        sweepCancel.isEnabled = true

        AutoLowerPrivTester.runAsync(
            ctx, reqs, token,
            onProgress = { p -> SwingUtilities.invokeLater { if (activeSweep === token) updateSweepBar(p) } },
            onComplete = { p -> SwingUtilities.invokeLater { finishSweepBar(token, p) } },
        )
    }

    private fun updateSweepBar(p: SweepProgress) {
        sweepProgress.maximum = maxOf(p.total, 1)
        sweepProgress.value = p.done
        sweepProgress.string = "${p.done} / ${p.total}"
        sweepStatus.text = buildString {
            append(I18n.t("sweep.progress", p.done, p.total))
            if (p.errors > 0) append(" · ").append(I18n.t("sweep.errors", p.errors))
            if (p.skipped > 0) append(" · ").append(I18n.t("sweep.skipped", p.skipped))
        }
    }

    private fun finishSweepBar(token: SweepToken, p: SweepProgress) {
        if (activeSweep !== token) return
        activeSweep = null
        sweepProgress.maximum = maxOf(p.total, 1)
        sweepProgress.value = p.done
        sweepProgress.string = "${p.done} / ${p.total}"
        sweepStatus.text = I18n.t("sweep.done", p.tested, p.errors, p.skipped)
        sweepCancel.isEnabled = false
        sweepHideTimer.restart()
        ctx.api.logging().logToOutput(I18n.t("sweep.doneLog", p.tested, p.errors, p.skipped))
    }

    private fun configureSweepBar() {
        sweepProgress.isStringPainted = true
        sweepBar.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        sweepBar.isVisible = false
        sweepBar.add(sweepStatus, BorderLayout.WEST)
        sweepBar.add(sweepProgress, BorderLayout.CENTER)
        sweepBar.add(sweepCancel, BorderLayout.EAST)
        sweepCancel.addActionListener {
            activeSweep?.cancelled = true
            sweepCancel.isEnabled = false
            sweepStatus.text = I18n.t("sweep.cancelling")
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
        if (t.isCookie) {
            val suggested = "cookie${ctx.store.personas.count { it.usesCookies } + 1}"
            val name = JOptionPane.showInputDialog(root, I18n.t("cookie.personaName"), suggested) ?: return
            if (name.isBlank()) return
            val p = Persona(name = name.trim(), location = TokenLocation.COOKIE)
            p.cookies.putAll(t.cookies)
            ctx.store.addPersona(p)
        } else {
            val name = t.info?.role ?: t.info?.owner ?: "token"
            ctx.store.addPersona(Persona(name = name, token = t.token, ownerSub = t.info?.sub))
        }
    }

    private fun crackSelected() {
        val t = catalogModel.tokenAt(catalogTable.selectedRow)
        if (t == null) {
            JOptionPane.showMessageDialog(root, I18n.t("crack.selectToken"), I18n.t("crack.title"),
                JOptionPane.WARNING_MESSAGE)
            return
        }
        if (t.isCookie) {
            JOptionPane.showMessageDialog(root, I18n.t("crack.notJwt"), I18n.t("crack.title"),
                JOptionPane.INFORMATION_MESSAGE)
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
            recomputeFindingsNow()
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

    private class SeverityFilter(val severity: Severity?) {
        override fun toString() = severity?.name ?: I18n.t("find.filterAll")
    }

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
