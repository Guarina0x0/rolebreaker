package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticAssetsTest {

    @Test fun jsIsStatic() = assertTrue(StaticAssets.isStatic("https://h/app.js"))
    @Test fun cssWithQueryIsStatic() = assertTrue(StaticAssets.isStatic("https://h/x/style.css?v=2"))
    @Test fun apiIsNotStatic() = assertFalse(StaticAssets.isStatic("https://h/api/users"))
    @Test fun jsInParamIsNotStatic() = assertFalse(StaticAssets.isStatic("https://h/download?file=js"))
    @Test fun extensionInQueryIsNotStatic() =
        assertFalse(StaticAssets.isStatic("https://h/api/download?file=report.pdf"))
}
