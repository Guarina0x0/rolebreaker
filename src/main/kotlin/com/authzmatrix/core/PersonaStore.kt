package com.authzmatrix.core

import com.authzmatrix.model.Persona
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A token seen on the wire, offered to the user so they can bind it to a persona
 * instead of pasting it by hand (the "auto-capture" flow).
 */
class CapturedToken(
    val token: String,
    val source: String,
    val info: JwtInfo?,
) {
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

        // Auto-refresh: a fresher, non-expired token of the SAME identity (sub) replaces a
        // persona's stale one, so long audits don't drift into false 401s.
        if (info?.sub != null && !info.isExpired()) {
            for (p in personas) {
                if (p.anonymous || p.token == token) continue
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
            val existing = personas.firstOrNull { !it.anonymous && it.name == role }
            if (existing != null) {
                existing.token = best.token
                existing.ownerSub = best.info?.sub
            } else {
                // Distinct default levels (0,10,20…) so "lower-privilege" works out of the box;
                // adjust them by hand — lower number = more privilege.
                val nextLevel = (personas.count { !it.anonymous }) * 10
                personas += Persona(name = role, token = best.token, ownerSub = best.info?.sub)
                    .apply { level = nextLevel }
                created++
            }
        }
        // No automatic personas (not even anon): only roles discovered from history.
        fireChanged()
        return created
    }
}
