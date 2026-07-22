package com.authzmatrix.ui

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.Jwt
import com.authzmatrix.model.Persona
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * Rank personas by privilege with a simple reorderable list (top = most privileged). On accept it
 * writes back their `level` (top = 0, then 10, 20…), which drives "probar con menor privilegio".
 */
object PrivilegeOrderDialog {

    fun open(ctx: AppContext) {
        val personas = ctx.store.personas.sortedBy { it.level }
        if (personas.size < 2) {
            JOptionPane.showMessageDialog(null, "Necesitas al menos 2 personas para ordenar privilegios.",
                "AuthZ Matrix", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        val model = DefaultListModel<Persona>()
        personas.forEach { model.addElement(it) }
        val list = JList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            cellRenderer = PersonaRenderer()
        }

        fun move(delta: Int) {
            val i = list.selectedIndex
            if (i < 0) return
            val j = i + delta
            if (j < 0 || j >= model.size) return
            val el = model.remove(i)
            model.add(j, el)
            list.selectedIndex = j
        }
        val up = JButton("▲ Subir (más privilegio)").apply { addActionListener { move(-1) } }
        val down = JButton("▼ Bajar (menos privilegio)").apply { addActionListener { move(1) } }
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(up); add(Box.createVerticalStrut(6)); add(down)
        }

        val scroll = JScrollPane(list).apply { preferredSize = Dimension(360, 220) }

        val panel = JPanel(BorderLayout(8, 8))
        panel.add(JLabel("<html><b>Ordena los roles por privilegio.</b><br>" +
            "Arriba = MÁS privilegio · Abajo = MENOS.<br>" +
            "«probar con menor privilegio» reenviará cada acción a los que estén por <i>debajo</i>.</html>"),
            BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(buttons, BorderLayout.EAST)

        val ok = JOptionPane.showConfirmDialog(null, panel, "Ordenar privilegios",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return

        for (i in 0 until model.size) {
            model[i].level = i * 10
        }
        ctx.store.fireChanged()
        ctx.api.logging().logToOutput("AuthZ Matrix: privilegios reordenados (${model.size} roles).")
    }

    private class PersonaRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is Persona) {
                val tag = if (value.anonymous) " (anónimo)" else Jwt.parse(value.token)?.owner?.let { " · $it" } ?: ""
                text = "${index + 1}.  ${value.name}$tag"
            }
            return c
        }
    }
}
