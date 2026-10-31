package com.privateassistant.core.tools

import com.privateassistant.core.assistant.PhotoSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToolPlanningTest {
    /** A synthetic monotonic size model standing in for a real encoder in JVM tests. */
    private fun model(base: Long) = { quality: Int, scale: Double -> (base * scale * scale * (0.2 + quality / 100.0)).toLong() }

    @Test fun `search returns the highest quality at full scale when it fits`() {
        var calls = 0
        val encode = model(300_000)
        val fit = SizeTargetSearch.fit(200_000, { q, s -> calls++; encode(q, s) }, { it }) as SizeFit.Fitted
        assertTrue(fit.bytes <= 200_000)
        assertEquals(1.0, fit.scale, 0.0)
        // One quality higher must exceed the target, proving it is the best fitting quality.
        assertTrue(fit.quality == SizeTargetSearch.MAX_QUALITY || encode(fit.quality + 1, 1.0) > 200_000)
        assertTrue(calls <= 10)
    }

    @Test fun `search downscales when quality alone cannot reach the target`() {
        val fit = SizeTargetSearch.fit(50_000, model(1_000_000), { it }) as SizeFit.Fitted
        assertTrue(fit.scale < 1.0)
        assertTrue(fit.bytes <= 50_000)
    }

    @Test fun `unreachable targets report the smallest measured size`() {
        val result = SizeTargetSearch.fit(10, model(10_000_000), { it })
        assertTrue(result is SizeFit.Unreachable)
        assertTrue((result as SizeFit.Unreachable).smallestBytes > 10)
    }

    @Test fun `passport crop keeps the spec aspect ratio and stays in bounds`() {
        val face = FaceGeometry(eyeCenterX = 1000f, eyeCenterY = 900f, eyeDistance = 200f)
        for (spec in PhotoSpec.entries) {
            val crop = PassportPhotoLayout.crop(2000, 3000, face, spec)
            val aspect = (crop.right - crop.left).toFloat() / (crop.bottom - crop.top)
            assertTrue(abs(aspect - spec.widthPx.toFloat() / spec.heightPx) < 0.01f)
            assertTrue(crop.left >= 0 && crop.top >= 0 && crop.right <= 2000 && crop.bottom <= 3000)
            // Eyes sit in the upper half of the result.
            assertTrue(900 - crop.top < (crop.bottom - crop.top) / 2)
            assertTrue(1000 in crop.left..crop.right)
        }
    }

    @Test fun `passport crop shrinks around an off-frame face and centres without one`() {
        val edge = PassportPhotoLayout.crop(800, 600, FaceGeometry(20f, 20f, 300f), PhotoSpec.PASSPORT_35X45)
        assertTrue(edge.left >= 0 && edge.top >= 0 && edge.right <= 800 && edge.bottom <= 600)
        val none = PassportPhotoLayout.crop(1200, 900, null, PhotoSpec.PASSPORT_35X45)
        assertEquals(0, none.top)
        assertEquals(900, none.bottom)
        assertTrue(abs((none.left + none.right) / 2 - 600) <= 1)
    }

    @Test fun `byte sizes never round up past a limit`() {
        assertEquals("199.9 KB", ByteSizes.format(199_960))
        assertEquals("1.99 MB", ByteSizes.format(1_999_999))
        assertEquals("512 bytes", ByteSizes.format(512))
    }
}
