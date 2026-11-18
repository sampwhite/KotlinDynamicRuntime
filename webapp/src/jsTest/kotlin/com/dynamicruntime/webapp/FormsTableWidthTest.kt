package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ColumnWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A forms-list column's width for the size its client named (issue #1855). */
class FormsTableWidthTest {
    @Test
    fun aNarrowColumnIsNarrowerThanANormalOneAndAWideOneWider() {
        assertEquals(144, displayColumnWidthOf(ColumnWidth.normal))
        assertTrue(displayColumnWidthOf(ColumnWidth.narrow) < displayColumnWidthOf(ColumnWidth.normal))
        assertTrue(displayColumnWidthOf(ColumnWidth.wide) > displayColumnWidthOf(ColumnWidth.normal))
    }
}
