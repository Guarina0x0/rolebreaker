package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.Jwt
import com.authzmatrix.model.Persona
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
import javax.swing.SwingUtilities

/**
 * Assigns a login/refresh request (the one you right-clicked) to a persona, so its token can be
 * renewed by re-login when it expires and no fresh token flows through the proxy.
 */
object RefreshAssignDialog {

    private class PItem(val persona: Persona) {
        override fun toString() = persona.name
    }

    fun open(ctx: AppContext, request: HttpRequest) {
        val personas = ctx.store.personas.filter { !it.anonymous }
        if (personas.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No hay personas. Escanea el HTTP history primero.",
                "AuthZ Matrix", JOptionPane.WARNING_MESSAGE)
            return
        }

        val combo = JComboBox(personas.map { PItem(it) }.toTypedArray())
        val field = JTextField(18)
        val testNow = JCheckBox("Probar ahora (re-login inmediato)", true)

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
        }
        var r = 0
        fun row(label: String, comp: JComponent) {
            c.gridx = 0; c.gridy = r; c.weightx = 0.0; panel.add(JLabel(label), c)
            c.gridx = 1; c.gridy = r; c.weightx = 1.0; panel.add(comp, c); r++
        }
        row("Petición:", JLabel("${request.method()} ${request.pathWithoutQuery()}"))
        row("Asignar a persona:", combo)
        row("Campo JSON del token:", field)
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2
        panel.add(JLabel("<html><i>Vacío = coge el primer JWT de la respuesta. Ej: <code>data.access_token</code></i></html>"), c)
        c.gridwidth = 1
        c.gridx = 1; c.gridy = r++; panel.add(testNow, c)

        val ok = JOptionPane.showConfirmDialog(null, panel, "Refresh / re-login de persona",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return

        val p = (combo.selectedItem as PItem).persona
        p.refreshRequest = request
        p.refreshTokenField = field.text.trim().ifBlank { null }
        ctx.store.fireChanged() // persist

        if (!testNow.isSelected) {
            ctx.api.logging().logToOutput("AuthZ Matrix: refresh asignado a '${p.name}'.")
            return
        }
        Thread({
            val tok = ctx.refreshEngine.refresh(p)
            SwingUtilities.invokeLater {
                if (tok == null) {
                    JOptionPane.showMessageDialog(null, "No se obtuvo token de la respuesta.\n" +
                        "Revisa el campo JSON o que la respuesta contenga un JWT.", "AuthZ Matrix",
                        JOptionPane.WARNING_MESSAGE)
                } else {
                    p.token = tok
                    Jwt.parse(tok)?.sub?.let { p.ownerSub = it }
                    ctx.store.capture(tok, "re-login de ${p.name}")
                    val info = Jwt.parse(tok)
                    JOptionPane.showMessageDialog(null, "Re-login OK.\n${info?.summary() ?: "token opaco"}",
                        "AuthZ Matrix", JOptionPane.INFORMATION_MESSAGE)
                }
            }
        }, "authz-refresh-test").start()
    }
}
