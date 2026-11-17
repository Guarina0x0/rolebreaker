package com.authzmatrix.ui

import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.core.AppContext
import com.authzmatrix.core.I18n
import com.authzmatrix.core.SweepProgress
import com.authzmatrix.core.SweepToken
import com.authzmatrix.core.TestOutcome
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Async sweep driver with progress and cancellation. [onProgress] and [onComplete] may be
     * invoked from executor threads; the caller must marshal them to the EDT.
     */
    fun runAsync(
        ctx: AppContext,
        requests: List<HttpRequest>,
        token: SweepToken,
        onProgress: (SweepProgress) -> Unit,
        onComplete: (SweepProgress) -> Unit,
    ) {
        val total = requests.size
        val done = AtomicInteger()
        val tested = AtomicInteger()
        val errors = AtomicInteger()
        val skipped = AtomicInteger()
        val completed = AtomicBoolean(false)

        fun snapshot() = SweepProgress(
            total = total,
            done = done.get(),
            tested = tested.get(),
            errors = errors.get(),
            skipped = skipped.get(),
        )

        fun complete(p: SweepProgress) {
            if (completed.compareAndSet(false, true)) onComplete(p)
        }

        if (total == 0) {
            val empty = snapshot()
            onProgress(empty)
            complete(empty)
            return
        }

        for (req in requests) {
            try {
                ctx.submitLowerPriv(req, token) { outcome ->
                    when (outcome) {
                        TestOutcome.TESTED -> tested.incrementAndGet()
                        TestOutcome.ERROR -> errors.incrementAndGet()
                        TestOutcome.SKIPPED -> skipped.incrementAndGet()
                    }
                    val d = done.incrementAndGet()
                    val p = snapshot()
                    onProgress(p)
                    if (d == total) complete(p)
                }
            } catch (_: Exception) {
                errors.incrementAndGet()
                val d = done.incrementAndGet()
                val p = snapshot()
                onProgress(p)
                if (d == total) complete(p)
            }
        }
    }
}
