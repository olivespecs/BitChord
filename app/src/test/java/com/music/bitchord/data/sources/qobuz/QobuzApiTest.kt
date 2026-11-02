package com.music.bitchord.data.sources.qobuz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QobuzApiTest {
    @Test
    fun `normalizeIsrc uppercases and trims valid values`() {
        assertEquals("USRC17607839", QobuzApi.normalizeIsrc(" usrc17607839 "))
    }

    @Test
    fun `normalizeIsrc rejects blank value`() {
        assertNull(QobuzApi.normalizeIsrc(" "))
    }

    @Test
    fun `normalizeIsrc rejects malformed value`() {
        assertNull(QobuzApi.normalizeIsrc("NOT-AN-ISRC"))
    }
}
