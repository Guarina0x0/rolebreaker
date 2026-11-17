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

    /** Extra matrix right-click actions contributed by optional modules (empty on the public build). */
    val matrixRowActions = java.util.concurrent.CopyOnWriteArrayList<MatrixRowAction>()

    /** UI installs this to receive a line per launched request. */
    @Volatile
    var activitySink: (com.authzmatrix.model.ActivityEntry) -> Unit = {}

    private val executor = Executors.newFixedThreadPool(4)

    /** Auto mode: replay every in-scope proxied request against all personas. Off by default. */
    val autoMode = AtomicBoolean(false)

    /** Continuous lower-privilege mode: browse as a high-priv user and each new in-scope request is
     *  replayed in the background as the lower-privilege roles. */
    val autoLowerMode = AtomicBoolean(false)

    /** Auto modes may replay POST/PUT/PATCH/DELETE only after an explicit opt-in. */
    val allowStateChanging = AtomicBoolean(false)

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

    /**
     * Re-login a persona if needed. JWT personas refresh when their token is expired; cookie
     * personas refresh when a JWT inside the bundle is expired (or when [force] is set, e.g. the
     * "Test now" action in the refresh dialog). Thread-safe per persona.
     *
     * @return true when credentials were actually updated.
     */
    fun refreshPersona(p: com.authzmatrix.model.Persona, force: Boolean = false): Boolean {
        if (p.anonymous || p.refreshRequest == null) return false
        synchronized(p) {
            if (p.usesCookies) {
                val identity = CookieSession.identitySubset(p.cookies)
                val values = if (identity.isNotEmpty()) identity.values else p.cookies.values
                val expiredJwt = values.any { Jwt.parse(it)?.isExpired() == true }
                if (!force && !expiredJwt) return false
                val fresh = refreshEngine.refreshCookies(p) ?: return false
                if (fresh.isEmpty()) return false
                p.cookies.putAll(fresh)
                p.location = com.authzmatrix.model.TokenLocation.COOKIE
                store.captureCookie(p.cookies, I18n.t("refresh.reloginSrc", p.name))
                api.logging().logToOutput("RoleBreaker: '${p.name}' cookies refreshed")
                return true
            }

            val cur = Jwt.parse(p.token)
            if (!force && cur != null && !cur.isExpired()) return false
            val tok = refreshEngine.refresh(p) ?: return false
            if (tok == p.token) return false
            p.token = tok
            Jwt.parse(tok)?.sub?.let { p.ownerSub = it }
            store.capture(tok, I18n.t("refresh.reloginSrc", p.name)) // adds to catalog + persists + refreshes UI
            api.logging().logToOutput("RoleBreaker: '${p.name}' re-logged in, new token obtained")
            return true
        }
    }

    /** Backwards-compatible helper used by the replay engine. */
    fun maybeRefresh(p: com.authzmatrix.model.Persona) {
        refreshPersona(p)
    }

    /** Force-refresh every persona whose token is expired (used by the toolbar button). */
    fun refreshAllExpired() {
        executor.submit { store.personas.forEach { maybeRefresh(it) } }
    }

    /** Queue a request for replay against the given personas (default: all enabled). */
    fun submitTest(
        req: HttpRequest,
        label: String? = null,
        personas: List<com.authzmatrix.model.Persona>? = null,
        token: SweepToken? = null,
        onDone: (TestOutcome) -> Unit = { _ -> },
    ) {
        executor.submit {
            if (token?.cancelled == true) {
                onDone(TestOutcome.SKIPPED)
                return@submit
            }
            try {
                rowSink(replay.test(req, label, personas ?: store.enabledPersonas()))
                onDone(TestOutcome.TESTED)
            } catch (e: Exception) {
                api.logging().logToError("Test failed: ${e.message}")
                onDone(TestOutcome.ERROR)
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
        if (!autoMode.get() || !isAutoTargetAllowed(req)) return
        val key = "${req.method()} ${req.url()}"
        if (remember(seenInAuto, key)) submitTest(req)
    }

    /** Continuous lower-priv entry point: dedupes, then replays as the lower-privilege roles. */
    fun autoLowerTest(req: HttpRequest) {
        if (!autoLowerMode.get() || !isAutoTargetAllowed(req)) return
        val key = "${req.method()} ${req.url()}"
        if (remember(seenLower, key)) submitLowerPriv(req)
    }

    /** Automatic modes skip state-changing methods and destructive paths unless explicitly allowed. */
    fun isAutoTargetAllowed(req: HttpRequest): Boolean =
        allowStateChanging.get() || SweepPolicy.isSafe(req.method(), req.url())

    /** Bounded dedupe: after [MAX_SEEN] distinct URLs, reset rather than grow forever. */
    private fun remember(seen: MutableSet<String>, key: String): Boolean {
        if (seen.size >= MAX_SEEN) seen.clear()
        return seen.add(key)
    }

    /** Replay [req] against the personas less privileged than its own identity. Returns true if it
     *  actually queued a test (i.e. the request's role was identified and has lower roles). */
    fun submitLowerPriv(
        req: HttpRequest,
        token: SweepToken? = null,
        onDone: (TestOutcome) -> Unit = { _ -> },
    ): Boolean {
        if (token?.cancelled == true) {
            onDone(TestOutcome.SKIPPED)
            return false
        }
        val targets = lowerPrivTargets(req)
        if (targets.isEmpty()) {
            onDone(TestOutcome.SKIPPED)
            return false
        }
        val label = "${com.authzmatrix.core.FindingsAnalyzer.LOWER_PRIV_MARKER} ${req.method()} ${req.pathWithoutQuery()}"
        submitTest(req, label, targets, token, onDone)
        return true
    }

    /**
     * Enabled personas less privileged than the request's own identity (anon always qualifies).
     * Empty if the request's identity can't be matched to a persona, or if several roles share the
     * same level: in that case we would be guessing which ones are "lower" and could label a more
     * privileged role as a finding. The user can order privileges explicitly.
     */
    fun lowerPrivTargets(req: HttpRequest): List<com.authzmatrix.model.Persona> {
        val current = currentPersonaFor(req) ?: return emptyList()
        val personas = store.personas
        val nonAnon = personas.filter { !it.anonymous }
        val levelsConfigured = nonAnon.map { it.level }.distinct().size > 1
        if (!levelsConfigured && nonAnon.size > 1) return emptyList()
        val candidates = personas.filter { it.enabled && it !== current }
        return if (levelsConfigured) candidates.filter { it.anonymous || it.level > current.level } else candidates
    }

    /** Persona whose identity matches the request's JWT or cookie session. */
    fun currentPersonaFor(req: HttpRequest): com.authzmatrix.model.Persona? {
        val info = extractToken(req)?.let { Jwt.parse(it) }
        if (info != null) {
            store.personas.firstOrNull {
                !it.anonymous && (it.ownerSub == info.sub || Jwt.parse(it.token)?.sub == info.sub)
            }?.let { return it }
            store.personas.firstOrNull { !it.anonymous && Jwt.parse(it.token)?.role == info.role }?.let { return it }
        }

        val requestCookies = CookieHeader.parse(req.headerValue("Cookie"))
        if (requestCookies.isNotEmpty()) {
            CookieSession.matchPersona(requestCookies, store.personas)?.let { return it }
        }
        return null
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

    /** Parent for dialogs — BApp Store criterion: GUI must be a child of the Burp suite frame. */
    fun uiFrame(): java.awt.Frame? = runCatching { api.userInterface().swingUtils().suiteFrame() }.getOrNull()

    fun shutdown() = executor.shutdownNow()

    companion object {
        private const val LANG_KEY = "rolebreaker.lang"

        /** Upper bound for each auto-mode dedupe set. */
        private const val MAX_SEEN = 10_000
    }
}
