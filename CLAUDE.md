# CLAUDE.md

Project instructions for AI coding agents working on **RoleBreaker** (repo directory:
`Authorize`). Read the README first for the user-facing feature set; this file covers the
architecture, commands and conventions an agent needs.

## What this is

A Burp Suite extension (Kotlin/JVM 17, Montoya API) for **broken access control / privilege
escalation** testing. It discovers JWTs in proxy traffic, builds one persona per role, replays
requests as lower-privilege roles (and anonymously), and shows an endpoint × persona access
matrix plus ranked findings.

## Commands

```bash
./gradlew test                 # pure-logic unit tests (no Burp needed)
./gradlew shadowJar            # fat-jar → build/libs/rolebreaker-0.1.0.jar
./gradlew clean test shadowJar # full verification
```

The jar is loaded in Burp via **Extensions → Add → Java**. Use **Reload** after rebuilding.
The demo lab is local-only and git-ignored: `cd demo-lab && node server.js` (port 8888).

## Architecture map

- `AuthzMatrixExtension.kt` — entry point. Creates `AppContext`, registers the suite tab,
  HTTP handler and context menu.
- `core/AppContext.kt` — wiring + 4-thread executor + auto-mode flags/dedup + persona refresh.
- `core/PersonaStore.kt` — thread-safe personas + captured-token catalog + persona sync.
- `core/PersonaPersistence.kt` — persistence in Burp Preferences (`authz.personas.v2`).
- `core/TokenCapture.kt` / `HistoryScanner.kt` — JWT discovery from live traffic / proxy history.
- `core/TokenSwapper.kt` — inject or strip a persona's token (Bearer header, custom header, cookie).
- `core/ReplayEngine.kt` — baseline + per-persona replay; emits `ActivityEntry` lines.
- `core/EnforcementDetector.kt` — response → `Verdict` (normalisation, deny/login walls,
  baseline comparison, and `classifyStandalone` for forged-token probes).
- `core/FindingsAnalyzer.kt` — derives ranked `Finding`s from matrix rows + activity entries.
- `core/FindingsExport.kt` — serialises findings to CSV/JSON (used by the Findings tab).
- `core/CurlBuilder.kt` — turns a request into a shell-ready `curl` command.
- `core/AppContext.lowerPrivTargets` — decides which personas are "lower" than a request's role.
- `ui/DashboardTab.kt` — sidebar shell. Modules implement `core/DashboardModule`.
- `ui/MainTab.kt` — the AuthZ Matrix module: personas, catalog, matrix, activity, findings
  (search/severity filter, export, cURL/Repeater actions).
- `ui/dashboard/*Module.kt` — Home, Matrix, Payloads, and (private build) PwnDoc modules.
- `handler/ProxyHttpHandler.kt` — captures traffic and drives auto modes.
- `handler/AuthzContextMenu.kt` — right-click actions.

### Branch model

`main` is the private build and includes `pwnreport/` + the PwnDoc module. The `public` branch
omits PwnDoc. **The only wiring difference is `core/ModuleRegistry.kt`** — keep it tiny and
branch-specific. Do not add private PwnDoc references to shared files.

## Conventions and gotchas

- **Montoya API is `compileOnly`**: never bundle it into the fat-jar; Burp provides it at runtime.
- **i18n**: every user-visible string goes through `core/I18n.kt` and must be added to **both**
  the `EN` and `ES` maps. There is no compile-time check for missing keys.
- **Findings contracts are string-based** (fragile, keep in sync):
  - lower-privilege rows carry `FindingsAnalyzer.LOWER_PRIV_MARKER`;
  - IDOR rows are detected by `→` in the row label;
  - forged-JWT activity entries start with `"🔴 JWT"`.
  If you refactor labels, update `FindingsAnalyzer` and its tests together.
- **Forged probes have no baseline**: use `EnforcementDetector.classifyStandalone` for them.
  Using `classify(-1, 0, …)` would classify every 2xx as `CHECK`, and the CRITICAL forged-JWT
  finding would never be produced.
- **"Lower privilege" needs ordered levels**: `PrivilegeRanker.assignLevels` assigns 0, 10, 20…
  If several non-anonymous personas share a level, `lowerPrivTargets` refuses to guess (returns
  empty) rather than mislabel a more privileged role as a finding.
- **Anonymous coverage**: auto sweep calls `PersonaStore.ensureAnonymousPersona()`; roles
  discovered from JWTs can never produce the CRITICAL anonymous-access finding on their own.
  `TokenSwapper` strips `Authorization`, the configured auth header/cookie, and any other
  header/cookie carrying a JWT, so cookie-based sessions don't leak into the anon replay.
- **Expired tokens are never sent** for a non-anonymous persona; the replay engine skips them
  (and tries a configured refresh first).
- **All UI updates from background threads must go through `SwingUtilities.invokeLater`.**
- **Findings recomputation is debounced** (250 ms Swing Timer) because a sweep emits many rows
  and activity entries; `recomputeFindingsNow()` is for explicit user actions (clear, crack).
- **Persona token column is display-only**: it renders a summary, so editing it would overwrite
  the real token. Use the **Set token…** button (`MainTab.setPersonaToken`).
- **In-memory logs are bounded**: activity 5,000 entries, token catalog 1,000 tokens, and each
  auto-mode dedupe set 10,000 URLs. Keep these caps in mind if you add data to those models.
- **Tests are pure logic** (no Montoya implementation). Don't instantiate `HttpRequest`/`HttpResponse`
  in tests if you can keep the logic in a pure overload instead.
- Results (matrix/activity/findings) are in-memory; personas and payloads persist in Burp
  Preferences. JWTs are stored in plain text there — acceptable for this tool, but don't log
  full tokens unnecessarily.
- The UI is Swing; keep `GridBagLayout`/`BorderLayout` patterns and `DashTheme` colours consistent
  with the existing modules.
