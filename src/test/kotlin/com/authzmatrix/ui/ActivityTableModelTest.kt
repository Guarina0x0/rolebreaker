package com.authzmatrix.ui

import com.authzmatrix.model.ActivityEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityTableModelTest {

    @Test fun dropsOldestRowsPastTheCap() {
        val model = ActivityTableModel()
        val total = ActivityTableModel.MAX_ROWS + 5
        repeat(total) { i ->
            model.add(ActivityEntry("00:00:00", "test", "GET", "http://h/$i", 200, 0, null, null))
        }
        assertEquals(ActivityTableModel.MAX_ROWS, model.rowCount)
        assertEquals("http://h/5", model.entryAt(0)?.url)
    }
}
