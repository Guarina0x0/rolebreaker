package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.AppContext
import com.authzmatrix.core.DashboardModule
import com.authzmatrix.core.I18n
import com.authzmatrix.core.ModuleStatus
import com.authzmatrix.core.StatChip
import com.authzmatrix.core.Tone
import com.authzmatrix.ui.MainTab
import javax.swing.JComponent

/**
 * The core AuthZ / JWT tool, hosted as a dashboard module. Delegates its whole UI to the existing
 * [MainTab]; this wrapper only adds the module identity and the Home-dashboard status card.
 */
class MatrixModule(ctx: AppContext) : DashboardModule {

    private val tab = MainTab(ctx)

    override val id = "authz-matrix"
    override fun title() = I18n.t("mod.matrix")
    override fun icon() = "🔐"
    override fun component(): JComponent = tab.component() as JComponent

    override fun status(): ModuleStatus {
        val critical = tab.criticalCount()
        val findings = tab.findingCount()
        return ModuleStatus(
            chips = listOf(
                StatChip(I18n.t("home.roles"), tab.roleCount().toString()),
                StatChip(I18n.t("home.endpoints"), tab.matrixRowCount().toString()),
                StatChip(
                    I18n.t("home.findings"), findings.toString(),
                    if (findings > 0) Tone.WARN else Tone.NEUTRAL,
                ),
                StatChip(
                    I18n.t("home.critical"), critical.toString(),
                    if (critical > 0) Tone.BAD else Tone.GOOD,
                ),
            ),
        )
    }
}
