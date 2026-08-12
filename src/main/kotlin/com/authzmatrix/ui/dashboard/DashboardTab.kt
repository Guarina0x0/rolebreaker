package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.DashboardModule
import com.authzmatrix.core.I18n
import com.authzmatrix.core.ModuleRegistry
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

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
    private val cards = JPanel(CardLayout())
    private val sidebar = JPanel()
    private val navItems = LinkedHashMap<String, NavItem>()
    private var selectedId = "home"

    init {
        home = HomeModule(ctx, peers = { modules.filter { it.id != "home" } }, switchTo = ::select)
        modules = listOf(home, MatrixModule(ctx), PayloadsModule(ctx)) + ModuleRegistry.extras(ctx)

        cards.isOpaque = false
        for (m in modules) cards.add(m.component(), m.id)

        buildSidebar()
        root.add(topBar(), BorderLayout.NORTH)
        root.add(sidebar, BorderLayout.WEST)
        root.add(cards, BorderLayout.CENTER)

        select("home")
        home.refresh()

        I18n.onChange { SwingUtilities.invokeLater { relabel() } }
    }

    fun component(): Component = root

    // ---- top bar --------------------------------------------------------------

    private fun topBar(): JComponent {
        val bar = JPanel(BorderLayout())
        bar.background = DashTheme.sidebar
        bar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, DashTheme.line),
            BorderFactory.createEmptyBorder(10, 14, 10, 14),
        )
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        left.isOpaque = false
        left.add(JLabel("◆").apply {
            foreground = DashTheme.accent
            font = font.deriveFont(Font.BOLD, font.size + 5f)
        })
        left.add(JLabel("RoleBreaker").apply {
            foreground = DashTheme.text
            font = font.deriveFont(Font.BOLD, font.size + 4f)
        })
        bar.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        right.isOpaque = false
        right.add(JLabel(I18n.t("tb.lang")).apply { foreground = DashTheme.muted })
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
        sidebar.background = DashTheme.sidebar
        sidebar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, DashTheme.line),
            BorderFactory.createEmptyBorder(12, 10, 12, 10),
        )
        sidebar.preferredSize = Dimension(210, 0)
        for (m in modules) {
            val item = NavItem(m)
            navItems[m.id] = item
            sidebar.add(item)
            sidebar.add(Box.createVerticalStrut(6))
        }
        sidebar.add(Box.createVerticalGlue())
    }

    private fun relabel() {
        navItems.values.forEach { it.relabel() }
        sidebar.revalidate(); sidebar.repaint()
    }

    /** Show the module's card and sync the sidebar selection. */
    private fun select(id: String) {
        selectedId = id
        navItems.forEach { (mid, item) -> item.setSelected(mid == id) }
        (cards.layout as CardLayout).show(cards, id)
        if (id == "home") home.refresh()
    }

    /** A clickable sidebar row: "🔐  AuthZ Matrix" with a selected (accent) state. */
    private inner class NavItem(private val module: DashboardModule) : JPanel(BorderLayout(10, 0)) {
        private val icon = JLabel(module.icon()).apply { font = font.deriveFont(15f) }
        private val label = JLabel(module.title())

        init {
            add(icon, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            setSelected(false)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = select(module.id)
            })
        }

        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)

        fun relabel() {
            icon.text = module.icon()
            label.text = module.title()
        }

        fun setSelected(selected: Boolean) {
            background = if (selected) DashTheme.selected else DashTheme.sidebar
            label.foreground = if (selected) DashTheme.text else DashTheme.muted
            label.font = label.font.deriveFont(if (selected) Font.BOLD else Font.PLAIN)
            border = if (selected) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, DashTheme.accent),
                    BorderFactory.createEmptyBorder(9, 9, 9, 12),
                )
            } else {
                BorderFactory.createEmptyBorder(9, 12, 9, 12)
            }
        }
    }
}
