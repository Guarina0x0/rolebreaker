package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.DashboardModule
import com.authzmatrix.core.I18n
import com.authzmatrix.core.ModuleStatus
import com.authzmatrix.core.Tone
import java.awt.BorderLayout
import java.awt.Color
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

    init {
        cards.layout = BoxLayout(cards, BoxLayout.Y_AXIS)
        cards.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        root.add(JScrollPane(cards), BorderLayout.CENTER)

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
        header.font = header.font.deriveFont(Font.BOLD, header.font.size + 4f)
        header.alignmentX = Component.LEFT_ALIGNMENT
        cards.add(header)
        cards.add(JLabel(I18n.t("home.subtitle")).apply {
            foreground = MUTED; alignmentX = Component.LEFT_ALIGNMENT
        })
        cards.add(Box.createVerticalStrut(10))

        for (m in peers()) {
            cards.add(card(m))
            cards.add(Box.createVerticalStrut(10))
        }
        cards.revalidate()
        cards.repaint()
    }

    private fun card(m: DashboardModule): JComponent {
        val st = runCatching { m.status() }.getOrDefault(ModuleStatus())
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)

        // Title row: "🔐 AuthZ Matrix" ................ [ Open → ]
        val titleRow = JPanel(BorderLayout())
        titleRow.isOpaque = false
        val name = JLabel("${m.icon()}  ${m.title()}")
        name.font = name.font.deriveFont(Font.BOLD, name.font.size + 1f)
        titleRow.add(name, BorderLayout.WEST)
        val open = JButton(I18n.t("home.open"))
        open.addActionListener { switchTo(m.id) }
        titleRow.add(open, BorderLayout.EAST)
        panel.add(titleRow, BorderLayout.NORTH)

        // Chips row.
        if (st.chips.isNotEmpty()) {
            val chips = JPanel(GridLayout(1, st.chips.size, 8, 0))
            chips.isOpaque = false
            for (c in st.chips) chips.add(chip(c.label, c.value, c.tone))
            panel.add(chips, BorderLayout.CENTER)
        }

        // Optional status line.
        st.line?.let {
            val line = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            line.isOpaque = false
            line.add(JLabel("●  ").apply { foreground = toneColor(st.lineTone) })
            line.add(JLabel(it))
            panel.add(line, BorderLayout.SOUTH)
        }
        return panel
    }

    private fun chip(label: String, value: String, tone: Tone): JComponent {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.Y_AXIS)
        p.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(6, 10, 6, 10),
        )
        val v = JLabel(value)
        v.font = v.font.deriveFont(Font.BOLD, v.font.size + 6f)
        v.foreground = toneColor(tone)
        v.alignmentX = Component.LEFT_ALIGNMENT
        val l = JLabel(label)
        l.foreground = MUTED
        l.alignmentX = Component.LEFT_ALIGNMENT
        p.add(v); p.add(l)
        return p
    }

    private fun toneColor(t: Tone): Color = when (t) {
        Tone.GOOD -> Color(0x2E, 0x7D, 0x32)
        Tone.WARN -> Color(0xE6, 0x7E, 0x00)
        Tone.BAD -> Color(0xC6, 0x28, 0x28)
        Tone.NEUTRAL -> UIColor
    }

    private companion object {
        val MUTED = Color(0x88, 0x88, 0x88)
        val BORDER = Color(0x9E, 0x9E, 0x9E, 0x60)
        val UIColor = JLabel().foreground // theme default text colour
    }
}
