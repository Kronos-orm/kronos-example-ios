package com.kotlinorm.example.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledSqliteProbeTest {
    @Test
    fun `bundled driver persists a row within its owned connection`() {
        val result = BundledSqliteProbe().insertAndRead("Kronos")

        assertTrue(result.id > 0)
        assertEquals("Kronos", result.name)
    }
}
