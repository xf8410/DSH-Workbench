package io.github.jackwener.dsh.core.workbench

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UmaFrameStoreTest {
    @Test fun `accept keeps previous and diffs all scenario fields`() {
        var now = 100L
        val store = UmaFrameStore { now }
        val first = store.accept("""{"turn":1,"stats":{"speed":100},"matsuri":{"festival":0},"ai":null}""")
        assertEquals(1L, first.sequence)
        assertNull(first.previous)
        assertEquals(setOf("turn", "stats", "matsuri", "ai"), first.changedFields.toSet())

        now = 200L
        val second = store.accept("""{"turn":2,"stats":{"speed":100},"matsuri":{"festival":1},"trainings":[]}""")
        assertEquals(2L, second.sequence)
        assertEquals(200L, second.receivedAtMs)
        assertEquals(1, second.previous?.get("turn")?.jsonPrimitive?.int)
        assertEquals(setOf("turn", "matsuri", "ai", "trainings"), second.changedFields.toSet())
        assertEquals(1, second.changes.getValue("matsuri").jsonObject.getValue("after").jsonObject
            .getValue("festival").jsonPrimitive.int)
        val changed = store.changes()!!.getValue("changedFields").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(second.changedFields, changed)
    }

    @Test fun `bad frame never replaces last good snapshot`() {
        val store = UmaFrameStore()
        store.accept("""{"turn":7}""")
        assertTrue(runCatching { store.accept("[]") }.isFailure)
        assertTrue(runCatching { store.accept("not json") }.isFailure)
        assertEquals(1L, store.latest()?.sequence)
        assertEquals(7, store.latest()?.current?.get("turn")?.jsonPrimitive?.int)
        assertEquals(1L, store.snapshot()?.get("sequence")?.jsonPrimitive?.long)
    }
}
