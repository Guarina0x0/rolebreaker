package com.authzmatrix.core

import com.authzmatrix.model.Finding
import com.authzmatrix.model.MatrixRow
import com.authzmatrix.model.Severity
import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingsExportTest {

    private fun finding(
        severity: Severity = Severity.CRITICAL,
        type: String = "Anonymous access",
        summary: String = "A, \"quoted\" summary",
        url: String = "http://h/admin?user_id=1",
        status: Int = 200,
    ): Finding {
        val row = MatrixRow("GET /admin", "GET", url, null, status, 100)
        return Finding(severity, type, summary, null, row)
    }

    @Test fun csvEscapesCommasQuotesAndNewlines() {
        val csv = FindingsExport.csv(listOf(finding(summary = "a,b \"c\"\nd")))
        assertTrue(csv.startsWith("severity,type,detail,method,url,status\n"))
        assertTrue(csv.contains("\"a,b \"\"c\"\"\nd\""))
    }

    @Test fun csvFallsBackToMatrixRowMetadata() {
        val csv = FindingsExport.csv(listOf(finding()))
        assertTrue(csv.contains("CRITICAL,Anonymous access,"))
        assertTrue(csv.contains(",GET,http://h/admin?user_id=1,200"))
    }

    @Test fun jsonContainsStructuredFinding() {
        val arr = JSONArray(FindingsExport.json(listOf(finding())))
        assertEquals(1, arr.length())
        val o = arr.getJSONObject(0)
        assertEquals("CRITICAL", o.getString("severity"))
        assertEquals("Anonymous access", o.getString("type"))
        assertEquals("GET", o.getString("method"))
        assertEquals("http://h/admin?user_id=1", o.getString("url"))
        assertEquals(200, o.getInt("status"))
    }

    @Test fun emptyExportStillHasCsvHeader() {
        assertEquals("severity,type,detail,method,url,status\n", FindingsExport.csv(emptyList()))
        assertEquals("[]", FindingsExport.json(emptyList()))
    }
}
