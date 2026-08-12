package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.DashboardModule
import com.authzmatrix.core.I18n
import com.authzmatrix.core.ModuleRegistry
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * The dashboard shell registered as the single Burp suite tab. A left sidebar (nav rail) switches
 * the card panel between modules; a slim top bar carries the suite title and the language selector.
 *
 * Always-present modules: Home + AuthZ Matrix. Branch-specific extras (e.g. the private PwnDoc
 * module on `main`) come from [ModuleRegistry.extras].
 */
class DashboardTab(private val ctx: AppContext) {

    private val home: HomeModule
    private val modules: List<DashboardModule>

    private val root = JPanel(BorderLayout())
    private val cards = JPanel(java.awt.CardLayout())
    private val sidebar = JPanel()
    private val group = ButtonGroup()
    private val navButtons = LinkedHashMap<String, JToggleButton>()

    init {
        // Home is built first but needs to see its peers + a way to switch cards.
        home = HomeModule(ctx, peers = { modules.filter { it.id != "home" } }, switchTo = ::select)
        modules = listOf(home, MatrixModule(ctx)) + ModuleRegistry.extras(ctx)

        for (m in modules) cards.add(m.component(), m.id)

        buildSidebar()
        root.add(topBar(), BorderLayout.NORTH)
        root.add(sidebar, BorderLayout.WEST)
        root.add(cards, BorderLayout.CENTER)

        select("home")
        home.refresh()

        // On language change, relabel the sidebar (Home refreshes itself via its own listener).
        I18n.onChange { javax.swing.SwingUtilities.invokeLater { relabelSidebar() } }
    }

    fun component(): Component = root

    // ---- top bar --------------------------------------------------------------

    private fun topBar(): JComponent {
        val bar = JPanel(BorderLayout())
        bar.border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        val title = JLabel("RoleBreaker")
        title.font = title.font.deriveFont(Font.BOLD, title.font.size + 3f)
        bar.add(title, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        right.add(JLabel(I18n.t("tb.lang")))
        right.add(langCombo())
        bar.add(right, BorderLayout.EAST)
        return bar
    }

    private fun langCombo(): JComboBox<LangItem> {
        val items = arrayOf(LangItem(I18n.Lang.EN, "English"), LangItem(I18n.Lang.ES, "Español"))
        val combo = JComboBox(items)
        combo.selectedItem = items.first { it.lang == I18n.lang }
        combo.maximumSize = combo.preferredSize
        combo.addActionListener {
            val sel = (combo.selectedItem as? LangItem)?.lang ?: return@addActionListener
            if (sel != I18n.lang) {
                ctx.persistLang(sel)
                I18n.setLang(sel) // fans out to every module's onChange listener
            }
        }
        return combo
    }

    private class LangItem(val lang: I18n.Lang, val label: String) {
        override fun toString() = label
    }

    // ---- sidebar --------------------------------------------------------------

    private fun buildSidebar() {
        sidebar.layout = BoxLayout(sidebar, BoxLayout.Y_AXIS)
        sidebar.border = BorderFactory.createEmptyBorder(10, 8, 10, 8)
        sidebar.preferredSize = Dimension(170, 0)
        for (m in modules) {
            val b = JToggleButton(navLabel(m))
            b.horizontalAlignment = JToggleButton.LEFT
            b.alignmentX = Component.LEFT_ALIGNMENT
            b.maximumSize = Dimension(Int.MAX_VALUE, b.preferredSize.height + 6)
            b.isFocusPainted = false
            b.addActionListener { select(m.id) }
            group.add(b)
            navButtons[m.id] = b
            sidebar.add(b)
            sidebar.add(Box.createVerticalStrut(4))
        }
        sidebar.add(Box.createVerticalGlue())
    }

    private fun navLabel(m: DashboardModule) = "${m.icon()}  ${m.title()}"

    private fun relabelSidebar() {
        for (m in modules) navButtons[m.id]?.text = navLabel(m)
        sidebar.revalidate(); sidebar.repaint()
    }

    /** Show the module's card and sync the sidebar selection. */
    private fun select(id: String) {
        navButtons[id]?.isSelected = true
        (cards.layout as java.awt.CardLayout).show(cards, id)
        if (id == "home") home.refresh()
    }
}
