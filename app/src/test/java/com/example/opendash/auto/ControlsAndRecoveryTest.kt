package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class ControlsAndRecoveryTest {
    @Test fun mapModeCodesAndDirectionalSelectionSurviveSavedBindings() {
        val controls=TripperControls()
        assertEquals(TripperControls.Action.RIGHT,controls.receive(0x13,1000).action)
        assertEquals(TripperControls.Action.LEFT,controls.receive(0x14,1001).action)
        assertEquals(TripperControls.Action.DOWN,controls.receive(0x15,1002).action)
        controls.useDirectionalSelection()
        val restored=TripperControls()
        restored.restore(TripperControls.Action.entries.mapNotNull { action -> controls.codeFor(action)?.let { action to it } }.toMap())
        for(c in listOf(controls,restored)) {
            assertEquals(TripperControls.Action.SELECT,c.receive(0x13,2000).action)
            assertEquals(TripperControls.Action.SELECT,c.receive(0x09,2001).action)
            assertEquals(TripperControls.Action.BACK,c.receive(0x14,2002).action)
            assertEquals(TripperControls.Action.BACK,c.receive(0x0A,2003).action)
            assertEquals(TripperControls.Action.DOWN,c.receive(0x15,2004).action)
            assertNull(c.receive(0x18,2005).action)
        }
        controls.arm(TripperControls.Action.SELECT)
        assertTrue(controls.receive(0x15,3000).learned)
        assertEquals(TripperControls.Action.SELECT,controls.receive(0x15,3300).action) // explicit calibration wins over alias
        restored.restore(TripperControls.Action.entries.mapNotNull { action -> controls.codeFor(action)?.let { action to it } }.toMap())
        assertEquals(TripperControls.Action.SELECT,restored.receive(0x15,4000).action)
        assertNull(restored.receive(0x09,4001).action) // removed binding must not reappear after restart
        assertNull(restored.receive(0x18,4002).action)
    }
    @Test fun mapsObservedDirectionsAndIgnoresUnknownCodes() {
        val controls=TripperControls()
        assertEquals(TripperControls.Action.UP,controls.receive(6,1000).action)
        assertEquals(TripperControls.Action.DOWN,controls.receive(7,1001).action)
        assertEquals(TripperControls.Action.LEFT,controls.receive(10,1002).action)
        assertEquals(TripperControls.Action.RIGHT,controls.receive(9,1003).action)
        assertEquals(TripperControls.Action.SELECT,controls.receive(24,1004).action)
        assertEquals(TripperControls.Action.BACK,controls.receive(18,1005).action)
        assertNull(controls.receive(99,1006).action)
        assertTrue(controls.receive(0,1007).ignored)
    }
    @Test fun calibrationConsumesPressAndRemovesDuplicateAssignment() {
        val controls=TripperControls()
        controls.arm(TripperControls.Action.SELECT)
        val event=controls.receive(9,1000)
        assertTrue(event.learned)
        assertEquals(TripperControls.Action.SELECT,event.action)
        assertNull(controls.learning)
        assertNull(controls.codeFor(TripperControls.Action.RIGHT))
        assertNull(controls.receive(24,1300).action)
        assertEquals(TripperControls.Action.SELECT,controls.receive(9,1600).action)
        controls.reset()
        assertEquals(TripperControls.Action.RIGHT,controls.receive(9,1601).action)
    }
    @Test fun debouncesRepeatedPacketButAcceptsDifferentDirection() {
        val controls=TripperControls()
        assertNotNull(controls.receive(6,1000).action)
        assertTrue(controls.receive(6,1100).ignored)
        assertNotNull(controls.receive(7,1101).action)
        assertNotNull(controls.receive(7,1400).action)
    }
    @Test fun retriesOnlyEstablishedSessionsAndStopsAfterThreeFailures() {
        val r=ProjectionRetry()
        assertNull(r.nextDelay(0,true))
        r.manualStart(); r.video(1000)
        assertEquals(3000L,r.nextDelay(2000,true))
        assertEquals(8000L,r.nextDelay(5000,true))
        assertEquals(15000L,r.nextDelay(14000,true))
        assertNull(r.nextDelay(30000,true))
        assertNull(r.nextDelay(60000,true))
    }
    @Test fun stableVideoResetsBudgetButShortReconnectDoesNot() {
        val r=ProjectionRetry(); r.video(0)
        assertEquals(3000L,r.nextDelay(1000,true))
        r.video(5000)
        assertEquals(8000L,r.nextDelay(6000,true))
        r.video(15000)
        assertEquals(3000L,r.nextDelay(75000,true))
        assertNull(r.nextDelay(76000,false))
    }
}
