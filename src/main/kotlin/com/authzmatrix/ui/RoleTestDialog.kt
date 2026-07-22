package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.Jwt
import com.authzmatrix.model.Persona
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * "Probar esta acción como…" — take an action that a high-priv identity can do (the request you
 * send in) and re-run it as the lower role(s) you pick, to see whether they can do it too.
 */
object RoleTestDialog {

    fun open(ctx: AppContext, requests: List<HttpRequest>) {
        if (requests.isEmpty()) return
        val personas = ctx.store.personas.toList()
        if (personas.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No hay personas definidas. Escanea el HTTP history primero.",
                "AuthZ Matrix", JOptionPane.WARNING_MESSAGE)
            return
        }
        val request = requests.first()

        val checks = personas.map { p ->
            JCheckBox(describe(p), true) to p
        }

        val list = JPanel()
        list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
        checks.forEach { list.add(it.first) }

        val toggle = JButton("Marcar / desmarcar todo")
        toggle.addActionListener {
            val target = checks.any { !it.first.isSelected }
            checks.forEach { it.first.isSelected = target }
        }

        val target = if (requests.size == 1) "${esc(request.method())} ${esc(request.pathWithoutQuery())}"
        else "${requests.size} peticiones"
        val panel = JPanel(BorderLayout(0, 6))
        panel.add(JLabel("<html>Reenvía <b>$target</b> con el token de cada rol marcado." +
            "<br>La respuesta original (baseline) es la referencia.</html>"),
            BorderLayout.NORTH)
        val scroll = JScrollPane(list)
        scroll.preferredSize = Dimension(480, 220)
        scroll.border = BorderFactory.createTitledBorder("Roles a probar")
        panel.add(scroll, BorderLayout.CENTER)
        panel.add(Box.createHorizontalBox().apply { add(toggle) }, BorderLayout.SOUTH)

        val ok = JOptionPane.showConfirmDialog(null, panel, "Probar acción como…",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return

        val selected = checks.filter { it.first.isSelected }.map { it.second }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Marca al menos un rol.", "AuthZ Matrix", JOptionPane.WARNING_MESSAGE)
            return
        }
        requests.forEach { req ->
            ctx.submitTest(req, "${req.method()} ${req.pathWithoutQuery()}", selected)
        }
        ctx.api.logging().logToOutput(
            "AuthZ Matrix: probando ${requests.size} petición(es) como ${selected.size} rol(es).")
    }

    private fun describe(p: Persona): String {
        if (p.anonymous) return "${p.name}  (anónimo)"
        val info = Jwt.parse(p.token)
        val state = when {
            p.token.isBlank() -> "sin token"
            info == null -> "token opaco"
            info.isExpired() -> "⚠ EXPIRADO"
            else -> "ok"
        }
        val owner = info?.owner?.let { " · $it" } ?: ""
        return "${p.name}$owner  [$state]"
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
