package com.nathanhanapps.appdual

import org.junit.Assert.*
import org.junit.Test

class TabletDisplaySizeTest {
    @Test fun guaranteesTabletShortEdgeAcrossDensities() {
        for (density in listOf(120, 160, 240, 320, 480, 640, 1000)) {
            val size = TabletDisplaySize.forDensity(density)
            assertTrue(size.width * 160.0 / density >= 640)
            assertTrue(size.height > size.width)
        }
    }
    @Test fun preservesTheVerified480DpiConfiguration() {
        assertEquals(TabletDisplaySize(1920, 2560, 480), TabletDisplaySize.forDensity(480))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnInvalidDensity() { TabletDisplaySize.forDensity(0) }
}
