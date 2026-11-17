package com.authzmatrix.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticAssetsTest {

    @Test fun jsIsStatic() = assertTrue(StaticAssets.isStatic("https://h/app.js"))
    @Test fun cssWithQueryIsStatic() = assertTrue(StaticAssets.isStatic("https://h/x/style.css?v=2"))
    @Test fun apiIsNotStatic() = assertFalse(StaticAssets.isStatic("https://h/api/users"))
    @Test fun jsInParamIsNotStatic() = assertFalse(StaticAssets.isStatic("https://h/download?file=js"))
    @Test fun modernAssetsAreStatic() {
        assertTrue(StaticAssets.isStatic("https://h/img/photo.avif"))
        assertTrue(StaticAssets.isStatic("https://h/app.wasm"))
    }
    @Test fun extensionInQueryIsNotStatic() =
        assertFalse(StaticAssets.isStatic("https://h/api/download?file=report.pdf"))
}
