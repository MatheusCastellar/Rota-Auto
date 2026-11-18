package com.example.opendash.auto
import org.junit.Assert.*
import org.junit.Test
class MediaButtonMappingTest {
    @Test fun mapAndMediaEventsRemainIndependent() {
        val c=TripperControls(true)
        val expected=listOf(0x13 to TripperControls.Action.MAP_RIGHT,0x14 to TripperControls.Action.MAP_LEFT,
            0x15 to TripperControls.Action.MAP_DOWN,0x18 to TripperControls.Action.MAP_ENTER,
            5 to TripperControls.Action.SELECT,9 to TripperControls.Action.RIGHT,10 to TripperControls.Action.LEFT,
            6 to TripperControls.Action.UP,7 to TripperControls.Action.DOWN)
        expected.forEachIndexed { i,(code,action) -> assertEquals(action,c.receive(code,1000L+i).action) }
        assertEquals(expected.map { it.second }.toSet(),TripperControls.mediaActions.toSet())
    }
    @Test fun duplicatePacketDoesNotSkipTwoTracksButMapEventIsNotAlias() {
        val c=TripperControls(true)
        assertEquals(TripperControls.Action.RIGHT,c.receive(9,1000,100).action)
        assertTrue(c.receive(9,1050,100).ignored)
        assertEquals(TripperControls.Action.MAP_RIGHT,c.receive(0x13,1060,100).action)
        assertEquals(TripperControls.Action.RIGHT,c.receive(9,1250,100).action)
    }
    @Test fun homeCannotHijackCentersOrInvokeVoice() {
        val c=TripperControls(true)
        c.restore(mapOf(TripperControls.Action.SELECT to 5,TripperControls.Action.HOME to 24))
        c.bind(24,TripperControls.Action.HOME); c.arm(TripperControls.Action.HOME); c.useDirectionalSelection()
        assertNull(c.learning); assertNull(c.codeFor(TripperControls.Action.HOME))
        assertFalse(TripperControls.mediaActions.contains(TripperControls.Action.HOME))
        assertEquals(TripperControls.Action.SELECT,c.receive(5,1000).action)
        assertEquals(TripperControls.Action.MAP_ENTER,c.receive(24,1100).action)
        for(code in listOf(11,18)) assertNull(c.receive(code,1200).action)
    }
    @Test fun oldCalibrationDoesNotCollapseContexts() {
        val c=TripperControls(true)
        assertFalse(c.importNavigationBindings(TripperControls.defaults.entries.associate { it.value to it.key }))
        assertEquals(TripperControls.Action.RIGHT,c.receive(9,1000).action)
        assertEquals(TripperControls.Action.MAP_RIGHT,c.receive(0x13,1001).action)
    }
    @Test fun doubleTapCannotSpanContexts() {
        val c=TripperControls(true); val g=BikeButtonGestures()
        fun press(code:Int,now:Long)=g.press(c.receive(code,now).action!!,now,BikeCommand.VOLUME_UP,BikeCommand.VOLUME_DOWN)
        assertTrue(press(0x15,1000).isEmpty())
        assertEquals(listOf(BikeCommand.VOLUME_UP),press(7,1400))
        assertEquals(listOf(BikeCommand.VOLUME_DOWN),press(7,1800))
        assertTrue(g.flush(5000).isEmpty())
    }
}
