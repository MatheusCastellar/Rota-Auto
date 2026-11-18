package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class BikeButtonGesturesTest {
    private val down=TripperControls.Action.DOWN
    private val up=TripperControls.Action.UP
    private fun press(g:BikeButtonGestures,b:TripperControls.Action,t:Long)=g.press(b,t,BikeControlLayout.default(true,b),BikeControlLayout.default(true,b,true))
    @Test fun disabledDoubleTapRunsEveryAcceptedPressImmediatelyInEveryProfile() {
        for(profile in 1..3) for(button in TripperControls.mediaActions) {
            val g=BikeButtonGestures()
            val single=BikeControlLayout.resolve(profile,button,false,"VOLUME_UP")
            val double=BikeControlLayout.resolve(profile,button,true,"NONE")
            for(now in listOf(1000L,1100L,1250L)) {
                assertEquals(listOf(BikeCommand.VOLUME_UP),g.press(button,now,single,double))
                assertNull(g.deadline)
            }
            assertTrue(g.flush(5000).isEmpty())
        }
    }
    @Test fun disabledDoubleTapStaysImmediateThroughRepeatedPacketDeduplication() {
        val c=TripperControls(true); val g=BikeButtonGestures()
        fun receive(code:Int,now:Long):List<BikeCommand> {
            val event=c.receive(code,now,100)
            return event.action?.let { g.press(it,now,BikeCommand.VOLUME_UP,BikeCommand.NONE) } ?: emptyList()
        }
        assertEquals(listOf(BikeCommand.VOLUME_UP),receive(7,1000))
        assertTrue(receive(7,1050).isEmpty()) // duplicate packet, not a delayed action
        assertEquals(listOf(BikeCommand.VOLUME_UP),receive(7,1100))
        assertNull(g.deadline)
        assertTrue(g.flush(5000).isEmpty())
    }
    @Test fun singleDownWaitsThenRaisesVolumeExactlyOnce() {
        val g=BikeButtonGestures()
        assertTrue(press(g,down,1000).isEmpty())
        assertTrue(g.flush(3999).isEmpty())
        assertEquals(listOf(BikeCommand.VOLUME_UP),g.flush(4000))
        assertTrue(g.flush(5000).isEmpty()); assertNull(g.deadline)
    }
    @Test fun doubleDownLowersWithoutRaisingFirstInSamePhysicalContext() {
        val c=TripperControls(true); val g=BikeButtonGestures()
        assertTrue(press(g,c.receive(7,1000,100).action!!,1000).isEmpty())
        assertTrue(c.receive(7,1050,100).ignored)
        assertEquals(listOf(BikeCommand.VOLUME_DOWN),press(g,c.receive(7,1200,100).action!!,1200))
        assertTrue(g.flush(1700).isEmpty())
    }
    @Test fun upCallsVoiceImmediatelyAndCenterHomeDoNothingByDefault() {
        val g=BikeButtonGestures()
        assertEquals(listOf(BikeCommand.VOICE),press(g,up,1000))
        for(b in listOf(TripperControls.Action.SELECT,TripperControls.Action.HOME,TripperControls.Action.LEFT,TripperControls.Action.RIGHT)) assertTrue(press(g,b,1500).isEmpty())
        assertNull(g.deadline)
    }
    @Test fun separatedDownPressesAreTwoIncreasesNotADecrease() {
        val g=BikeButtonGestures()
        press(g,down,1000)
        assertEquals(listOf(BikeCommand.VOLUME_UP),press(g,down,4100))
        assertEquals(listOf(BikeCommand.VOLUME_UP),g.flush(7100))
    }
    @Test fun changingModeCalibrationOrDisconnectCanCancelPendingVolume() {
        val g=BikeButtonGestures(); press(g,down,1000); g.cancel()
        assertTrue(g.flush(2000).isEmpty()); assertNull(g.deadline)
    }
    @Test fun customDoubleTapReplacesSingleActionOnAnyButton() {
        val g=BikeButtonGestures()
        assertTrue(g.press(up,1000,BikeCommand.NEXT,BikeCommand.BACK).isEmpty())
        assertEquals(listOf(BikeCommand.BACK),g.press(up,1300,BikeCommand.NEXT,BikeCommand.BACK))
        assertTrue(g.flush(2000).isEmpty())
        assertEquals(BikeCommand.NEXT,BikeControlLayout.resolve(true,up,false,"NEXT"))
        assertEquals(BikeCommand.VOICE,BikeControlLayout.resolve(true,up,false,"invalid"))
    }
    @Test fun differentButtonCompletesSingleBeforeItsOwnCommand() {
        val g=BikeButtonGestures(); press(g,down,1000)
        assertEquals(listOf(BikeCommand.VOLUME_UP,BikeCommand.VOICE),press(g,up,1200))
        assertTrue(g.flush(2000).isEmpty())
    }
    @Test fun deliberateSecondPressAfterOldTimeoutLowersWithoutFirstIncreasing() {
        for(gap in listOf(600L,1500L,2900L)) {
            val c=TripperControls(true); val g=BikeButtonGestures()
            assertTrue(press(g,c.receive(7,1000,100).action!!,1000).isEmpty())
            assertTrue(g.flush(1500).isEmpty())
            val second=c.receive(7,1000+gap,100).action!!
            assertEquals(listOf(BikeCommand.VOLUME_DOWN),press(g,second,1000+gap))
            assertTrue(g.flush(5000).isEmpty())
        }
    }
    @Test fun missingRapidSecondPacketCanBeFollowedByDeliberateRepress() {
        val g=BikeButtonGestures()
        press(g,down,1000) // firmware never delivered the attempted rapid second press
        assertTrue(g.flush(2000).isEmpty())
        assertEquals(listOf(BikeCommand.VOLUME_DOWN),press(g,down,3400))
        assertTrue(g.flush(6000).isEmpty())
    }
    @Test fun thirdPressStartsNewGestureWithoutReusingCompletedPair() {
        val g=BikeButtonGestures()
        press(g,down,1000)
        assertEquals(listOf(BikeCommand.VOLUME_DOWN),press(g,down,2500))
        assertTrue(press(g,down,3000).isEmpty())
        assertEquals(listOf(BikeCommand.VOLUME_UP),g.flush(6000))
    }
}
