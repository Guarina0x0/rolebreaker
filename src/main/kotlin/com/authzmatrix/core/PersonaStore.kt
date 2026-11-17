package com.authzmatrix.core

import com.authzmatrix.model.Persona
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An identity seen on the wire, offered to the user so they can bind it to a persona
 * instead of pasting credentials by hand (the "auto-capture" flow). For cookie sessions,
 * [token] is the canonical auth-cookie subset and [cookies] is the full bundle.
 */
class CapturedToken(
    val token: String,
    val source: String,
    val info: JwtInfo?,
    val cookies: Map<String, String> = emptyMap(),
) {
    val isCookie: Boolean get() = cookies.isNotEmpty()

    override fun equals(other: Any?) = other is CapturedToken && other.token == token
    override fun hashCode() = token.hashCode()
}

/**
 * In-memory home for personas + captured tokens. Thread-safe: the proxy handler,
 * the replay workers and the Swing EDT all touch it.
 */
class PersonaStore {

    val personas = CopyOnWriteArrayList<Persona>()
    val capturedTokens = CopyOnWriteArrayList<CapturedToken>()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Notified when a persona's token is auto-refreshed from newer traffic (persona, source). */
    @Volatile
    var onPersonaRefreshed: (Persona, String) -> Unit = { _, _ -> }

    @Volatile
    private var suppress = false

    // No pre-created personas: they are built from the HTTP history scan (syncPersonasFromTokens).

    fun onChange(l: () -> Unit) { listeners += l }

    fun fireChanged() { if (!suppress) listeners.forEach { it() } }

    /** Run [block] coalescing change notifications into a single fire at the end. */
    fun <T> batch(block: () -> T): T {
        suppress = true
        return try {
            block()
        } finally {
            suppress = false
            fireChanged()
        }
    }

    fun addPersona(p: Persona) {
        personas += p
        fireChanged()
    }

    fun removePersona(p: Persona) {
        personas.remove(p)
        fireChanged()
    }

    /** Wipe all personas (also clears the persisted set, since save is triggered by the change). */
    fun clearPersonas() {
        personas.clear()
        fireChanged()
    }

    fun enabledPersonas(): List<Persona> = personas.filter { it.enabled }

    /** Record a token seen in traffic (deduped) and auto-refresh matching personas. Returns true if new. */
    fun capture(token: String, source: String): Boolean {
        if (capturedTokens.any { it.token == token }) return false
        val info = Jwt.parse(token)
        capturedTokens += CapturedToken(token, source, info)
        trimCaptured()

        // Auto-refresh: a fresher, non-expired token of the SAME identity (sub) replaces a
        // persona's stale one, so long audits don't drift into false 401s.
        if (info?.sub != null && !info.isExpired()) {
            for (p in personas) {
                if (p.anonymous || p.token == token) continue
                // Cookie-bundle personas are maintained by captureCookie/sync; don't overwrite
                // their (unused) token field with a JWT found inside a cookie.
                if (p.usesCookies && synchronized(p) { p.cookies.isNotEmpty() }) continue
                // Match on the persona's known sub, falling back to its current token's sub
                // (so promoted/seeded personas without a stored ownerSub still refresh).
                val cur = Jwt.parse(p.token)
                val pSub = p.ownerSub ?: cur?.sub ?: continue
                if (pSub != info.sub) continue
                val fresher = cur == null || cur.isExpired() ||
                    (info.exp ?: Long.MAX_VALUE) > (cur.exp ?: Long.MIN_VALUE)
                if (fresher) {
                    p.token = token
                    p.ownerSub = info.sub
                    onPersonaRefreshed(p, source)
                }
            }
        }
        fireChanged()
        return true
    }

    fun clearCaptured() {
        capturedTokens.clear()
        fireChanged()
    }

    /**
     * Record a cookie-based session, deduped by its auth-cookie subset. Non-auth cookies from
     * newer captures are merged in, so a rotating CSRF cookie doesn't create a new identity.
     *
     * @return true when this is a new session (an update of an existing one returns false).
     */
    fun captureCookie(cookies: Map<String, String>, source: String): Boolean {
        if (cookies.isEmpty() || cookies.values.all { it.isBlank() }) return false
        // Prefer stable identity cookies for dedupe; custom-only bundles fall back to all cookies
        // so a Cookie header with no recognisable session name is still captured.
        val identity = CookieSession.identitySubset(cookies)
        val key = CookieHeader.canonical(if (identity.isNotEmpty()) identity else cookies)

        val idx = capturedTokens.indexOfFirst { it.isCookie && it.token == key }
        if (idx >= 0) {
            val existing = capturedTokens[idx]
            if (existing.cookies != cookies) {
                val merged = LinkedHashMap(existing.cookies)
                merged.putAll(cookies)
                capturedTokens[idx] = CapturedToken(key, source, null, merged)
                fireChanged()
            }
            return false
        }

        capturedTokens += CapturedToken(key, source, null, LinkedHashMap(cookies))
        trimCaptured()
        fireChanged()
        return true
    }

