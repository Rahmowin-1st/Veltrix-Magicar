package com.veltrix.ultron.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSafetyBoundaryTest {
    @Test
    fun blocksVehicleActuationButNotNormalHeadUnitTasks() {
        assertTrue(CarSafetyBoundary.blocksObjective("shift into reverse gear"))
        assertTrue(CarSafetyBoundary.blocksObjective("disable traction control"))
        assertTrue(CarSafetyBoundary.blocksObjective("start engine"))

        assertFalse(CarSafetyBoundary.blocksObjective("open YouTube and play my playlist"))
        assertFalse(CarSafetyBoundary.blocksObjective("open steering wheel settings page"))
        assertFalse(CarSafetyBoundary.blocksObjective("turn the music volume down"))
    }
}
