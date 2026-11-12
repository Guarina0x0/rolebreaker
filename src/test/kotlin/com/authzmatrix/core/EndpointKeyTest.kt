package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointKeyTest {

    @Test fun collapsesNumericAndUuidPathSegments() {
        assertEquals(
            "https://h/users/{id}/orders/{id}",
            EndpointKey.normalize("https://h/users/42/orders/550e8400-e29b-41d4-a716-446655440000"),
        )
    }

    @Test fun collapsesIdValuesButKeepsQueryNames() {
        assertEquals(
            "https://h/orders?user_id={id}&page={id}&sort=desc",
            EndpointKey.normalize("https://h/orders?user_id=7&page=2&sort=desc"),
        )
    }

    @Test fun keepsAuthorityAndPortIntact() {
        assertEquals(
            "https://api.example.com:8443/users/{id}",
            EndpointKey.normalize("https://api.example.com:8443/users/123"),
        )
    }

    @Test fun handlesRelativeUrls() {
        assertEquals("/users/{id}?id={id}", EndpointKey.normalize("/users/99?id=5"))
    }

    @Test fun leavesNonIdSegmentsAlone() {
        assertEquals("https://h/v1/users/me", EndpointKey.normalize("https://h/v1/users/me"))
    }

    @Test fun isIdRecognisesNumericAndUuid() {
        assertTrue(EndpointKey.isId("1"))
        assertTrue(EndpointKey.isId("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(EndpointKey.isId("v1"))
        assertFalse(EndpointKey.isId(""))
    }
}