    /** Drop the oldest expired/opaque tokens first, then the oldest active ones. */
    private fun trimCaptured() {
        if (capturedTokens.size <= MAX_CAPTURED_TOKENS) return
        while (capturedTokens.size > MAX_CAPTURED_TOKENS) {
            val idx = capturedTokens.indexOfFirst { it.info?.isExpired() == true }
            capturedTokens.removeAt(if (idx >= 0) idx else 0)
        }
    }

    /**
     * Ensure the auto-sweep has an unauthenticated identity to replay with. Anonymous access is a
     * CRITICAL finding, but roles discovered from JWTs can never produce it on their own, so the
     * sweep adds one persona the first time and reuses it afterwards. A user-created (possibly
     * disabled) anon persona is respected: nothing is added if one already exists.
     *
     * @return true when a new anonymous persona was created.
     */
    fun ensureAnonymousPersona(): Boolean {
        if (personas.any { it.anonymous }) return false
        var name = "anonymous"
        var n = 2
        while (personas.any { it.name.equals(name, ignoreCase = true) }) name = "anonymous${n++}"
        val p = Persona(name = name, anonymous = true)
        p.level = (personas.filter { !it.anonymous }.maxOfOrNull { it.level } ?: 0) + 10
        personas += p
        fireChanged()
        return true
    }

    /**
     * Group discovered JWTs by role and create/refresh one persona per distinct role (with the most
     * recent non-expired token), assigning distinct default levels. No automatic anon persona is
     * created — add one by hand if you want to test unauthenticated access. Returns personas created.
     */
    fun syncPersonasFromTokens(): Int {
        // Enforce one persona per role name (merge any pre-existing duplicates).
        val seen = HashSet<String>()
        personas.removeAll { !it.anonymous && !seen.add(it.name) }

        // Only valid (parseable + non-expired) tokens are eligible.
        val jwtToks = capturedTokens.filter { it.info != null && !it.info!!.isExpired() }
        val byRole = jwtToks.groupBy { it.info!!.role ?: "sin-rol" }
        var created = 0
        for ((role, toks) in byRole) {
            val best = toks.maxByOrNull { it.info?.exp ?: 0L } ?: continue // freshest active token
            // A JWT can live in a cookie (Spring Session, NextAuth, …). If we captured the same
            // token in a cookie bundle, create the persona as a cookie persona automatically.
            val cookieSession = capturedTokens.firstOrNull {
                it.isCookie && it.cookies.values.contains(best.token)
            }
            val existing = personas.firstOrNull { !it.anonymous && it.name == role }
            if (existing != null) {
                synchronized(existing) {
                    existing.ownerSub = best.info?.sub
                    when {
                        cookieSession != null && existing.location == com.authzmatrix.model.TokenLocation.COOKIE -> {
                            // Same role, possibly rotated JWT-in-cookie: refresh the bundle.
                            existing.cookies.putAll(cookieSession.cookies)
                            existing.token = ""
                        }
                        cookieSession != null &&
                            existing.location == com.authzmatrix.model.TokenLocation.BEARER_HEADER &&
                            existing.cookies.isEmpty() -> {
                            existing.location = com.authzmatrix.model.TokenLocation.COOKIE
                            existing.cookies.putAll(cookieSession.cookies)
                            existing.token = ""
                        }
                        else -> existing.token = best.token
                    }
                }
            } else {
                // Distinct default levels (0,10,20…) so "lower-privilege" works out of the box;
                // adjust them by hand — lower number = more privilege.
                val nextLevel = (personas.count { !it.anonymous }) * 10
                val p = Persona(name = role, token = best.token, ownerSub = best.info?.sub)
                if (cookieSession != null) {
                    p.location = com.authzmatrix.model.TokenLocation.COOKIE
                    p.cookies.putAll(cookieSession.cookies)
                    p.token = ""
                }
                p.level = nextLevel
                personas += p
                created++
            }
        }
        // No automatic personas (not even anon): only roles discovered from history.
        fireChanged()
        return created
    }

    companion object {
        /** Bound the in-memory catalog so long audits don't grow without limit. */
        const val MAX_CAPTURED_TOKENS = 1_000
    }
}
