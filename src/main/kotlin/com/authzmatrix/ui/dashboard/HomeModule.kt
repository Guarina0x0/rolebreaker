package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.DashboardModule
import com.authzmatrix.core.I18n
import com.authzmatrix.core.ModuleStatus
import com.authzmatrix.core.StatChip
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * The dashboard landing view: one live status card per registered module (everything except Home
 * itself), each with its stat chips and an "Open" button that switches the sidebar to that module.
 *
 * Cards refresh whenever the persona store changes and whenever the Home view is shown again.
 */
class HomeModule(
    ctx: AppContext,
    /** All modules except this one; supplied lazily so the shell can build Home first. */
    private val peers: () -> List<DashboardModule>,
    /** Switch the sidebar/card panel to the module with this id. */
    private val switchTo: (String) -> Unit,
) : DashboardModule {

    private val root = JPanel(BorderLayout())
    private val cards = JPanel()
    private val scroll: JScrollPane

    init {
        cards.layout = BoxLayout(cards, BoxLayout.Y_AXIS)
        cards.isOpaque = false
        cards.border = BorderFactory.createEmptyBorder(16, 18, 16, 18)
        scroll = JScrollPane(cards)
        scroll.border = null
        scroll.verticalScrollBar.unitIncrement = 16
        root.add(scroll, BorderLayout.CENTER)

        // Live-refresh cards on any data change; rebuild labels on language change too.
        ctx.store.onChange { SwingUtilities.invokeLater { refresh() } }
        I18n.onChange { SwingUtilities.invokeLater { refresh() } }
    }

    override val id = "home"
    override fun title() = I18n.t("mod.home")
    override fun icon() = "🏠"
    override fun component(): JComponent = root
    override fun status() = ModuleStatus()

    /** Rebuild every card from the peers' current status. Safe to call repeatedly. */
    fun refresh() {
        cards.removeAll()

        val header = JLabel(I18n.t("home.title"))
        header.font = header.font.deriveFont(Font.BOLD, header.font.size + 7f)
        header.foreground = DashTheme.text
        header.alignmentX = Component.LEFT_ALIGNMENT
        cards.add(header)
        cards.add(JLabel(I18n.t("home.subtitle")).apply {
            foreground = DashTheme.muted; alignmentX = Component.LEFT_ALIGNMENT
        })
        cards.add(Box.createVerticalStrut(14))

        for (m in peers()) {
            cards.add(card(m))
            cards.add(Box.createVerticalStrut(12))
        }
        cards.add(Box.createVerticalGlue())
        cards.revalidate()
        cards.repaint()
    }

    private fun card(m: DashboardModule): JComponent {
        val st = runCatching { m.status() }.getOrDefault(ModuleStatus())
        // Anonymous subclass so the card never grows past its preferred height in the BoxLayout
        // (this was the "cut off" bug: a fixed maximumSize captured before children were added).
        val panel = object : JPanel(BorderLayout(0, 10)) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        panel.background = DashTheme.card
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(DashTheme.line),
            BorderFactory.createEmptyBorder(14, 16, 14, 16),
        )
        panel.alignmentX = Component.LEFT_ALIGNMENT

        // Title row: "🔐  AuthZ Matrix" .............. [ Open → ]
        val titleRow = JPanel(BorderLayout())
        titleRow.isOpaque = false
        val name = JLabel("${m.icon()}   ${m.title()}")
        name.font = name.font.deriveFont(Font.BOLD, name.font.size + 2f)
        name.foreground = DashTheme.text
        titleRow.add(name, BorderLayout.WEST)
        val open = JButton(I18n.t("home.open"))
        open.addActionListener { switchTo(m.id) }
        titleRow.add(open, BorderLayout.EAST)
        panel.add(titleRow, BorderLayout.NORTH)

        if (st.chips.isNotEmpty()) {
            val chips = JPanel(GridLayout(1, st.chips.size, 10, 0))
            chips.isOpaque = false
            for (c in st.chips) chips.add(chip(c))
            panel.add(chips, BorderLayout.CENTER)
        }

        st.line?.let {
            val line = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
            line.isOpaque = false
            line.add(JLabel("●").apply { foreground = DashTheme.tone(st.lineTone) })
            line.add(JLabel(it).apply { foreground = DashTheme.text })
            panel.add(line, BorderLayout.SOUTH)
        }
        return panel
    }

    private fun chip(c: StatChip): JComponent {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.background = DashTheme.chipFill
        p.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(DashTheme.line),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )
        val v = JLabel(c.value)
        v.font = v.font.deriveFont(Font.BOLD, v.font.size + 10f)
        v.foreground = DashTheme.tone(c.tone)
        v.alignmentX = Component.LEFT_ALIGNMENT
        val l = JLabel(c.label.uppercase())
        l.font = l.font.deriveFont(l.font.size - 1f)
        l.foreground = DashTheme.muted
        l.alignmentX = Component.LEFT_ALIGNMENT
        p.add(v); p.add(Box.createVerticalStrut(2)); p.add(l)
        return p
    }
}
