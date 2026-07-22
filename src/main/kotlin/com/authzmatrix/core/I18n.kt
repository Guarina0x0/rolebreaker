package com.authzmatrix.core

/**
 * Tiny in-memory i18n. Default language is English; the user can switch to Spanish from the
 * toolbar. UI components register [onChange] listeners to rebuild themselves when the language
 * changes. Missing keys fall back to English, then to the raw key (so gaps are visible).
 */
object I18n {

    enum class Lang { EN, ES }

    @Volatile
    var lang: Lang = Lang.EN
        private set

    private val listeners = ArrayList<() -> Unit>()

    fun onChange(l: () -> Unit) { listeners += l }

    /** Set without notifying (used at startup, before the UI exists). */
    fun initLang(l: Lang) { lang = l }

    fun setLang(l: Lang) {
        if (l == lang) return
        lang = l
        listeners.toList().forEach { it() }
    }

    fun t(key: String, vararg args: Any?): String {
        val table = if (lang == Lang.ES) ES else EN
        val raw = table[key] ?: EN[key] ?: key
        return if (args.isEmpty()) raw else raw.format(*args)
    }

    // ---- English (default) ----------------------------------------------------

    private val EN: Map<String, String> = mapOf(
        "ext.name" to "RoleBreaker",
        "ext.load" to "RoleBreaker loaded. Configure personas, then right-click requests or enable Auto mode to build the access matrix.",

        // toolbar
        "tb.auto" to " Auto: ",
        "tb.sweep" to "⚡ Auto sweep",
        "tb.sweep.tip" to "One click: scans recent history, creates and ranks roles, and tests everything with the lower ones",
        "tb.mode" to " Mode: ",
        "tb.autoAll" to "Auto (all personas)",
        "tb.autoAll.tip" to "Replay every in-scope request against all personas",
        "tb.lowerCont" to "Continuous ↓priv (background)",
        "tb.lowerCont.tip" to "Browse as admin; every new request is replayed in the background as the lower-privilege roles",
        "tb.capture" to "Capture JWTs from traffic",
        "tb.view" to " View: ",
        "tb.clearMatrix" to "Clear matrix",
        "tb.clearActivity" to "Clear activity",
        "tb.export" to " Export: ",
        "tb.exportCsv" to "Export CSV",
        "tb.exportHtml" to "Export HTML",
        "tb.lang" to " Language: ",

        // center
        "center.matrix" to "Access matrix (request × persona)",
        "center.activity" to "Activity (live)",
        "center.findings" to "⚠ Findings",
        "center.request" to "Request",
        "center.response" to "Response",

        // config panel
        "cfg.personas" to "Personas (roles)",
        "cfg.catalog" to "Discovered identities (JWT, deduplicated)",
        "btn.add" to "Add",
        "btn.remove" to "Remove",
        "btn.refreshExpired" to "Refresh expired",
        "btn.refreshExpired.tip" to "Re-login personas with an expired token that have a refresh request configured",
        "btn.clearPersonas" to "Clear personas",
        "btn.clearPersonas.tip" to "Delete ALL personas (including those saved from previous sessions)",
        "btn.order" to "Order privileges…",
        "btn.order.tip" to "Mark which role has the most and which the least privilege (for the automatic test)",
        "btn.scan" to "Scan HTTP history",
        "btn.sync" to "Create personas by role",
        "btn.promote" to "Promote selection",
        "btn.crack" to "Crack secret (HS)",
        "btn.crack.tip" to "Try common secrets against an HS256/384/512 token (offline)",
        "btn.clear" to "Clear",

        // menus (matrix)
        "menu.toRepeater" to "Send original to Repeater",

        // dialogs / messages
        "dlg.title" to "RoleBreaker",
        "msg.confirmClearPersonas" to "Delete all personas?",

        "sweep.prompt" to "Auto sweep — use the last N minutes of history:\nscans active JWTs, creates roles, ranks privileges automatically and tests\neach request with the LOWEST-privilege roles.",
        "sweep.noRoles" to "No roles with an active token were discovered in the last %d min.",
        "sweep.noReqs" to "No in-scope requests in the last %d min to test.",
        "sweep.launched" to "Auto sweep launched over %d request(s) from the last %d min.\nDetected hierarchy (most → least priv): %s\nCheck the ⚠ Findings tab.",
        "sweep.title" to "Auto sweep",

        "scan.prompt" to "Scan JWTs from the last N minutes of history (active tokens only):",
        "scan.result" to "%d new active token(s) (last %d min).\n%d persona(s) created by role.",

        "sync.result" to "%d persona(s) created. Total: %d.",
        "order.offer" to "Order the roles by privilege now (most → least)?",

        "crack.selectToken" to "Select a token from the catalog.",
        "crack.title" to "Crack",
        "crack.onlyHmac" to "Secret cracking only applies to HS256/384/512 tokens (this one is alg=%s).",
        "crack.commonList" to "common list",
        "crack.wordlist" to "wordlist (%d words)",
        "crack.weakLog" to "RoleBreaker: WEAK HMAC SECRET found (%s): '%s'",
        "crack.weakDialog" to "⚠ WEAK SECRET: «%s»\nThe token is forgeable: you can sign any claim with that secret.",
        "crack.weakTitle" to "Crack — vulnerable",
        "crack.notCracked" to "Not cracked with the %s.",
        "crack.tryWordlist" to "Not cracked with the %s. Try your own wordlist?",
        "crack.wordlistErr" to "Could not read the wordlist: %s",

        "finding.weakSecret.type" to "Weak HMAC secret",
        "finding.weakSecret.summary" to "Token secret cracked (%s): «%s» → the JWT is forgeable.",

        "export.error" to "Export error: %s",
        "pwndoc.notLinked" to "This finding is not linked to a matrix row (send it from the matrix or the log).",

        // table headers — matrix
        "col.request" to "Request",
        "col.base" to "Base",
        // table headers — personas
        "col.on" to "On",
        "col.name" to "Name",
        "col.anon" to "Anon",
        "col.location" to "Location",
        "col.headerCookie" to "Header/Cookie",
        "col.token" to "Token",
        "col.level" to "Level",
        // table headers — activity
        "col.time" to "Time",
        "col.what" to "What",
        "col.method" to "Method",
        "col.url" to "URL",
        "col.status" to "Status",
        "col.bytes" to "Bytes",
        "col.verdict" to "Verdict",
        // table headers — findings
        "col.severity" to "Severity",
        "col.type" to "Type",
        "col.detail" to "Detail",
        // table headers — catalog
        "col.role" to "Role",
        "col.owner" to "Owner",
        "col.issuer" to "Issuer",
        "col.alg" to "Alg",
        "col.exp" to "Exp",
        "col.security" to "Security",
        "col.source" to "Source",

        // persona token summary
        "persona.anon" to "(anonymous)",
        "persona.noToken" to "(no token)",
        "persona.opaque" to "opaque token",
        "persona.expired" to "⚠ EXPIRED",
        "persona.refresh" to "⟳refresh",
        "token.expired" to "EXPIRED",
        "token.noJwt" to "no-JWT",

        // JWT security note
        "jwt.noAlg" to "no alg",
        "jwt.algNone" to "⚠ alg:none (unsigned)",
        "jwt.hsSymmetric" to "HS symmetric (forgeable if weak secret)",
        "jwt.noSig" to "⚠ no signature",
        "jwt.noExp" to "no exp",
        "jwt.expired" to "⚠ EXPIRED",

        // findings
        "find.anonAccess" to "Anonymous access",
        "find.anonAccess.sum" to "Without a token, %s is accessible (HTTP %d)",
        "find.idor" to "IDOR / horizontal access",
        "find.idor.sum" to "«%s» reaches another user's resource: %s (HTTP %d)",
        "find.lowerPriv" to "Lower-privilege role with access",
        "find.lowerPriv.sum" to "«%s» (lower privilege) accesses %s %s (HTTP %d)",
        "find.differential" to "Differential access",
        "find.differential.sum" to "%s %s: allowed [%s] · denied [%s]",
        "find.identical" to "Identical response between roles",
        "find.identical.sum" to "%s %s: [%s] get the SAME 2xx response — possible horizontal access / un-segregated resource",
        "find.forged" to "Forged JWT accepted",
        "find.forged.sum" to "%s accepted on %s %s (HTTP %d)",
        "role.fallback" to "role",

        // context menu
        "ctx.lowerPriv" to "RoleBreaker: test with lower privilege (auto)",
        "ctx.asRole" to "RoleBreaker: test this action as… (choose roles)",
        "ctx.testAllN" to "RoleBreaker: test %d requests (all personas)",
        "ctx.testAll" to "RoleBreaker: test (all personas)",
        "ctx.idor" to "RoleBreaker: IDOR / param tampering…",
        "ctx.jwtAttacks" to "RoleBreaker: JWT attacks (none/strip/escalate)…",
        "ctx.asRefresh" to "RoleBreaker: use as a persona's refresh…",

        // AutoLowerPrivTester
        "lower.noPersonas" to "No personas. Scan the HTTP history and create personas by role first.",
        "lower.log" to "RoleBreaker (lower-priv): %d request(s) replayed as lower-level roles.",
        "lower.none" to "No request had lower-privilege roles to test.\nCheck the persona levels ('Order privileges…').",

        // RoleTestDialog
        "role.noPersonas" to "No personas defined. Scan the HTTP history first.",
        "role.toggleAll" to "Select / deselect all",
        "role.nRequests" to "%d requests",
        "role.header" to "<html>Replay <b>%s</b> with each checked role's token.<br>The original response (baseline) is the reference.</html>",
        "role.rolesToTest" to "Roles to test",
        "role.dialogTitle" to "Test action as…",
        "role.pickOne" to "Check at least one role.",
        "role.log" to "RoleBreaker: testing %d request(s) as %d role(s).",
        "role.st.anon" to "anonymous",
        "role.st.noToken" to "no token",
        "role.st.opaque" to "opaque token",
        "role.st.expired" to "⚠ EXPIRED",
        "role.st.ok" to "ok",

        // PrivilegeOrderDialog
        "order.need2" to "You need at least 2 personas to order privileges.",
        "order.up" to "▲ Up (more privilege)",
        "order.down" to "▼ Down (less privilege)",
        "order.header" to "<html><b>Order the roles by privilege.</b><br>Top = MOST privilege · Bottom = LEAST.<br>«test with lower privilege» will replay each action to those <i>below</i>.</html>",
        "order.dialogTitle" to "Order privileges",
        "order.log" to "RoleBreaker: privileges reordered (%d roles).",
        "order.anon" to " (anonymous)",

        // IdorDialog
        "idor.none" to "No identifiers (numeric/UUID) detected in the query, body or path of this request.",
        "idor.title" to "IDOR",
        "idor.includeOriginal" to "Include the original value as a reference row",
        "idor.identifier" to "Identifier:",
        "idor.altValues" to "Alternative values (comma/space):",
        "idor.hint" to "<html><i>Each value is tested with every active persona.</i></html>",
        "idor.dialogTitle" to "IDOR / parameter tampering",
        "idor.pickValue" to "Enter at least one alternative value.",
        "idor.log" to "RoleBreaker: IDOR — %d variant(s) queued over %s",

        // JwtAttackDialog
        "jwtatk.noJwt" to "No JWT found in this request.",
        "jwtatk.title" to "JWT attacks",
        "jwtatk.notParseable" to "The token found is not a parseable JWT.",
        "jwtatk.none" to "alg:none (unsigned)",
        "jwtatk.strip" to "Strip signature (original header+payload)",
        "jwtatk.escalate" to "Escalate role",
        "jwtatk.detected" to "Detected token:",
        "jwtatk.roleClaim" to "  role claim:",
        "jwtatk.roleValue" to "  value to inject:",
        "jwtatk.log" to "RoleBreaker: %d forged JWT variant(s) sent — see the activity log.",

        // RefreshAssignDialog
        "refresh.noPersonas" to "No personas. Scan the HTTP history first.",
        "refresh.testNow" to "Test now (immediate re-login)",
        "refresh.request" to "Request:",
        "refresh.assignTo" to "Assign to persona:",
        "refresh.jsonField" to "Token JSON field:",
        "refresh.hint" to "<html><i>Empty = take the first JWT in the response. E.g.: <code>data.access_token</code></i></html>",
        "refresh.dialogTitle" to "Persona refresh / re-login",
        "refresh.assigned" to "RoleBreaker: refresh assigned to '%s'.",
        "refresh.noToken" to "No token obtained from the response.\nCheck the JSON field or that the response contains a JWT.",
        "refresh.ok" to "Re-login OK.\n%s",
        "refresh.opaque" to "opaque token",
        "refresh.reloginSrc" to "re-login of %s",
    )

