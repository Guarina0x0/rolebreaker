package com.authzmatrix.handler

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.I18n
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

        val autoLower = JMenuItem(I18n.t("ctx.lowerPriv"))
        autoLower.addActionListener {
            com.authzmatrix.ui.AutoLowerPrivTester.run(ctx, requests)
        }

        val asRole = JMenuItem(I18n.t("ctx.asRole"))
        asRole.addActionListener {
            com.authzmatrix.ui.RoleTestDialog.open(ctx, requests)
        }

        val allText = if (requests.size > 1) I18n.t("ctx.testAllN", requests.size) else I18n.t("ctx.testAll")
        val item = JMenuItem(allText)
        item.addActionListener {
            requests.forEach { ctx.submitTest(it) }
            ctx.api.logging().logToOutput("RoleBreaker: queued ${requests.size} request(s)")
        }

        val idor = JMenuItem(I18n.t("ctx.idor"))
        idor.addActionListener {
            com.authzmatrix.ui.IdorDialog.open(ctx, requests.first())
        }

        val jwtAttacks = JMenuItem(I18n.t("ctx.jwtAttacks"))
        jwtAttacks.addActionListener {
            com.authzmatrix.ui.JwtAttackDialog.open(ctx, requests.first())
        }

        val asRefresh = JMenuItem(I18n.t("ctx.asRefresh"))
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
