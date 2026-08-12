package com.authzmatrix.core

/**
 * Branch-specific extra modules. On the public build this returns nothing; on the private `main`
 * branch it is overridden to also register the PwnDoc module. This is the ONLY file that differs
 * between the two branches for module wiring — keep it tiny.
 */
object ModuleRegistry {
    /** Modules added after the always-present ones (Home, AuthZ Matrix). */
    fun extras(@Suppress("UNUSED_PARAMETER") ctx: AppContext): List<DashboardModule> = emptyList()
}
