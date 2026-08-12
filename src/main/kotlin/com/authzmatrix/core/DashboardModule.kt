package com.authzmatrix.core

import javax.swing.JComponent

/**
 * A pluggable panel hosted by the dashboard shell (left sidebar → card panel). Each module owns a
 * Swing component and reports a lightweight [ModuleStatus] the Home dashboard renders as a card.
 *
 * Keeping modules behind this small contract is what lets the private PwnDoc module live only on
 * the `main` branch (registered via [ModuleRegistry.extras]) while the public build ships without
 * it — the shell code is identical on both.
 */
interface DashboardModule {
    /** Stable id (used for the CardLayout key). Not shown to the user. */
    val id: String

    /** Short display name for the sidebar. Should be i18n-aware (call I18n.t). */
    fun title(): String

    /** Optional emoji/icon glyph shown next to the title in the sidebar. */
    fun icon(): String = ""

    /** The module's root component. Built once; reused across navigation. */
    fun component(): JComponent

    /** Live snapshot for the Home dashboard card. Cheap to compute; called on refresh. */
    fun status(): ModuleStatus
}

/**
 * An extra right-click action contributed to the access-matrix popup by an optional module (e.g.
 * the private PwnDoc module registers "Send to PwnDoc"). The public build registers none, so the
 * matrix menu is unchanged there. [run] receives the selected row and the selected cell's result
 * (null when the baseline column is selected).
 */
class MatrixRowAction(
    val label: () -> String,
    val run: (com.authzmatrix.model.MatrixRow, com.authzmatrix.model.AccessResult?) -> Unit,
)

/** Visual tone for a stat/status line, mapped to a colour by the renderer. */
enum class Tone { NEUTRAL, GOOD, WARN, BAD }

/** A single labelled number/value shown as a chip inside a module's Home card. */
data class StatChip(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL)

/**
 * What a module reports to the Home dashboard: a row of stat chips plus an optional one-line
 * status message (e.g. "PwnDoc: connected as alice").
 */
data class ModuleStatus(
    val chips: List<StatChip> = emptyList(),
    val line: String? = null,
    val lineTone: Tone = Tone.NEUTRAL,
)
