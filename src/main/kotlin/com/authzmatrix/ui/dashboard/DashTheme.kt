package com.authzmatrix.ui.dashboard

import com.authzmatrix.core.Tone
import java.awt.Color
import javax.swing.UIManager

/**
 * Colours for the dashboard shell, derived from the active Burp look-and-feel so the UI adapts to
 * both the light and dark themes. Only the accent is fixed (it reads well on either background).
 */
object DashTheme {
    val accent = Color(0x4F, 0x8C, 0xFF)

    private val base: Color get() = UIManager.getColor("Panel.background") ?: Color(0x2B, 0x2B, 0x2B)
    val text: Color get() = UIManager.getColor("Label.foreground") ?: Color(0xE6, 0xE6, 0xE6)

    private val dark: Boolean get() = luminance(base) < 0.5

    /** Card/surface background: slightly raised from the base (lighter on dark, darker on light). */
    val card: Color get() = shade(base, if (dark) 0.06f else -0.035f)

    /** Sidebar / top-bar background: slightly recessed from the base. */
    val sidebar: Color get() = shade(base, if (dark) -0.035f else -0.02f)

    /** Selected nav item background: a subtle accent tint over the sidebar. */
    val selected: Color get() = blend(accent, sidebar, 0.78f)

    /** Muted secondary text. */
    val muted: Color get() = blend(text, base, 0.42f)

    /** Hairline separators / borders. */
    val line: Color get() = Color(text.red, text.green, text.blue, 0x24)

    /** Faint chip fill. */
    val chipFill: Color get() = shade(base, if (dark) 0.11f else -0.05f)

    fun tone(t: Tone): Color = when (t) {
        Tone.GOOD -> Color(0x3B, 0xA5, 0x5D)
        Tone.WARN -> Color(0xE0, 0x8A, 0x1E)
        Tone.BAD -> Color(0xD1, 0x45, 0x45)
        Tone.NEUTRAL -> text
    }

    private fun luminance(c: Color) = (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) / 255.0

    /** amount > 0 → toward white, < 0 → toward black. */
    private fun shade(c: Color, amount: Float): Color {
        val target = if (amount >= 0) 255 else 0
        val a = Math.abs(amount)
        fun mix(v: Int) = (v + (target - v) * a).toInt().coerceIn(0, 255)
        return Color(mix(c.red), mix(c.green), mix(c.blue))
    }

    /** Blend [fg] over [bg] with [fgWeight] toward bg (0 = all fg, 1 = all bg). */
    private fun blend(fg: Color, bg: Color, bgWeight: Float): Color {
        val w = bgWeight.coerceIn(0f, 1f)
        fun mix(f: Int, b: Int) = (f * (1 - w) + b * w).toInt().coerceIn(0, 255)
        return Color(mix(fg.red, bg.red), mix(fg.green, bg.green), mix(fg.blue, bg.blue))
    }
}
