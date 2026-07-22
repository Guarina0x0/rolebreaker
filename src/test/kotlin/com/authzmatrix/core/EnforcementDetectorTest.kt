package com.authzmatrix.core

import com.authzmatrix.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EnforcementDetectorTest {

    private val d = EnforcementDetector()

    @Test fun deniedOn401() = assertEquals(Verdict.DENIED, d.classify(200, 100, 401, "x"))

    @Test fun deniedByRegex() = assertEquals(Verdict.DENIED, d.classify(200, 100, 200, "Error: Access Denied"))

    @Test fun allowedWhenContentDiffers() =
        assertEquals(Verdict.ALLOWED, d.classify(200, 1000, 200, "a".repeat(10)))

    @Test fun sameWhenStatusAndSizeMatch() =
        assertEquals(Verdict.SAME_AS_BASELINE, d.classify(200, 1000, 200, "a".repeat(1000)))

    @Test fun smallBaselineUsesAbsoluteFloor() =
        assertEquals(Verdict.SAME_AS_BASELINE, d.classify(200, 12, 200, "a".repeat(13)))

    @Test fun checkOnRedirect() = assertEquals(Verdict.CHECK, d.classify(200, 100, 302, ""))

    @Test fun loginPageIsDeniedEvenOn200() {
        val body = "<html><body><form><input type=\"password\" name=\"pass\"></form></body></html>"
        assertEquals(Verdict.DENIED, d.classify(200, 100, 200, body))
    }

    @Test fun normalizeStripsCsrfSoResponsesMatch() {
        val base = """{"csrf_token":"aaaaaaaa","data":"x"}"""
        val resp = """{"csrf_token":"zzzzzzzzzzzzzzzz","data":"x"}""" // only csrf differs
        val baseNorm = d.normalizedLength(base)
        assertEquals(Verdict.SAME_AS_BASELINE, d.classify(200, baseNorm, 200, resp))
    }

    @Test fun markerAlsoInBaselineIsNotDenied() {
        // e.g. an admin change-password page: password field is normal content here, not a wall.
        val base = "<html>settings <input type=\"password\" name=\"pass\"> AAAA</html>"
        val resp = "<html>settings <input type=\"password\" name=\"pass\"> BBBB</html>"
        val v = d.classify(200, d.normalizedLength(base), 200, resp, base)
        assertNotEquals(Verdict.DENIED, v)
    }

    @Test fun failedBaselineYieldsCheckNotAllowed() {
        // baselineStatus < 0 means the baseline send failed → can't claim the 2xx is "access".
        assertEquals(Verdict.CHECK, d.classify(-1, 0, 200, "some content"))
    }
}
