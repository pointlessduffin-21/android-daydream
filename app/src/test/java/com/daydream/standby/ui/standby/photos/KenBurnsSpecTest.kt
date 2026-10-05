package com.daydream.standby.ui.standby.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KenBurnsSpecTest {
    @Test fun `same seed gives the same motion`() {
        assertEquals(KenBurnsSpec.random(42), KenBurnsSpec.random(42))
        assertNotEquals(KenBurnsSpec.random(1), KenBurnsSpec.random(2))
    }

    @Test fun `pan never exceeds the zoom overflow`() {
        for (seed in 0L until 500L) {
            val spec = KenBurnsSpec.random(seed)
            for (step in 0..20) {
                val frame = spec.at(step / 20f)
                val overflow = (frame.scale - 1f) / 2f
                assertTrue("seed $seed", frame.scale >= 1f)
                assertTrue("seed $seed x", abs(frame.offsetX) <= overflow + 1e-6f)
                assertTrue("seed $seed y", abs(frame.offsetY) <= overflow + 1e-6f)
            }
        }
    }

    @Test fun `zoom stays within bounds and moves`() {
        for (seed in 0L until 200L) {
            val spec = KenBurnsSpec.random(seed)
            assertTrue(spec.startScale in 1f..1.18f && spec.endScale in 1f..1.18f)
            assertTrue(abs(spec.endScale - spec.startScale) > 0.1f)
        }
    }

    @Test fun `intensity scales the zoom and zero is still`() {
        val gentle = KenBurnsSpec.random(7, intensity = 0.5f)
        assertTrue(maxOf(gentle.startScale, gentle.endScale) <= 1.09f + 1e-6f)
        assertEquals(KenBurnsSpec.STILL, KenBurnsSpec.random(7, intensity = 0f))
        assertEquals(KenBurnsSpec.Frame(1f, 0f, 0f), KenBurnsSpec.STILL.at(0.5f))
    }

    @Test fun `progress is clamped`() {
        val spec = KenBurnsSpec.random(3)
        assertEquals(spec.at(0f), spec.at(-1f))
        assertEquals(spec.at(1f), spec.at(2f))
        assertEquals(spec.startScale, spec.at(0f).scale, 1e-6f)
        assertEquals(spec.endScale, spec.at(1f).scale, 1e-6f)
    }
}
