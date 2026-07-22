package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdorAnalyzerTest {

    @Test fun numericIsIdLike() = assertTrue(IdorAnalyzer.idLike("123"))

    @Test fun uuidIsIdLike() = assertTrue(IdorAnalyzer.idLike("550e8400-e29b-41d4-a716-446655440000"))

    @Test fun shortWordIsNotIdLike() = assertFalse(IdorAnalyzer.idLike("abc"))

    @Test fun emptyIsNotIdLike() = assertFalse(IdorAnalyzer.idLike(""))
}
