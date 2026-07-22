package com.authzmatrix.handler

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import com.authzmatrix.core.AppContext
import java.awt.Component
import javax.swing.JMenuItem

/**
 * Right-click "send to AuthZ Matrix" from Proxy history, Repeater, target, etc. — the
 * manual trigger mode.
 */
class AuthzContextMenu(private val ctx: AppContext) : ContextMenuItemsProvider {

    override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
        val requests = collect(event)
        if (requests.isEmpty()) return emptyList()

        val autoLower = JMenuItem("AuthZ Matrix: probar con menor privilegio (auto)")
        autoLower.addActionListener {
            com.authzmatrix.ui.AutoLowerPrivTester.run(ctx, requests)
        }

        val asRole = JMenuItem("AuthZ Matrix: probar esta acción como… (elegir roles)")
        asRole.addActionListener {
            com.authzmatrix.ui.RoleTestDialog.open(ctx, requests)
        }

        val allText = if (requests.size > 1)
            "AuthZ Matrix: test ${requests.size} peticiones (todas las personas)"
        else "AuthZ Matrix: test (todas las personas)"
        val item = JMenuItem(allText)
        item.addActionListener {
            requests.forEach { ctx.submitTest(it) }
            ctx.api.logging().logToOutput("AuthZ Matrix: queued ${requests.size} request(s)")
        }

        val idor = JMenuItem("AuthZ Matrix: IDOR / param tampering…")
        idor.addActionListener {
            com.authzmatrix.ui.IdorDialog.open(ctx, requests.first())
        }

        val jwtAttacks = JMenuItem("AuthZ Matrix: JWT attacks (none/strip/escala)…")
        jwtAttacks.addActionListener {
            com.authzmatrix.ui.JwtAttackDialog.open(ctx, requests.first())
        }

        val asRefresh = JMenuItem("AuthZ Matrix: usar como refresh de una persona…")
        asRefresh.addActionListener {
            com.authzmatrix.ui.RefreshAssignDialog.open(ctx, requests.first())
        }
        return listOf(autoLower, asRole, item, idor, jwtAttacks, asRefresh)
    }

    private fun collect(event: ContextMenuEvent): List<burp.api.montoya.http.message.requests.HttpRequest> {
        val out = ArrayList<burp.api.montoya.http.message.requests.HttpRequest>()
        event.selectedRequestResponses().forEach { rr: HttpRequestResponse ->
            rr.request()?.let { out += it }
        }
        val editor = event.messageEditorRequestResponse()
        if (editor.isPresent) {
            editor.get().requestResponse().request()?.let {
                if (out.none { r -> r === it }) out += it
            }
        }
        return out
    }
}
