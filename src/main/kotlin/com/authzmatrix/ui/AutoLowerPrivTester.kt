package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import javax.swing.JOptionPane

/**
 * Manual/batch driver for the lower-privilege test: for each request, replay it as the personas
 * less privileged than its own identity (logic lives in [AppContext.submitLowerPriv]).
 */
object AutoLowerPrivTester {

    fun run(ctx: AppContext, requests: List<HttpRequest>) {
        if (requests.isEmpty()) return
        if (ctx.store.personas.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                "No hay personas. Escanea el HTTP history y crea personas por rol primero.",
                "AuthZ Matrix", JOptionPane.WARNING_MESSAGE)
            return
        }

        var tested = 0
        for (req in requests) {
            if (ctx.submitLowerPriv(req)) tested++
        }

        ctx.api.logging().logToOutput(
            "AuthZ Matrix (menor-priv): $tested petición(es) reenviadas a roles de menor nivel.")
        if (tested == 0) {
            JOptionPane.showMessageDialog(null,
                "Ninguna petición tenía roles de menor privilegio que probar.\n" +
                    "Revisa los niveles de las personas ('Ordenar privilegios…').",
                "AuthZ Matrix", JOptionPane.INFORMATION_MESSAGE)
        }
    }
}
