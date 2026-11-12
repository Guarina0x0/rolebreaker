package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SweepPolicyTest {

    @Test fun onlyIdempotentMethodsAreSafeByDefault() {
        listOf("GET", "HEAD", "OPTIONS", "get").forEach { assertTrue(SweepPolicy.isSafeMethod(it), it) }
        listOf("POST", "PUT", "PATCH", "DELETE", "TRACE").forEach {
            assertFalse(SweepPolicy.isSafeMethod(it), it)
        }
    }

    @Test fun destructivePathsAreDetected() {
        listOf(
            "https://h/logout",
            "https://h/api/sign-out?next=/",
            "https://h/users/delete",
            "https://h/api/delete-user",
            "https://h/admin/remove/42",
            "https://h/api/reset-password",
        ).forEach { assertTrue(SweepPolicy.isDangerousUrl(it), it) }
    }

    @Test fun queryContentDoesNotMakeAPathDangerous() {
        assertFalse(SweepPolicy.isDangerousUrl("https://h/search?q=delete"))
        assertFalse(SweepPolicy.isDangerousUrl("https://h/users/42"))
    }

    @Test fun safeCombinesMethodAndUrl() {
        assertTrue(SweepPolicy.isSafe("GET", "https://h/users/42"))
        assertFalse(SweepPolicy.isSafe("POST", "https://h/users/42"))
        assertFalse(SweepPolicy.isSafe("GET", "https://h/logout"))
    }
}
