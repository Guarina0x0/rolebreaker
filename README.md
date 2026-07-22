# AuthZ Matrix (JWT) — Burp Suite Extension

A Burp Suite extension for **broken access control** and **privilege escalation** testing, driven by JWTs and multiple roles. Think of it as *Authorize* but designed around a per-role **access matrix** (endpoint × persona) with automatic token discovery, JWT security analysis, and a curated Findings view.

> **Feature requests and bug reports are welcome — open a [GitHub Issue](../../issues).**

---

## Features at a glance

| Feature | What it does |
|---------|-------------|
| **JWT auto-discovery** | Scans proxy history + live traffic; no pasting |
| **Persona auto-creation** | One persona per distinct role from captured tokens |
| **⚡ Auto sweep** | One click: scan, rank, test all recent requests with lower roles |
| **Continuous lower-priv mode** | Browse as admin — every new request is background-tested as lower roles |
| **Access matrix** | Endpoint × persona, colour-coded verdict |
| **Findings view** | Ranked, severity-coloured; only the interesting results |
| **JWT attacks** | `alg:none`, signature strip, role escalation — live verdict |
| **HMAC weak-secret cracker** | Offline HS256/384/512 against a built-in list or your wordlist |
| **IDOR / param tampering** | Numeric + UUID ids, cross-persona replay |
| **Token auto-refresh** | Swaps token from traffic (same `sub`) or re-logs in via a saved request |
| **Export** | CSV / HTML matrix with verdict colours |
| **Bilingual UI** | Loads in English by default; switch to Spanish from the toolbar (remembered across restarts) |

---

## How it works

For every request the extension replays it as each configured **persona** (e.g. `admin`, `user`, anonymous) by swapping the JWT, then classifies each response:

| Colour | Verdict | Meaning |
|--------|---------|---------|
| 🟩 green | `DENIED` | 401/403 or deny-marker — enforcement in place |
| 🟥 red | `ALLOWED` | 2xx with content different from the baseline — **got in** |
| 🟧 amber | `SAME_AS_BASELINE` | 2xx, indistinguishable from the reference identity |
| 🟨 yellow | `CHECK` | Ambiguous (3xx/5xx/etc.) — look manually |
| ⬜ grey | `ERROR` | Replay failed |

**Red or amber on a low-privilege or anonymous persona for an admin-only endpoint = likely broken access control.**

---

## Build

Requires JDK 17+. Uses the bundled Gradle wrapper — no Gradle install needed.

```bash
./gradlew shadowJar
```

Output: `build/libs/rolebreaker-0.1.0.jar`  
(fat-jar; bundles Kotlin stdlib + org.json — Montoya API is provided by Burp at runtime)

Run the test suite (55 tests — JWT, verdict classification, IDOR, findings, privilege ranking):

```bash
./gradlew test
```

---

## Load in Burp

1. **Extensions → Add**
2. Extension type: **Java**
3. Select `build/libs/rolebreaker-0.1.0.jar`
4. An **AuthZ Matrix (JWT)** tab appears.

After a rebuild: click **Reload** on the extension row — no need to re-add.

### Language

The UI loads in **English** by default. Use the **Language** selector on the far right of the toolbar to switch to **Español**; the whole tab re-renders instantly and your choice is remembered across Burp restarts.

---

## Typical workflow

### Option A — One-click auto sweep (recommended)

1. Log in as each role through Burp so their JWTs get captured automatically.
2. In the **AuthZ Matrix** tab, click **⚡ Auto sweep**.
3. Enter how many minutes of history to cover (default: 20).
4. The extension:
   - Scans for active JWTs → auto-creates one persona per role
   - Auto-ranks privilege (admin/root/super > manager > user/member > guest/anon)
   - Prompts you to confirm / reorder the hierarchy with a drag-and-drop form
   - Tests every in-scope, non-static request from that window with the lower-privilege roles
5. Check the **⚠ Findings** tab.

