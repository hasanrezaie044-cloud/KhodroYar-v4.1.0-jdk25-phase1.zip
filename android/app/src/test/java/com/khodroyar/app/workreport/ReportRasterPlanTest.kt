package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

class ReportRasterPlanTest {
    @Test fun slightlyOversizedPhotoIsNotNeedlesslyHalvedAndPrintIsRetained() {
        val plan = ReportRasterPlan.of(2200, 2900, 6000000, 3200)
        assertTrue(plan.width > 2000 && plan.height > 2700); assertEquals(1, plan.sample)
        assertTrue(plan.width.toLong() * plan.height <= 6000000 && maxOf(plan.width, plan.height) <= 3200)
    }
    @Test fun hugeCameraImageStillDecodesWithABoundedSampleAndDensity() {
        val plan = ReportRasterPlan.of(12000, 9000, 6000000, 3200)
        assertTrue(plan.sample >= 4); assertTrue(plan.density in 1..1000000)
        assertTrue(plan.width.toLong() * plan.height <= 6000000 && maxOf(plan.width, plan.height) <= 3200)
    }
    @Test fun thumbnailsAndAlreadySmallImagesKeepTheirBounds() {
        val small = ReportRasterPlan.of(320, 240, 6000000, 3200)
        assertEquals(320, small.width); assertEquals(240, small.height); assertEquals(1000000, small.density)
        val thumb = ReportRasterPlan.of(4000, 3000, 120000, 420)
        assertTrue(thumb.width <= 420 && thumb.height <= 420 && thumb.width.toLong() * thumb.height <= 120000)
    }
}
