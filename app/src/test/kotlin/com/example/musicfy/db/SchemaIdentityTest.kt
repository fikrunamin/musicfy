package com.example.musicfy.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Room refuses to open (or destructively resets) a database whose identity hash differs from the
 * one compiled into the app. Moving entities between modules must therefore leave the exported
 * schema byte-for-byte the same; this pins the hash KSP writes on every build.
 */
class SchemaIdentityTest {
    private val schema = File("schemas/com.example.musicfy.db.InternalDatabase/39.json")
    private val golden = File("src/test/resources/room/InternalDatabase-39.identityHash")

    @Test
    fun exportedSchemaIsVersion39() {
        assertTrue("missing $schema - did KSP run?", schema.exists())
        assertTrue(Regex("\"version\"\\s*:\\s*39\\b").containsMatchIn(schema.readText()))
    }

    @Test
    fun identityHashMatchesBaseline() {
        val hash = Regex("\"identityHash\"\\s*:\\s*\"([0-9a-f]{32})\"")
            .find(schema.readText())
            ?.groupValues
            ?.get(1)
        assertEquals(golden.readText().trim(), hash)
    }
}
