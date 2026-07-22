package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.I18n
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
            JOptionPane.showMessageDialog(ctx.uiFrame(), I18n.t("idor.none"),
                I18n.t("idor.title"), JOptionPane.INFORMATION_MESSAGE)
            return
        }

        val combo = JComboBox(cands.map { CandItem(it) }.toTypedArray())
        val values = JTextField(36)
        val includeOriginal = JCheckBox(I18n.t("idor.includeOriginal"), true)

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
        }
        var r = 0
        fun addRow(label: String, comp: JComponent) {
            c.gridx = 0; c.gridy = r; c.weightx = 0.0; panel.add(JLabel(label), c)
            c.gridx = 1; c.gridy = r; c.weightx = 1.0; panel.add(comp, c); r++
        }
        addRow(I18n.t("idor.identifier"), combo)
        addRow(I18n.t("idor.altValues"), values)
        c.gridx = 1; c.gridy = r++; panel.add(includeOriginal, c)
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2
        panel.add(JLabel(I18n.t("idor.hint")), c)

        val ok = JOptionPane.showConfirmDialog(ctx.uiFrame(), panel, I18n.t("idor.dialogTitle"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return

        val cand = (combo.selectedItem as CandItem).cand
        val alts = values.text.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (alts.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.uiFrame(), I18n.t("idor.pickValue"), I18n.t("idor.title"),
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
        ctx.api.logging().logToOutput(I18n.t("idor.log", alts.size, cand.display()))
    }
}
