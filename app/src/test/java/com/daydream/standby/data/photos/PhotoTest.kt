package com.daydream.standby.data.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoTest {
    @Test fun `landscape and portrait`() {
        assertEquals(1.5f, displayAspect(3000, 2000, rotated90 = false)!!, 1e-6f)
        assertEquals(0.5f, displayAspect(1000, 2000, rotated90 = false)!!, 1e-6f)
    }

    @Test fun `rotation swaps width and height`() {
        assertEquals(2f / 3f, displayAspect(3000, 2000, rotated90 = true)!!, 1e-6f)
        assertEquals(2f, displayAspect(1000, 2000, rotated90 = true)!!, 1e-6f)
    }

    @Test fun `square is 1 either way`() {
        assertEquals(1f, displayAspect(500, 500, rotated90 = false)!!, 1e-6f)
        assertEquals(1f, displayAspect(500, 500, rotated90 = true)!!, 1e-6f)
    }

    @Test fun `null zero or negative dimensions give null`() {
        assertNull(displayAspect(null, 100, false))
        assertNull(displayAspect(100, null, false))
        assertNull(displayAspect(null, null, true))
        assertNull(displayAspect(0, 100, false))
        assertNull(displayAspect(100, 0, true))
        assertNull(displayAspect(-1, 100, false))
        assertNull(displayAspect(100, -50, true))
    }
}
