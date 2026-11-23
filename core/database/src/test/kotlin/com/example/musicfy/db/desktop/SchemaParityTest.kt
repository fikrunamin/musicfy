package com.example.musicfy.db.desktop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Desktop and Android must agree on every table, so a database (or backup) from one platform is
 * valid on the other. Room's identity hash covers entities and views only, not the DAO.
 */
class SchemaParityTest {
    private val desktop = File("schemas/com.example.musicfy.db.desktop.DesktopMusicDatabase/39.json")
    private val android = File("../../app/schemas/com.example.musicfy.db.InternalDatabase/39.json")

    @Test
    fun desktopSchemaIsIdenticalToAndroidSchema() {
        val d = JSONObject(desktop.readText()).getJSONObject("database")
        val a = JSONObject(android.readText()).getJSONObject("database")
        assertEquals(39, d.getInt("version"))
        assertEquals(a.getString("identityHash"), d.getString("identityHash"))
        assertTrue("entities/views/setupQueries differ from the Android schema", a.similar(d))
    }
}
