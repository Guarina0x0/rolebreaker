package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.Jwt
import com.authzmatrix.core.JwtForge
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Forge JWT attack variants (alg:none, stripped signature, role escalation) from the token in a
 * request and replay them. Results appear in the activity log: ALLOWED (green->red) = the server
 * accepted a forged token → broken JWT verification.
 */
object JwtAttackDialog {

    fun open(ctx: AppContext, request: HttpRequest) {
        val tok = extractToken(request)
        if (tok == null) {
            JOptionPane.showMessageDialog(null, "No se encontró ningún JWT en esta petición.",
                "JWT attacks", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val info = Jwt.parse(tok)
        if (info == null) {
            JOptionPane.showMessageDialog(null, "El token encontrado no es un JWT parseable.",
                "JWT attacks", JOptionPane.WARNING_MESSAGE)
            return
        }

        val none = JCheckBox("alg:none (sin firma)", true)
        val strip = JCheckBox("Eliminar firma (header+payload originales)", true)
        val escalate = JCheckBox("Escalar rol", true)
        val roleClaim = JTextField(JwtForge.roleClaimKey(info) ?: "role", 12)
        val roleValue = JTextField("admin", 16)

        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
        }
        var r = 0
        fun row(label: String, comp: JComponent) {
            c.gridx = 0; c.gridy = r; c.weightx = 0.0; panel.add(JLabel(label), c)
            c.gridx = 1; c.gridy = r; c.weightx = 1.0; panel.add(comp, c); r++
        }
        row("Token detectado:", JLabel("alg=${info.alg}  role=${info.role ?: "-"}  owner=${info.owner ?: "-"}"))
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2; panel.add(none, c)
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2; panel.add(strip, c)
        c.gridx = 0; c.gridy = r++; c.gridwidth = 2; panel.add(escalate, c); c.gridwidth = 1
        row("  claim de rol:", roleClaim)
        row("  valor a inyectar:", roleValue)

        val ok = JOptionPane.showConfirmDialog(null, panel, "JWT attacks",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return

        var queued = 0
        if (none.isSelected) {
            send(ctx, request, tok, JwtForge.algNone(info), "🔴 JWT:none"); queued++
        }
        if (strip.isSelected) {
            send(ctx, request, tok, JwtForge.stripSignature(info), "🔴 JWT:strip-sig"); queued++
        }
        if (escalate.isSelected) {
            val forged = JwtForge.escalateRole(info, roleClaim.text.trim(), roleValue.text.trim())
            send(ctx, request, tok, forged, "🔴 JWT:role=${roleValue.text.trim()}"); queued++
        }
        ctx.api.logging().logToOutput("AuthZ Matrix: $queued variante(s) JWT forjada(s) enviadas — mira el log de actividad.")
    }

    private fun send(ctx: AppContext, req: HttpRequest, oldTok: String, forged: String, label: String) {
        ctx.submitForged(JwtForge.replaceToken(req, oldTok, forged), label)
    }

    private fun extractToken(req: HttpRequest): String? {
        req.headerValue("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val t = auth.substring(7).trim()
                if (Jwt.looksLikeJwt(t)) return t
            }
        }
        return Jwt.findAll(req.toString()).firstOrNull()
    }
}
