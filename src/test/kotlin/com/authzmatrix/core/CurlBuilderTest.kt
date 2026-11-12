package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CurlBuilderTest {

    @Test fun escapesSingleQuotesForPosixShell() {
        assertEquals("it'\\''s", CurlBuilder.quote("it's"))
    }

    @Test fun leavesPlainTextUntouched() {
        assertEquals("Authorization: Bearer abc.def.ghi", CurlBuilder.quote("Authorization: Bearer abc.def.ghi"))
    }
}
