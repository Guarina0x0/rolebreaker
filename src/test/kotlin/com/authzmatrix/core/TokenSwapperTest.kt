package com.authzmatrix.core

import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import com.authzmatrix.model.Persona
import com.authzmatrix.model.TokenLocation
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises the pure header/cookie swapping logic with a tiny dynamic-proxy HttpRequest, so it
 * runs outside Burp.
 */
class TokenSwapperTest {

    private class H(private val headerName: String, private val headerValue: String) : HttpHeader {
        override fun name() = headerName
        override fun value() = headerValue
        override fun toString() = "$headerName: $headerValue"
    }

    private class FakeRequest(private val headers: List<Pair<String, String>>) {

        fun proxy(): HttpRequest = Proxy.newProxyInstance(
            HttpRequest::class.java.classLoader,
            arrayOf(HttpRequest::class.java),
        ) { _, method, args ->
            when (method.name) {
                "headers" -> headers.map { H(it.first, it.second) }
                "headerValue" -> headers.firstOrNull {
                    it.first.equals(args[0] as String, ignoreCase = true)
                }?.second
                "hasHeader" -> headers.any { it.first.equals(args[0] as String, ignoreCase = true) }
                "withUpdatedHeader" -> FakeRequest(update(args[0] as String, args[1] as String)).proxy()
                "withAddedHeader" -> FakeRequest(headers + (args[0] as String to args[1] as String)).proxy()
                "withRemovedHeader" -> FakeRequest(
                    headers.filterNot { it.first.equals(args[0] as String, ignoreCase = true) },
                ).proxy()
                "toString" -> "FakeRequest($headers)"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args[0]
                else -> default(method.returnType)
            }
        } as HttpRequest

        private fun update(name: String, value: String): List<Pair<String, String>> {
            var replaced = false
            return headers.map {
                if (!replaced && it.first.equals(name, ignoreCase = true)) {
                    replaced = true
                    name to value
                } else {
                    it
                }
            }
        }

        private fun default(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> ' '
            Double::class.javaPrimitiveType -> 0.0
            Float::class.javaPrimitiveType -> 0f
            else -> null
        }
    }

    private fun request(vararg headers: Pair<String, String>): HttpRequest =
        FakeRequest(headers.toList()).proxy()

    private fun cookieMap(req: HttpRequest): Map<String, String> =
        CookieHeader.parse(req.headerValue("Cookie"))

    @Test fun cookiePersonaReplacesAuthAndKeepsNonAuthCookies() {
        val req = request(
            "Cookie" to "session=admin; csrf=old; lang=es",
            "Authorization" to "Bearer admin-jwt",
            "X-Trace" to "1",
        )
        val persona = Persona("user", location = TokenLocation.COOKIE).apply {
            cookies["session"] = "user"
            cookies["csrf"] = "new"
        }

        val out = TokenSwapper.apply(req, persona)
        val cookies = cookieMap(out)
        assertEquals("user", cookies["session"])
        assertEquals("new", cookies["csrf"])
        assertEquals("es", cookies["lang"])
        assertNull(out.headerValue("Authorization"))
        assertEquals("1", out.headerValue("X-Trace"))
    }

    @Test fun bearerPersonaStripsAuthCookiesAndOtherJwtHeaders() {
        val req = request(
            "Cookie" to "session=admin; csrf=old; lang=es",
            "Authorization" to "Bearer old",
            "X-Auth-Token" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhIn0.sig",
        )
        val persona = Persona("user", token = "new-token")

        val out = TokenSwapper.apply(req, persona)
        assertEquals("Bearer new-token", out.headerValue("Authorization"))
        assertEquals(mapOf("lang" to "es"), cookieMap(out))
        assertNull(out.headerValue("X-Auth-Token"))
    }

    @Test fun anonymousStripsAuthMaterial() {
        val req = request(
            "Cookie" to "session=admin; lang=es",
            "Authorization" to "Bearer admin",
        )
        val anon = Persona("anon", anonymous = true)

        val out = TokenSwapper.apply(req, anon)
        assertNull(out.headerValue("Authorization"))
        assertEquals(mapOf("lang" to "es"), cookieMap(out))
    }
}