    // ---- Spanish --------------------------------------------------------------

    private val ES: Map<String, String> = mapOf(
        "ext.name" to "RoleBreaker",
        "ext.load" to "RoleBreaker cargado. Configura personas y luego haz clic derecho en las peticiones o activa el modo Auto para construir la matriz de acceso.",

        // toolbar
        "tb.auto" to " Auto: ",
        "tb.sweep" to "⚡ Auto sweep",
        "tb.sweep.tip" to "Un clic: escanea el historial reciente, crea y ordena roles, y prueba todo con los inferiores",
        "tb.mode" to " Modo: ",
        "tb.autoAll" to "Auto (todas las personas)",
        "tb.autoAll.tip" to "Replay de cada request in-scope contra todas las personas",
        "tb.lowerCont" to "Continuo ↓priv (fondo)",
        "tb.lowerCont.tip" to "Navega como admin; cada request nueva se relanza en background con los roles de menor privilegio",
        "tb.capture" to "Capturar JWTs del tráfico",
        "tb.view" to " Vista: ",
        "tb.clearMatrix" to "Limpiar matriz",
        "tb.clearActivity" to "Limpiar actividad",
        "tb.export" to " Export: ",
        "tb.exportCsv" to "Export CSV",
        "tb.exportHtml" to "Export HTML",
        "tb.lang" to " Idioma: ",

        // center
        "center.matrix" to "Matriz de acceso (petición × persona)",
        "center.activity" to "Actividad (en vivo)",
        "center.findings" to "⚠ Findings",
        "center.request" to "Request",
        "center.response" to "Response",

        // config panel
        "cfg.personas" to "Personas (roles)",
        "cfg.catalog" to "Identidades descubiertas (JWT, sin duplicados)",
        "btn.add" to "Add",
        "btn.remove" to "Remove",
        "btn.refreshExpired" to "Refrescar caducados",
        "btn.refreshExpired.tip" to "Re-login de las personas con token caducado que tengan refresh configurado",
        "btn.clearPersonas" to "Limpiar personas",
        "btn.clearPersonas.tip" to "Borra TODAS las personas (incl. las guardadas de sesiones anteriores)",
        "btn.order" to "Ordenar privilegios…",
        "btn.order.tip" to "Marca qué rol tiene más y cuál menos privilegio (para el test automático)",
        "btn.scan" to "Escanear HTTP history",
        "btn.sync" to "Crear personas por rol",
        "btn.promote" to "Promover selección",
        "btn.crack" to "Crackear secreto (HS)",
        "btn.crack.tip" to "Prueba secretos comunes contra un token HS256/384/512 (offline)",
        "btn.clear" to "Limpiar",

        // menus (matrix)
        "menu.toRepeater" to "Enviar original a Repeater",

        // dialogs / messages
        "dlg.title" to "RoleBreaker",
        "msg.confirmClearPersonas" to "¿Borrar todas las personas?",

        "sweep.prompt" to "Auto sweep — usa el historial de los últimos N minutos:\nescanea JWT activos, crea roles, ordena privilegios solo y prueba\ncada petición con los roles de MENOR privilegio.",
        "sweep.noRoles" to "No se descubrieron roles con token activo en los últimos %d min.",
        "sweep.noReqs" to "No hay peticiones in-scope en los últimos %d min para probar.",
        "sweep.launched" to "Auto sweep lanzado sobre %d petición(es) de los últimos %d min.\nJerarquía detectada (más → menos priv): %s\nRevisa la pestaña ⚠ Findings.",
        "sweep.title" to "Auto sweep",

        "scan.prompt" to "Escanear JWT del historial de los últimos N minutos (solo tokens activos):",
        "scan.result" to "%d token(s) activo(s) nuevos (últimos %d min).\n%d persona(s) creada(s) por rol.",

        "sync.result" to "%d persona(s) creada(s). Total: %d.",
        "order.offer" to "¿Ordenar ahora los roles por privilegio (más → menos)?",

        "crack.selectToken" to "Selecciona un token del catálogo.",
        "crack.title" to "Crackear",
        "crack.onlyHmac" to "El crackeo de secreto solo aplica a tokens HS256/384/512 (este es alg=%s).",
        "crack.commonList" to "lista común",
        "crack.wordlist" to "wordlist (%d palabras)",
        "crack.weakLog" to "RoleBreaker: SECRETO HMAC DÉBIL encontrado (%s): '%s'",
        "crack.weakDialog" to "⚠ SECRETO DÉBIL: «%s»\nEl token es forjable: puedes firmar cualquier claim con ese secreto.",
        "crack.weakTitle" to "Crackear — vulnerable",
        "crack.notCracked" to "No se crackeó con la %s.",
        "crack.tryWordlist" to "No se crackeó con la %s. ¿Probar con una wordlist propia?",
        "crack.wordlistErr" to "No se pudo leer la wordlist: %s",

        "finding.weakSecret.type" to "Secreto HMAC débil",
        "finding.weakSecret.summary" to "Secreto del token crackeado (%s): «%s» → el JWT es forjable.",

        "export.error" to "Error exportando: %s",
        "pwndoc.notLinked" to "Este finding no está ligado a una fila de la matriz (envíalo desde la matriz o el log).",

        // table headers — matrix
        "col.request" to "Request",
        "col.base" to "Base",
        // personas
        "col.on" to "On",
        "col.name" to "Nombre",
        "col.anon" to "Anón",
        "col.location" to "Ubicación",
        "col.headerCookie" to "Header/Cookie",
        "col.token" to "Token",
        "col.level" to "Nivel",
        // activity
        "col.time" to "Hora",
        "col.what" to "Qué",
        "col.method" to "Método",
        "col.url" to "URL",
        "col.status" to "Status",
        "col.bytes" to "Bytes",
        "col.verdict" to "Veredicto",
        // findings
        "col.severity" to "Severidad",
        "col.type" to "Tipo",
        "col.detail" to "Detalle",
        // catalog
        "col.role" to "Rol",
        "col.owner" to "Propietario",
        "col.issuer" to "Emisor",
        "col.alg" to "Alg",
        "col.exp" to "Exp",
        "col.security" to "Seguridad",
        "col.source" to "Origen",

        // persona token summary
        "persona.anon" to "(anónimo)",
        "persona.noToken" to "(sin token)",
        "persona.opaque" to "token opaco",
        "persona.expired" to "⚠ EXPIRADO",
        "persona.refresh" to "⟳refresh",
        "token.expired" to "EXPIRADO",
        "token.noJwt" to "no-JWT",

        // JWT security note
        "jwt.noAlg" to "sin alg",
        "jwt.algNone" to "⚠ alg:none (sin firma)",
        "jwt.hsSymmetric" to "HS simétrico (forjable si secreto débil)",
        "jwt.noSig" to "⚠ sin firma",
        "jwt.noExp" to "sin exp",
        "jwt.expired" to "⚠ EXPIRADO",

        // findings
        "find.anonAccess" to "Acceso anónimo",
        "find.anonAccess.sum" to "Sin token se accede a %s (HTTP %d)",
        "find.idor" to "IDOR / acceso horizontal",
        "find.idor.sum" to "«%s» accede a recurso ajeno: %s (HTTP %d)",
        "find.lowerPriv" to "Rol de menor privilegio con acceso",
        "find.lowerPriv.sum" to "«%s» (menor privilegio) accede a %s %s (HTTP %d)",
        "find.differential" to "Acceso diferencial",
        "find.differential.sum" to "%s %s: acceden [%s] · deniegan [%s]",
        "find.identical" to "Respuesta idéntica entre roles",
        "find.identical.sum" to "%s %s: [%s] reciben la MISMA respuesta 2xx — posible acceso horizontal / recurso no segregado",
        "find.forged" to "JWT forjado aceptado",
        "find.forged.sum" to "%s aceptado en %s %s (HTTP %d)",
        "role.fallback" to "rol",

        // context menu
        "ctx.lowerPriv" to "RoleBreaker: probar con menor privilegio (auto)",
        "ctx.asRole" to "RoleBreaker: probar esta acción como… (elegir roles)",
        "ctx.testAllN" to "RoleBreaker: test %d peticiones (todas las personas)",
        "ctx.testAll" to "RoleBreaker: test (todas las personas)",
        "ctx.idor" to "RoleBreaker: IDOR / param tampering…",
        "ctx.jwtAttacks" to "RoleBreaker: JWT attacks (none/strip/escala)…",
        "ctx.asRefresh" to "RoleBreaker: usar como refresh de una persona…",

        // AutoLowerPrivTester
        "lower.noPersonas" to "No hay personas. Escanea el HTTP history y crea personas por rol primero.",
        "lower.log" to "RoleBreaker (menor-priv): %d petición(es) reenviadas a roles de menor nivel.",
        "lower.none" to "Ninguna petición tenía roles de menor privilegio que probar.\nRevisa los niveles de las personas ('Ordenar privilegios…').",

        // RoleTestDialog
        "role.noPersonas" to "No hay personas definidas. Escanea el HTTP history primero.",
        "role.toggleAll" to "Marcar / desmarcar todo",
        "role.nRequests" to "%d peticiones",
        "role.header" to "<html>Reenvía <b>%s</b> con el token de cada rol marcado.<br>La respuesta original (baseline) es la referencia.</html>",
        "role.rolesToTest" to "Roles a probar",
        "role.dialogTitle" to "Probar acción como…",
        "role.pickOne" to "Marca al menos un rol.",
        "role.log" to "RoleBreaker: probando %d petición(es) como %d rol(es).",
        "role.st.anon" to "anónimo",
        "role.st.noToken" to "sin token",
        "role.st.opaque" to "token opaco",
        "role.st.expired" to "⚠ EXPIRADO",
        "role.st.ok" to "ok",

        // PrivilegeOrderDialog
        "order.need2" to "Necesitas al menos 2 personas para ordenar privilegios.",
        "order.up" to "▲ Subir (más privilegio)",
        "order.down" to "▼ Bajar (menos privilegio)",
        "order.header" to "<html><b>Ordena los roles por privilegio.</b><br>Arriba = MÁS privilegio · Abajo = MENOS.<br>«probar con menor privilegio» reenviará cada acción a los que estén por <i>debajo</i>.</html>",
        "order.dialogTitle" to "Ordenar privilegios",
        "order.log" to "RoleBreaker: privilegios reordenados (%d roles).",
        "order.anon" to " (anónimo)",

        // IdorDialog
        "idor.none" to "No se detectaron identificadores (numéricos/UUID) en query, body o path de esta petición.",
        "idor.title" to "IDOR",
        "idor.includeOriginal" to "Incluir el valor original como fila de referencia",
        "idor.identifier" to "Identificador:",
        "idor.altValues" to "Valores alternativos (coma/espacio):",
        "idor.hint" to "<html><i>Cada valor se prueba con todas las personas activas.</i></html>",
        "idor.dialogTitle" to "IDOR / parameter tampering",
        "idor.pickValue" to "Introduce al menos un valor alternativo.",
        "idor.log" to "RoleBreaker: IDOR — %d variante(s) encoladas sobre %s",

        // JwtAttackDialog
        "jwtatk.noJwt" to "No se encontró ningún JWT en esta petición.",
        "jwtatk.title" to "JWT attacks",
        "jwtatk.notParseable" to "El token encontrado no es un JWT parseable.",
        "jwtatk.none" to "alg:none (sin firma)",
        "jwtatk.strip" to "Eliminar firma (header+payload originales)",
        "jwtatk.escalate" to "Escalar rol",
        "jwtatk.detected" to "Token detectado:",
        "jwtatk.roleClaim" to "  claim de rol:",
        "jwtatk.roleValue" to "  valor a inyectar:",
        "jwtatk.log" to "RoleBreaker: %d variante(s) JWT forjada(s) enviadas — mira el log de actividad.",

        // RefreshAssignDialog
        "refresh.noPersonas" to "No hay personas. Escanea el HTTP history primero.",
        "refresh.testNow" to "Probar ahora (re-login inmediato)",
        "refresh.request" to "Petición:",
        "refresh.assignTo" to "Asignar a persona:",
        "refresh.jsonField" to "Campo JSON del token:",
        "refresh.hint" to "<html><i>Vacío = coge el primer JWT de la respuesta. Ej: <code>data.access_token</code></i></html>",
        "refresh.dialogTitle" to "Refresh / re-login de persona",
        "refresh.assigned" to "RoleBreaker: refresh asignado a '%s'.",
        "refresh.noToken" to "No se obtuvo token de la respuesta.\nRevisa el campo JSON o que la respuesta contenga un JWT.",
        "refresh.ok" to "Re-login OK.\n%s",
        "refresh.opaque" to "token opaco",
        "refresh.reloginSrc" to "re-login de %s",
    )
}
