package com.authzmatrix.ui

import com.authzmatrix.model.Finding
import com.authzmatrix.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindingsTableModelTest {

    private fun finding(severity: Severity, type: String, summary: String) =
        Finding(severity, type, summary, null)

    private fun model() = FindingsTableModel().apply {
        set(
            listOf(
                finding(Severity.CRITICAL, "Anonymous access", "GET /admin is public"),
                finding(Severity.HIGH, "IDOR", "GET /orders/1001 as bob"),
                finding(Severity.MEDIUM, "Differential access", "GET /config roles differ"),
            ),
        )
    }

    @Test fun filtersByFreeText() {
        val m = model()
        m.setFilter("admin", null)
        assertEquals(1, m.rowCount)
        assertEquals("Anonymous access", m.findingAt(0)?.type)
        assertEquals(3, m.all().size, "full list must be kept for the dashboard counters")
    }

    @Test fun filtersBySeverity() {
        val m = model()
        m.setFilter("", Severity.HIGH)
        assertEquals(1, m.rowCount)
        assertEquals("IDOR", m.findingAt(0)?.type)
    }

    @Test fun combinesTextAndSeverity() {
        val m = model()
        m.setFilter("orders", Severity.CRITICAL)
        assertEquals(0, m.rowCount)
        assertNull(m.findingAt(0))
    }

    @Test fun clearsFilter() {
        val m = model()
        m.setFilter("nothing", null)
        assertEquals(0, m.rowCount)
        m.setFilter("", null)
        assertEquals(3, m.rowCount)
        assertTrue(m.visible().isNotEmpty())
    }

    @Test fun searchesEndpointMetadata() {
        val m = model()
        m.setFilter("1001", null)
        assertEquals(1, m.rowCount)
        assertEquals("IDOR", m.findingAt(0)?.type)
    }
}
