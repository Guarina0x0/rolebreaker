package com.authzmatrix

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.I18n
import com.authzmatrix.handler.AuthzContextMenu
import com.authzmatrix.handler.ProxyHttpHandler
import com.authzmatrix.ui.MainTab

/**
 * Entry point. Burp discovers this class (implements BurpExtension) and calls initialize().
 */
class AuthzMatrixExtension : BurpExtension {

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("RoleBreaker")

        val ctx = AppContext(api)
        val tab = MainTab(ctx)

        api.userInterface().registerSuiteTab("RoleBreaker", tab.component())
        api.http().registerHttpHandler(ProxyHttpHandler(ctx))
        api.userInterface().registerContextMenuItemsProvider(AuthzContextMenu(ctx))
        api.extension().registerUnloadingHandler { ctx.shutdown() }

        api.logging().logToOutput(I18n.t("ext.load"))
    }
}
