package com.authzmatrix.core

import com.authzmatrix.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EndpointRiskTest {

    @Test fun privilegedPathIsHigh() = assertEquals(Severity.HIGH, EndpointRisk.severity("https://h/admin/users"))
    @Test fun numericIdIsHigh() = assertEquals(Severity.HIGH, EndpointRisk.severity("https://h/api/users/123"))
    @Test fun uuidIsHigh() =
        assertEquals(Severity.HIGH, EndpointRisk.severity("https://h/o/550e8400-e29b-41d4-a716-446655440000"))
    @Test fun idorParamIsHigh() = assertEquals(Severity.HIGH, EndpointRisk.severity("https://h/x?user_id=5"))
    @Test fun numericParamIsMedium() = assertEquals(Severity.MEDIUM, EndpointRisk.severity("https://h/x?page=42"))
    @Test fun benignIsNull() = assertNull(EndpointRisk.severity("https://h/about"))

    @Test fun escalatePicksMoreSevere() {
        assertEquals(Severity.HIGH, EndpointRisk.escalate(Severity.MEDIUM, Severity.HIGH))
        assertEquals(Severity.MEDIUM, EndpointRisk.escalate(Severity.MEDIUM, null))
        assertEquals(Severity.HIGH, EndpointRisk.escalate(Severity.HIGH, Severity.MEDIUM))
    }
}
