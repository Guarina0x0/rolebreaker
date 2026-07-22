package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.IdCandidate
import com.authzmatrix.core.IdorAnalyzer
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * IDOR / parameter-tampering dialog. Pick an identifier in the request, give alternative values
 * (e.g. another user's id), and each variant is replayed against every persona → the matrix then
 * shows both vertical (role) and horizontal (resource) access.
 */
object IdorDialog {

    private class CandItem(val cand: IdCandidate) {
        override fun toString() = cand.display()
    }

    fun open(ctx: AppContext, request: HttpRequest) {
        val cands = IdorAnalyzer.detect(request)
        if (cands.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                "No se detectaron identificadores (numéricos/UUID) en query, body o path de esta petición.",
                "IDOR", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        val combo = JComboBox(cands.map { CandItem(it) }.toTypedArray())
        val values = JTextField(36)
        val includeOriginal = JCheckBox("Incluir el valor original como fila de referencia", true)

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
        }
        var r = 0
        fun addRow(label: String, comp: JComponent) {
            c.gridx = 0; c.gridy = r; c.weightx = 0.0; panel.add(JLabel(label), c)
            c.gridx = 1; c.gridy = r; c.weightx = 1.0; panel.add(comp, c); r++
        }
        addRow("Identificador:", combo)
        addRow("Valores alternativos (coma/espacio):", values)
        c.gridx = 1; c.gridy = r++; panel.add(includeOriginal, c)
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2
        panel.add(JLabel("<html><i>Cada valor se prueba con todas las personas activas.</i></html>"), c)

        val ok = JOptionPane.showConfirmDialog(null, panel, "IDOR / parameter tampering",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return

        val cand = (combo.selectedItem as CandItem).cand
        val alts = values.text.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (alts.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Introduce al menos un valor alternativo.", "IDOR",
                JOptionPane.WARNING_MESSAGE)
            return
        }

        val base = "${request.method()} ${request.pathWithoutQuery()}"
        if (includeOriginal.isSelected) {
            ctx.submitTest(request, "$base [${cand.name}=${cand.value}]")
        }
        for (alt in alts) {
            val mutated = IdorAnalyzer.mutate(request, cand, alt)
            ctx.submitTest(mutated, "$base [${cand.name}:${cand.value}→$alt]")
        }
        ctx.api.logging().logToOutput("AuthZ Matrix: IDOR — ${alts.size} variante(s) encoladas sobre ${cand.display()}")
    }
}
