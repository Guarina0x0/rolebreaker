package com.authzmatrix.ui

import com.authzmatrix.core.PersonaStore
import com.authzmatrix.model.Persona
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonaTableModelTest {

    @Test fun tokenSummaryColumnIsReadOnly() {
        val store = PersonaStore()
        store.personas.add(Persona("admin"))
        val model = PersonaTableModel(store)

        assertEquals(5, PersonaTableModel.TOKEN_COLUMN)
        assertFalse(model.isCellEditable(0, PersonaTableModel.TOKEN_COLUMN))
        assertTrue(model.isCellEditable(0, 1), "name remains editable")
        assertTrue(model.isCellEditable(0, 6), "level remains editable")
    }
}