### Option B — Manual / targeted

Right-click any request in Proxy history, Repeater, Target…

- **"probar con menor privilegio (auto)"** — tests with roles ranked below the request's own token
- **"probar esta acción como… (elegir roles)"** — pick exactly which personas to test
- **"test (todas las personas)"** — replay against every persona
- **"IDOR / param tampering…"** — pick a numeric/UUID identifier and provide alternative values
- **"JWT attacks…"** — forge `alg:none`, stripped signature, or role-escalated variants
- **"usar como refresh de una persona…"** — save this login/refresh request for auto re-login

### Option C — Continuous lower-priv mode

Enable **"Continuo ↓priv (fondo)"** in the toolbar. As you browse as a high-privilege user, every new in-scope request is automatically replayed in the background as the lower-privilege roles. Results appear live in the matrix and Findings tabs.

---

## JWT discovery & persona management

- **"Escanear HTTP history"** — scans Burp proxy history for JWTs (Authorization headers, cookies, bodies). Prompts for how far back to look.
- **"Crear personas por rol"** — turns the catalog into personas (one per distinct role). Only the freshest valid token per role is kept.
- **"Ordenar privilegios…"** — drag-and-drop form to set the privilege hierarchy (top = most privileged). The extension uses this to determine "lower" roles.
- **"Refrescar caducados"** — force re-login of personas that have an assigned refresh request and an expired token.
- **"Limpiar personas"** — wipe all personas (including any persisted from previous sessions).

Personas and refresh requests persist across Burp restarts (Burp Preferences). Tokens are only used if valid-in-time — expired tokens are skipped and refreshed automatically when possible.

---

## Token catalog ("Identidades descubiertas")

Shows every unique JWT identity found in traffic, deduplicated by `sub` + role, freshest first. Columns: role, owner, issuer, algorithm, expiry, and a **security note** (`alg:none`, symmetric, no expiry, etc.).

- **"Promover selección"** → create a persona from the selected token.
- **"Crackear secreto (HS)"** → offline HMAC brute-force for HS256/384/512 tokens. Tries a built-in list of common secrets, then optionally your own wordlist. A hit means the token is fully forgeable.

---

## Findings view

The **⚠ Findings** tab surfaces only the interesting results, ranked by severity:

| Severity | Finding type |
|----------|-------------|
| CRITICAL | Anonymous access to non-public endpoint |
| CRITICAL | Forged JWT accepted by the server |
| CRITICAL | HMAC secret cracked (forgeable tokens) |
| HIGH | IDOR — lower-privilege persona accessed a different user's resource |
| HIGH | Lower-privilege role accessed an endpoint (continuous mode) |
| MEDIUM | Differential access — roles get different content on same endpoint |
| MEDIUM | Identical response between two non-anonymous roles (possible horizontal BAC) |

Severity is escalated automatically when the endpoint matches high-risk patterns (`/admin`, numeric IDs, UUIDs, `?id=` params).

Select a finding to see its request/response.

---

## JWT attacks

Right-click → **"JWT attacks (none/strip/escala)…"**. The dialog lets you forge:

- **alg:none** — remove the algorithm (server should reject; ALLOWED in the log = broken)
- **Signature stripped** — keeps the header+claims, removes the signature bytes
- **Role escalation** — bumps the role claim to `admin` (or a custom value you type)

Each is sent once; the result lands in the activity log. If the server accepts a forged token the verdict is **ALLOWED** — clear evidence of broken JWT verification.

---

## IDOR / parameter tampering

Right-click → **"IDOR / param tampering…"**. The extension detects numeric and UUID-shaped values in the query string, body, and path, and lets you pick one to vary. Each variant is replayed against every persona, covering both the **vertical** (role) and **horizontal** (resource ownership) axes simultaneously.

---

## Token auto-refresh

Two mechanisms keep tokens valid during long audits:

