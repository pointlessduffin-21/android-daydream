package com.daydream.standby.data.photos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPhotoQueryTest {
    private fun build(vararg ids: String) = LocalPhotoQuery.build(ids.toList(), "bucket_id", "bucket_name")
    private val defaultSelection = "bucket_name IS NULL OR bucket_name NOT IN (?, ?)"
    private val defaultArgs = arrayOf("Screenshots", "Screen recordings")

    @Test fun `empty selection excludes screenshots and screen recordings`() {
        val q = build()
        assertEquals(defaultSelection, q.selection)
        assertArrayEquals(defaultArgs, q.selectionArgs)
    }

    @Test fun `ids produce an IN clause in order`() {
        val q = build("30", "10", "20")
        assertEquals("bucket_id IN (?, ?, ?)", q.selection)
        assertArrayEquals(arrayOf("30", "10", "20"), q.selectionArgs)
    }

    @Test fun `single id`() {
        val q = build("-5")
        assertEquals("bucket_id IN (?)", q.selection)
        assertArrayEquals(arrayOf("-5"), q.selectionArgs)
    }

    @Test fun `non-numeric ids are dropped`() {
        val q = build("1", "abc", "", "2; DROP TABLE", "3")
        assertEquals("bucket_id IN (?, ?)", q.selection)
        assertArrayEquals(arrayOf("1", "3"), q.selectionArgs)
    }

    @Test fun `only junk ids falls back to the default filter`() {
        val q = build("x", "")
        assertEquals(defaultSelection, q.selection)
        assertArrayEquals(defaultArgs, q.selectionArgs)
    }

    @Test fun `duplicates collapse`() {
        val q = build("1", "2", "1", " 2 ")
        assertEquals("bucket_id IN (?, ?)", q.selection)
        assertArrayEquals(arrayOf("1", "2"), q.selectionArgs)
    }

    @Test fun `ids beyond the cap are ignored`() {
        val ids = (1..LocalPhotoQuery.MAX_BUCKET_IDS + 50).map { it.toString() }
        val q = LocalPhotoQuery.build(ids, "bucket_id", "bucket_name")
        assertEquals(LocalPhotoQuery.MAX_BUCKET_IDS, q.selectionArgs.size)
        assertEquals(LocalPhotoQuery.MAX_BUCKET_IDS, q.selection.count { it == '?' })
        assertEquals("1", q.selectionArgs.first())
    }

    @Test fun `junk does not count toward the cap`() {
        val ids = List(10) { "junk$it" } + (1..LocalPhotoQuery.MAX_BUCKET_IDS).map { it.toString() }
        assertEquals(LocalPhotoQuery.MAX_BUCKET_IDS, LocalPhotoQuery.build(ids, "a", "b").selectionArgs.size)
    }
}
