package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.CookieHeader
import com.authzmatrix.core.I18n
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/**
 * "Use this session as persona": captures the Cookie header of the selected request as a
 * cookie-bundle persona. This is the quickest way to configure roles on apps that authenticate
 * with opaque session cookies instead of JWTs.
 */
object SessionPersonaDialog {

    fun open(ctx: AppContext, request: HttpRequest) {
        val cookies = CookieHeader.parse(request.headerValue("Cookie"))
        if (cookies.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.uiFrame(), I18n.t("session.noCookies"),
                I18n.t("session.title"), JOptionPane.WARNING_MESSAGE)
            return
        }

        val name = JTextField("role${ctx.store.personas.count { !it.anonymous } + 1}", 24)
        val area = JTextArea(CookieHeader.serialize(cookies), 5, 60)
        area.lineWrap = true
        area.wrapStyleWord = true

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        val nameRow = JPanel(BorderLayout(6, 0))
        nameRow.add(JLabel(I18n.t("session.name")), BorderLayout.WEST)
        nameRow.add(name, BorderLayout.CENTER)
        panel.add(nameRow)
        panel.add(Box.createVerticalStrut(6))
        panel.add(JLabel(I18n.t("session.cookies")))
        panel.add(JScrollPane(area).apply { preferredSize = Dimension(520, 140) })
        panel.add(JLabel(I18n.t("session.hint")))

        val ok = JOptionPane.showConfirmDialog(
            ctx.uiFrame(), panel, I18n.t("session.title"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
        )
        if (ok != JOptionPane.OK_OPTION) return

        val parsed = CookieHeader.parse(area.text)
        if (parsed.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.uiFrame(), I18n.t("session.noCookies"),
                I18n.t("session.title"), JOptionPane.WARNING_MESSAGE)
            return
        }
        val p = Persona(
            name = name.text.trim().ifBlank { "session" },
            location = TokenLocation.COOKIE,
        )
        p.cookies.putAll(parsed)
        ctx.store.addPersona(p)
        ctx.api.logging().logToOutput(I18n.t("session.created", p.name))
    }
}
