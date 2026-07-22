package com.authzmatrix.core

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.model.MatrixRow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires the pieces together and is shared by the handlers and the UI. The UI installs
 * itself as [rowSink] so replayed results land in the matrix table.
 */
class AppContext(val api: MontoyaApi) {

    val store = PersonaStore()
    val detector = EnforcementDetector()
    val capture = TokenCapture(store)
    val replay = ReplayEngine(api, store, detector)
    val historyScanner = HistoryScanner(api, capture, store)
    val refreshEngine = RefreshEngine(api)

    /** UI installs this to receive a line per launched request. */
    @Volatile
    var activitySink: (com.authzmatrix.model.ActivityEntry) -> Unit = {}

    private val executor = Executors.newFixedThreadPool(4)

    /** Auto mode: replay every in-scope proxied request against all personas. Off by default. */
    val autoMode = AtomicBoolean(false)

    /** Continuous lower-privilege mode: browse as a high-priv user and each new in-scope request is
     *  replayed in the background as the lower-privilege roles. */
    val autoLowerMode = AtomicBoolean(false)

    /** Endpoints already tested in each auto mode, so we don't retest on every hit. */
    private val seenInAuto = ConcurrentHashMap.newKeySet<String>()
    private val seenLower = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var rowSink: (MatrixRow) -> Unit = {}

    init {
        // Restore saved language (default English) before the UI is built.
        if (api.persistence().preferences().getString(LANG_KEY) == "ES") I18n.initLang(I18n.Lang.ES)

        // Restore saved personas (if any) and auto-persist on every change.
        PersonaPersistence.load(api.persistence().preferences())?.let { saved ->
            store.personas.clear()
            store.personas.addAll(saved)
        }
        store.onChange { PersonaPersistence.save(api.persistence().preferences(), store.personas) }
        replay.onActivity = { entry -> activitySink(entry) }
        store.onPersonaRefreshed = { p, source ->
            api.logging().logToOutput("RoleBreaker: token for '${p.name}' refreshed (sub=${p.ownerSub}) from $source")
        }
        replay.refresher = { p -> maybeRefresh(p) }
    }

    /** Re-login a persona if its token is expired and it has a refresh request. Thread-safe per persona. */
    fun maybeRefresh(p: com.authzmatrix.model.Persona) {
        if (p.anonymous || p.refreshRequest == null) return
        synchronized(p) {
            val cur = Jwt.parse(p.token)
            if (cur != null && !cur.isExpired()) return // still valid — another thread may have refreshed
            val tok = refreshEngine.refresh(p) ?: return
            if (tok == p.token) return
            p.token = tok
            Jwt.parse(tok)?.sub?.let { p.ownerSub = it }
            store.capture(tok, I18n.t("refresh.reloginSrc", p.name)) // adds to catalog + persists + refreshes UI
            api.logging().logToOutput("RoleBreaker: '${p.name}' re-logged in, new token obtained")
        }
    }

    /** Force-refresh every persona whose token is expired (used by the toolbar button). */
    fun refreshAllExpired() {
        executor.submit { store.personas.forEach { maybeRefresh(it) } }
    }

    /** Queue a request for replay against the given personas (default: all enabled). */
    fun submitTest(req: HttpRequest, label: String? = null, personas: List<com.authzmatrix.model.Persona>? = null) {
        executor.submit {
            try {
                rowSink(replay.test(req, label, personas ?: store.enabledPersonas()))
            } catch (e: Exception) {
                api.logging().logToError("Test failed: ${e.message}")
            }
        }
    }

    /** Send a forged/raw request once (no persona swap) and log it live. */
    fun submitForged(req: HttpRequest, what: String) {
        executor.submit {
            try {
                replay.sendOnce(req, what)
            } catch (e: Exception) {
                api.logging().logToError("Forged send failed: ${e.message}")
            }
        }
    }

    /** Auto-mode entry point (all personas): dedupes by method+URL before submitting. */
    fun autoTest(req: HttpRequest) {
        if (!autoMode.get()) return
        val key = "${req.method()} ${req.url()}"
        if (seenInAuto.add(key)) submitTest(req)
    }

    /** Continuous lower-priv entry point: dedupes, then replays as the lower-privilege roles. */
    fun autoLowerTest(req: HttpRequest) {
        if (!autoLowerMode.get()) return
        val key = "${req.method()} ${req.url()}"
        if (seenLower.add(key)) submitLowerPriv(req)
    }

    /** Replay [req] against the personas less privileged than its own identity. Returns true if it
     *  actually queued a test (i.e. the request's role was identified and has lower roles). */
    fun submitLowerPriv(req: HttpRequest): Boolean {
        val targets = lowerPrivTargets(req)
        if (targets.isEmpty()) return false
        val label = "${com.authzmatrix.core.FindingsAnalyzer.LOWER_PRIV_MARKER} ${req.method()} ${req.pathWithoutQuery()}"
        submitTest(req, label, targets)
        return true
    }

    /**
     * Enabled personas less privileged than the request's own identity (anon always qualifies).
     * Empty if the request's identity can't be matched to a persona — we won't guess "lower" and
     * risk testing the most-privileged role as if it were lower.
     */
    fun lowerPrivTargets(req: HttpRequest): List<com.authzmatrix.model.Persona> {
        val current = currentPersonaFor(req) ?: return emptyList()
        val personas = store.personas
        val levelsConfigured = personas.filter { !it.anonymous }.map { it.level }.distinct().size > 1
        val candidates = personas.filter { it.enabled && it !== current }
        return if (levelsConfigured) candidates.filter { it.level > current.level || it.anonymous } else candidates
    }

    /** Persona whose identity matches the request's token (by sub, then role). */
    fun currentPersonaFor(req: HttpRequest): com.authzmatrix.model.Persona? {
        val info = extractToken(req)?.let { Jwt.parse(it) } ?: return null
        return store.personas.firstOrNull { !it.anonymous && (it.ownerSub == info.sub || Jwt.parse(it.token)?.sub == info.sub) }
            ?: store.personas.firstOrNull { !it.anonymous && Jwt.parse(it.token)?.role == info.role }
    }

    private fun extractToken(req: HttpRequest): String? {
        req.headerValue("Authorization")?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                val t = auth.substring(7).trim()
                if (Jwt.looksLikeJwt(t)) return t
            }
        }
        return Jwt.findAll(req.toString()).firstOrNull()
    }

    fun resetAutoSeen() { seenInAuto.clear(); seenLower.clear() }

    /** Persist the chosen UI language so it survives Burp restarts. */
    fun persistLang(lang: I18n.Lang) {
        api.persistence().preferences().setString(LANG_KEY, lang.name)
    }

    fun shutdown() = executor.shutdownNow()

    companion object {
        private const val LANG_KEY = "rolebreaker.lang"
    }
}
