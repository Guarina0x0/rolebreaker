package com.authzmatrix.ui

import com.authzmatrix.core.I18n
import com.authzmatrix.core.Jwt
import com.authzmatrix.core.PersonaStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenCatalogModelTest {

    private fun jwt(): String {
        val exp = Instant.now().plusSeconds(3_600).epochSecond
        return "${Jwt.encode("""{"alg":"HS256"}""")}." +
            "${Jwt.encode("""{"sub":"a","role":"admin","exp":$exp}""")}.sig"
    }

    @Test fun catalogShowsJwtAndCookieSessions() {
        val store = PersonaStore()
        store.capture(jwt(), "src")
        store.captureCookie(mapOf("session" to "abc", "csrf" to "xyz"), "src")

        val model = TokenCatalogModel(store)
        model.refresh()

        assertEquals(2, model.rowCount)
        assertEquals(I18n.t("token.typeJwt"), model.getValueAt(0, 0))
        assertEquals(I18n.t("token.typeCookie"), model.getValueAt(1, 0))
        assertTrue(model.getValueAt(1, 6).toString().contains("session"))
    }
}