1. **Traffic-based refresh** — when a newer, non-expired token with the same `sub` appears in traffic, the persona's token is swapped automatically.
2. **Re-login** — right-click the login/token-refresh request → *"usar como refresh de una persona…"*, pick the persona and the JSON field where the token lives. When that persona's token expires, the extension replays the saved request and extracts the fresh token. Persisted across Burp restarts.

---

## Verdict classification notes

- Responses are **normalised** before comparison: volatile fields (`csrf_token`, `nonce`, `timestamp`, `iat`, `X-Request-Id`) are stripped to avoid false positives.
- A deny or login-page marker only counts as **DENIED** if it is **not** present in the baseline too.
- A 3xx redirect to a URL containing `/login`, `/auth`, or `/signin` is classified as **DENIED**.
- If the baseline request itself fails, 2xx responses are classified **CHECK** — not ALLOWED.
- Static assets (`.js`, `.css`, `.png`, `.ico`, …) are skipped in auto-modes. The path is matched **without** the query string so `?file=report.pdf` is never mistakenly skipped.

---

## Project layout

```
com.authzmatrix
├── AuthzMatrixExtension.kt
├── model/Models.kt
├── core/
│   ├── Jwt.kt                    # JWT parse, role extraction (incl. Keycloak realm_access.roles)
│   ├── JwtForge.kt               # alg:none / strip / role-escalate
│   ├── JwtCracker.kt             # offline HMAC brute-force
│   ├── PersonaStore.kt           # personas + token catalog (thread-safe)
│   ├── PersonaPersistence.kt     # Burp Preferences serialization (org.json, v2 key)
│   ├── PrivilegeRanker.kt        # auto-ranks roles by name heuristic
│   ├── TokenCapture.kt           # harvest JWTs from live traffic
│   ├── TokenSwapper.kt           # inject / strip token in a request
│   ├── EnforcementDetector.kt    # response → Verdict (normalise, deny/login regexes)
│   ├── ReplayEngine.kt           # baseline + per-persona replay
│   ├── RefreshEngine.kt          # re-login via saved request
│   ├── HistoryScanner.kt         # scan proxy history for JWTs
│   ├── FindingsAnalyzer.kt       # derive ranked findings from matrix + activity
│   ├── EndpointRisk.kt           # escalate severity for /admin, ids, UUIDs
│   ├── IdorAnalyzer.kt           # detect numeric/UUID params
│   ├── StaticAssets.kt           # filter static extensions
│   └── AppContext.kt             # wiring, executor pool, auto-mode state
├── handler/
│   ├── ProxyHttpHandler.kt       # auto-mode + token capture
│   └── AuthzContextMenu.kt       # right-click menu
└── ui/
    ├── MainTab.kt
    ├── MatrixTableModel.kt
    ├── PersonaTableModel.kt
    ├── TokenCatalogModel.kt
    ├── ActivityTableModel.kt
    ├── FindingsTableModel.kt
    ├── AutoLowerPrivTester.kt
    ├── RoleTestDialog.kt
    ├── PrivilegeOrderDialog.kt
    ├── RefreshAssignDialog.kt
    ├── IdorDialog.kt
    └── JwtAttackDialog.kt
```

---

## Limitations

- JWT signatures are **never validated** — the tool trusts whatever token you configure. The point is to test the *server's* enforcement.
- The privilege auto-ranking heuristic is a starting point; always confirm via **"Ordenar privilegios…"**.
- Auto-mode deduplication is by `method + URL`; parameterised endpoints with many IDs are only tested once per URL.
- Results (matrix, activity, findings) are **in-memory** — reset on Burp restart. Export CSV/HTML to keep a snapshot.

---

## Contributing & feature requests

Issues and pull requests are welcome.

- **Bug?** → open a [Bug report](../../issues/new?template=bug_report.md)
- **Idea?** → open a [Feature request](../../issues/new?template=feature_request.md)

Please describe the testing scenario or workflow you're trying to solve — the more concrete the use case, the easier it is to implement well.
