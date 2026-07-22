package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.I18n
import javax.swing.JOptionPane

/**
 * Manual/batch driver for the lower-privilege test: for each request, replay it as the personas
 * less privileged than its own identity (logic lives in [AppContext.submitLowerPriv]).
 */
object AutoLowerPrivTester {

    fun run(ctx: AppContext, requests: List<HttpRequest>) {
        if (requests.isEmpty()) return
        if (ctx.store.personas.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.uiFrame(), I18n.t("lower.noPersonas"),
                I18n.t("dlg.title"), JOptionPane.WARNING_MESSAGE)
            return
        }

        var tested = 0
        for (req in requests) {
            if (ctx.submitLowerPriv(req)) tested++
        }

        ctx.api.logging().logToOutput(I18n.t("lower.log", tested))
        if (tested == 0) {
            JOptionPane.showMessageDialog(ctx.uiFrame(), I18n.t("lower.none"),
                I18n.t("dlg.title"), JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
