package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class BikeCommandRoutingTest {
    @Test fun dashboardTapUsesSourceBarPositionAndNeverFallsThroughWhenUnavailable() {
        for((command,x) in listOf(BikeCommand.AA_DASHBOARD_LEFT to 40,BikeCommand.AA_DASHBOARD_RIGHT to 760)) {
            val taps=mutableListOf<Pair<Int,Int>>()
            assertEquals(true,command.sendToAndroidAuto({error("Unexpected key")},{error("Unexpected rotation")},{a,b->taps+=a to b; true}))
            assertEquals(listOf(x to 440),taps)
            assertEquals(false,command.sendToAndroidAuto({error("Unexpected key")},{error("Unexpected rotation")},{_,_->false}))
        }
    }
    @Test fun navigationRoutesToOneAdvertisedInputWithoutMediaFallback() {
        val supported=AaServices.discovery().servicesList.single { it.id==3 }.inputSourceService.keycodesSupportedList
        val commands=mapOf(BikeCommand.BACK to 4, BikeCommand.AA_UP to 19, BikeCommand.AA_DOWN to 20,
            BikeCommand.AA_LEFT to 21, BikeCommand.AA_RIGHT to 22, BikeCommand.AA_SELECT to 23,
            BikeCommand.AA_ENTER to 66, BikeCommand.AA_HOME to 3, BikeCommand.AA_APPS to 187,
            BikeCommand.OPEN_MAPS to 0x10002, BikeCommand.OPEN_SPOTIFY to 0x10001)
        for((command,code) in commands) {
            val keys=mutableListOf<Int>()
            assertEquals(true,command.sendToAndroidAuto({ keys+=it; true },{ error("Unexpected rotation") }))
            assertEquals(listOf(code),keys)
            assertTrue(supported.contains(code))
            // Unavailable AA is a handled failure, never null (which would run phone media).
            assertEquals(false,command.sendToAndroidAuto({ false },{ error("Unexpected rotation") }))
        }
    }
    @Test fun rotaryAndPhoneActionsStayOnTheirOwnPaths() {
        for((command,delta) in listOf(BikeCommand.AA_PREVIOUS_ITEM to -1,BikeCommand.AA_NEXT_ITEM to 1)) {
            val rotations=mutableListOf<Int>()
            assertEquals(true,command.sendToAndroidAuto({ error("Unexpected key") },{ rotations+=it; true }))
            assertEquals(listOf(delta),rotations)
            assertEquals(false,command.sendToAndroidAuto({ error("Unexpected key") },{ false }))
        }
        for(command in listOf(BikeCommand.NONE,BikeCommand.VOICE,BikeCommand.PLAY_PAUSE,BikeCommand.PREVIOUS,
            BikeCommand.NEXT,BikeCommand.VOLUME_UP,BikeCommand.VOLUME_DOWN)) {
            assertNull(command.sendToAndroidAuto({ error("Phone action sent to AA") },{ error("Phone action rotated AA") }))
        }
    }
}
