package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertTrue

class PayloadLibraryTest {

    @Test
    fun `bundled payload resource loads`() {
        val cats = PayloadLibrary.categories
        assertTrue(cats.size >= 20, "expected a rich set of categories, got ${cats.size}")
        assertTrue(PayloadLibrary.total >= 150, "expected many payloads, got ${PayloadLibrary.total}")
    }

    @Test
    fun `every payload has a non-blank value`() {
        val blanks = PayloadLibrary.categories.flatMap { (cat, list) ->
            list.filter { it.value.isBlank() }.map { cat }
        }
        assertTrue(blanks.isEmpty(), "blank payloads in: $blanks")
    }

    @Test
    fun `no duplicate category names`() {
        val names = PayloadLibrary.categories.keys
        assertTrue(names.size == names.toSet().size, "duplicate category names")
    }

    @Test
    fun `key categories are present`() {
        val names = PayloadLibrary.categories.keys
        listOf("XSS", "SSTI (template injection)", "SSRF - cloud metadata", "Log4Shell / JNDI")
            .forEach { assertTrue(names.contains(it), "missing category: $it") }
    }
}
