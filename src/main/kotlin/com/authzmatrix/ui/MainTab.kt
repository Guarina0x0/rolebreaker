package com.authzmatrix.ui

import burp.api.montoya.ui.editor.EditorOptions
import com.authzmatrix.core.AppContext
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
        buildToolbar()
        buildCenter()
        wire()
        catalogModel.refresh()
        widenMatrix()

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

    // ---- toolbar --------------------------------------------------------------

    private fun buildToolbar() {
        val bar = javax.swing.JToolBar()
        bar.isFloatable = false
        bar.isRollover = true

        val auto = JCheckBox("Auto (todas las personas)", ctx.autoMode.get())
        auto.toolTipText = "Replay de cada request in-scope contra todas las personas"
        val lowerCont = JCheckBox("Continuo ↓priv (fondo)", ctx.autoLowerMode.get())
        lowerCont.toolTipText = "Navega como admin; cada request nueva se relanza en background con los roles de menor privilegio"
        // Mutually exclusive: enabling one disables the other (avoids double-testing each request).
        auto.addActionListener {
            ctx.autoMode.set(auto.isSelected)
            if (auto.isSelected) { lowerCont.isSelected = false; ctx.autoLowerMode.set(false); ctx.resetAutoSeen() }
        }
        lowerCont.addActionListener {
            ctx.autoLowerMode.set(lowerCont.isSelected)
            if (lowerCont.isSelected) { auto.isSelected = false; ctx.autoMode.set(false); ctx.resetAutoSeen() }
        }
        val capture = JCheckBox("Capturar JWTs del tráfico", ctx.capture.enabled)
        capture.addActionListener { ctx.capture.enabled = capture.isSelected }

        val clearResults = JButton("Limpiar matriz")
        clearResults.addActionListener { matrixModel.clear() }
        val clearActivity = JButton("Limpiar actividad")
        clearActivity.addActionListener { activityModel.clear() }
        val exportCsv = JButton("Export CSV")
        exportCsv.addActionListener { export("csv") }
        val exportHtml = JButton("Export HTML")
        exportHtml.addActionListener { export("html") }

        val sweep = JButton("⚡ Auto sweep")
        sweep.toolTipText = "Un clic: escanea el historial reciente, crea y ordena roles, y prueba todo con los inferiores"
        sweep.addActionListener { autoSweep() }

        // Grouped: Auto · Modo · Vista · Export · PwnDoc
        bar.add(JLabel(" Auto: "))
        bar.add(sweep)
        bar.addSeparator()
        bar.add(JLabel(" Modo: "))
        bar.add(auto); bar.add(lowerCont); bar.add(capture)
        bar.addSeparator()
        bar.add(JLabel(" Vista: "))
        bar.add(clearResults); bar.add(clearActivity)
        bar.addSeparator()
        bar.add(JLabel(" Export: "))
        bar.add(exportCsv); bar.add(exportHtml)
        root.add(bar, BorderLayout.NORTH)
    }

    // ---- center ---------------------------------------------------------------

    private fun buildCenter() {
        matrixTable.setDefaultRenderer(Any::class.java, ColorRenderer { row, col ->
            if (col >= 2) matrixModel.verdictAt(row, col)?.background() else null
        })
        matrixTable.cellSelectionEnabled = true
        matrixTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        matrixTable.rowHeight = 22
        val matrixScroll = JScrollPane(matrixTable)
        matrixScroll.border = BorderFactory.createTitledBorder("Matriz de acceso (petición × persona)")

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
        bottomTabs.addTab("Actividad (en vivo)", activityScroll)
        bottomTabs.addTab("⚠ Findings", findingsScroll)

        val detail = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            titled(reqEditor.uiComponent(), "Request"),
            titled(respEditor.uiComponent(), "Response"),
        )
        detail.resizeWeight = 0.5

        val bottom = JSplitPane(JSplitPane.VERTICAL_SPLIT, bottomTabs, detail)
        bottom.resizeWeight = 0.4

        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, bottom)
        mainSplit.resizeWeight = 0.5
        root.add(mainSplit, BorderLayout.CENTER)
    }

    private fun buildConfigPanel(): JComponent {
        // Personas.
        personaTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        personaTable.getColumnModel().getColumn(3).cellEditor = DefaultCellEditor(JComboBox(TokenLocation.values()))
        personaTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        applyWidths(personaTable, intArrayOf(34, 100, 44, 120, 110, 230, 55))
        val personaScroll = JScrollPane(personaTable)
        personaScroll.border = BorderFactory.createTitledBorder("Personas (roles)")
        val personaBtns = JPanel(FlowLayout(FlowLayout.LEFT))
        val add = JButton("Add"); add.addActionListener { ctx.store.addPersona(Persona(name = "role${ctx.store.personas.size + 1}")) }
        val remove = JButton("Remove"); remove.addActionListener {
            personaModel.personaAt(personaTable.selectedRow)?.let { ctx.store.removePersona(it) }
        }
        val refreshExpired = JButton("Refrescar caducados")
        refreshExpired.toolTipText = "Re-login de las personas con token caducado que tengan refresh configurado"
        refreshExpired.addActionListener { ctx.refreshAllExpired() }
        val clearPersonas = JButton("Limpiar personas")
        clearPersonas.toolTipText = "Borra TODAS las personas (incl. las guardadas de sesiones anteriores)"
        clearPersonas.addActionListener {
            if (JOptionPane.showConfirmDialog(root, "¿Borrar todas las personas?", "AuthZ Matrix",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                ctx.store.clearPersonas()
            }
        }
        val order = JButton("Ordenar privilegios…")
        order.toolTipText = "Marca qué rol tiene más y cuál menos privilegio (para el test automático)"
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
        catalogScroll.border = BorderFactory.createTitledBorder("Identidades descubiertas (JWT, sin duplicados)")
        val catalogBtns = JPanel(FlowLayout(FlowLayout.LEFT))
        val scan = JButton("Escanear HTTP history"); scan.addActionListener { scanHistory() }
        val sync = JButton("Crear personas por rol"); sync.addActionListener { syncPersonas() }
        val promote = JButton("Promover selección"); promote.addActionListener { promoteToken() }
        val crack = JButton("Crackear secreto (HS)"); crack.addActionListener { crackSelected() }
        crack.toolTipText = "Prueba secretos comunes contra un token HS256/384/512 (offline)"
        val clearCat = JButton("Limpiar"); clearCat.addActionListener { ctx.store.clearCaptured() }
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

    private fun wire() {
        matrixTable.selectionModel.addListSelectionListener { showMatrixCell() }
        matrixTable.columnModel.selectionModel.addListSelectionListener { showMatrixCell() }
        activityTable.selectionModel.addListSelectionListener {
            activityModel.entryAt(activityTable.selectedRow)?.requestResponse?.let { show(it) }
        }
        findingsTable.selectionModel.addListSelectionListener {
            findingsModel.findingAt(findingsTable.selectedRow)?.requestResponse?.let { show(it) }
        }
        val menu = JPopupMenu()
        val toRepeater = JMenuItem("Send original to Repeater")
        toRepeater.addActionListener {
            matrixModel.rowAt(matrixTable.selectedRow)?.baseline?.request()?.let { ctx.api.repeater().sendToRepeater(it) }
        }
        menu.add(toRepeater)
        matrixTable.componentPopupMenu = menu
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

    private fun autoSweep() {
        val input = JOptionPane.showInputDialog(root,
            "Auto sweep — usa el historial de los últimos N minutos:\n" +
                "escanea JWT activos, crea roles, ordena privilegios solo y prueba\n" +
                "cada petición con los roles de MENOR privilegio.",
            "20") ?: return
        val minutes = input.trim().toLongOrNull()?.coerceAtLeast(1) ?: 20L
        Thread({
            ctx.historyScanner.scan(minutes, true)
            ctx.store.syncPersonasFromTokens()
            com.authzmatrix.core.PrivilegeRanker.assignLevels(ctx.store.personas)
            ctx.store.fireChanged() // persist levels + refresh UI
            val reqs = ctx.historyScanner.recentRequests(minutes)
            SwingUtilities.invokeLater {
                catalogModel.refresh()
                val roles = ctx.store.personas.filter { !it.anonymous }
                if (roles.isEmpty()) {
                    JOptionPane.showMessageDialog(root,
                        "No se descubrieron roles con token activo en los últimos ${minutes}m.",
                        "Auto sweep", JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                if (reqs.isEmpty()) {
                    JOptionPane.showMessageDialog(root,
                        "No hay peticiones in-scope en los últimos ${minutes}m para probar.",
                        "Auto sweep", JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                com.authzmatrix.ui.AutoLowerPrivTester.run(ctx, reqs)
                val order = ctx.store.personas.sortedBy { it.level }
                    .joinToString(" > ") { "${it.name}(${it.level})" }
                JOptionPane.showMessageDialog(root,
                    "Auto sweep lanzado sobre ${reqs.size} petición(es) de los últimos ${minutes}m.\n" +
                        "Jerarquía detectada (más → menos priv): $order\n" +
                        "Revisa la pestaña ⚠ Findings.",
                    "Auto sweep", JOptionPane.INFORMATION_MESSAGE)
            }
        }, "authz-auto-sweep").start()
    }

    private fun scanHistory() {
        val input = JOptionPane.showInputDialog(root,
            "Escanear JWT del historial de los últimos N minutos (solo tokens activos):",
            "60") ?: return
        val minutes = input.trim().toLongOrNull()?.coerceAtLeast(1) ?: 60L
        Thread({
            val n = ctx.historyScanner.scan(minutes, true)
            val created = ctx.store.syncPersonasFromTokens()
            SwingUtilities.invokeLater {
                catalogModel.refresh()
                JOptionPane.showMessageDialog(root,
                    "$n token(s) activo(s) nuevos (últimos ${minutes}m).\n$created persona(s) creada(s) por rol.",
                    "AuthZ Matrix", JOptionPane.INFORMATION_MESSAGE)
                offerPrivilegeOrder()
            }
        }, "authz-history-scan").start()
    }

    private fun syncPersonas() {
        val created = ctx.store.syncPersonasFromTokens()
        JOptionPane.showMessageDialog(root, "$created persona(s) creada(s). Total: ${ctx.store.personas.size}.",
            "AuthZ Matrix", JOptionPane.INFORMATION_MESSAGE)
        offerPrivilegeOrder()
    }

    private fun offerPrivilegeOrder() {
        if (ctx.store.personas.size >= 2 &&
            JOptionPane.showConfirmDialog(root, "¿Ordenar ahora los roles por privilegio (más → menos)?",
                "AuthZ Matrix", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
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
            JOptionPane.showMessageDialog(root, "Selecciona un token del catálogo.", "Crackear",
                JOptionPane.WARNING_MESSAGE)
            return
        }
        if (!com.authzmatrix.core.JwtCracker.isHmac(t.info?.alg)) {
            JOptionPane.showMessageDialog(root, "El crackeo de secreto solo aplica a tokens HS256/384/512 " +
                "(este es alg=${t.info?.alg ?: "?"}).", "Crackear", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        Thread({
            val hit = com.authzmatrix.core.JwtCracker.crack(t.token, com.authzmatrix.core.JwtCracker.COMMON_SECRETS)
            SwingUtilities.invokeLater { reportCrack(t.token, hit, "lista común", offerWordlist = true) }
        }, "authz-jwt-crack").start()
    }

    private fun reportCrack(token: String, hit: String?, source: String, offerWordlist: Boolean) {
        if (hit != null) {
            ctx.api.logging().logToOutput("AuthZ Matrix: SECRETO HMAC DÉBIL encontrado ($source): '$hit'")
            manualFindings += com.authzmatrix.model.Finding(
                com.authzmatrix.model.Severity.CRITICAL, "Secreto HMAC débil",
                "Secreto del token crackeado ($source): «$hit» → el JWT es forjable.", null,
            )
            recomputeFindings()
            JOptionPane.showMessageDialog(root,
                "⚠ SECRETO DÉBIL: «$hit»\nEl token es forjable: puedes firmar cualquier claim con ese secreto.",
                "Crackear — vulnerable", JOptionPane.WARNING_MESSAGE)
            return
        }
        if (!offerWordlist) {
            JOptionPane.showMessageDialog(root, "No se crackeó con la $source.", "Crackear",
                JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val loadCustom = JOptionPane.showConfirmDialog(root,
            "No se crackeó con la $source. ¿Probar con una wordlist propia?",
            "Crackear", JOptionPane.YES_NO_OPTION)
        if (loadCustom != JOptionPane.YES_OPTION) return
        val fc = JFileChooser()
        if (fc.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return
        val file = fc.selectedFile
        Thread({
            val words = try {
                java.nio.file.Files.readAllLines(file.toPath()).map { it.trim() }.filter { it.isNotEmpty() }
            } catch (ex: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(root, "No se pudo leer la wordlist: ${ex.message}", "Crackear",
                        JOptionPane.ERROR_MESSAGE)
                }
                return@Thread
            }
            val hit2 = com.authzmatrix.core.JwtCracker.crack(token, words)
            SwingUtilities.invokeLater { reportCrack(token, hit2, "wordlist (${words.size} palabras)", offerWordlist = false) }
        }, "authz-jwt-crack-wl").start()
    }

    private fun export(kind: String) {
        val fc = JFileChooser()
        fc.selectedFile = File("authz-matrix.$kind")
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) return
        val text = if (kind == "csv") matrixModel.exportCsv() else matrixModel.exportHtml()
        try {
            java.nio.file.Files.writeString(fc.selectedFile.toPath(), text)
            ctx.api.logging().logToOutput("AuthZ Matrix: exportado ${fc.selectedFile}")
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(root, "Error exportando: ${ex.message}", "Export", JOptionPane.ERROR_MESSAGE)
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
