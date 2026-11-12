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

    // --- single-shot probes (no baseline), e.g. forged-JWT attacks -----------------

    @Test fun standalone2xxIsAllowed() =
        assertEquals(Verdict.ALLOWED, d.classifyStandalone(200, "sensitive data"))

    @Test fun standalone401And403AreDenied() {
        assertEquals(Verdict.DENIED, d.classifyStandalone(401, "no"))
        assertEquals(Verdict.DENIED, d.classifyStandalone(403, "no"))
    }

    @Test fun standaloneLoginWallIsDeniedEvenOn200() {
        val body = "<html><form><input type=\"password\" name=\"pass\"></form></html>"
        assertEquals(Verdict.DENIED, d.classifyStandalone(200, body))
    }

    @Test fun standaloneDenyBodyIsDenied() =
        assertEquals(Verdict.DENIED, d.classifyStandalone(200, "Error: Access Denied"))

    @Test fun standaloneJwtValidationErrorIsDenied() {
        assertEquals(Verdict.DENIED, d.classifyStandalone(200, """{"error":"invalid token"}"""))
        assertEquals(Verdict.DENIED, d.classifyStandalone(200, "Signature verification failed"))
    }

    @Test fun ordinaryTokenContentIsNotADenial() {
        assertEquals(Verdict.ALLOWED, d.classifyStandalone(200, """{"token":"abc","balance":10}"""))
    }

    @Test fun standaloneRedirectToLoginIsDenied() =
        assertEquals(Verdict.DENIED, d.classifyStandalone(302, "", "https://h/login?next=/x"))

    @Test fun standaloneServerErrorNeedsReview() =
        assertEquals(Verdict.CHECK, d.classifyStandalone(500, "boom"))
}
