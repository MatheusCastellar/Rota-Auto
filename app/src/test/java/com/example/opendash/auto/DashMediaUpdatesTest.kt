package com.example.opendash.auto

import com.example.opendash.dash.*
import com.example.opendash.dash.protocol.*
import org.junit.Assert.*
import org.junit.Test

class DashMediaUpdatesTest {
    private val label=DashMediaCard.panelLabel("Android Auto")
    @Test fun playbackChangesOnlySendStateAndRetainItAcrossLabelsAndReconnects() {
        val updates=DashMediaUpdates()
        assertArrayEquals(DashCommands.musicPlaying(false),updates.next(label,0)[1])
        assertArrayEquals(DashCommands.musicPlaying(true),updates.next(label,250,true).single())
        assertTrue(updates.next(label,500,true).isEmpty())
        assertArrayEquals(DashCommands.musicPlaying(false),updates.next(label,750,false).single())
        assertEquals(0x0D,updates.next(DashMediaCard.panelLabel("Volume +"),1000,false).single()[18].toInt())
        updates.next(null,1250,false)
        assertArrayEquals(DashCommands.musicPlaying(false),updates.next(label,1500,false)[1])
    }
    @Test fun activatesUsingExactOfficialConstantsAndFirstFieldFallbackLayout() {
        val packets=DashMediaUpdates().next(label,0,true)
        // Royal 10.1.23(45), w2c.l1 and i1, read directly from installed APK.
        assertArrayEquals("0016000200000000020100054B314720000517000155".hexToBytes(),packets[0])
        assertArrayEquals("0016000200000000020100054B314720000519000155".hexToBytes(),packets[1])
        assertArrayEquals("0023000200000000020100054B31472000050D000E416E64726F6964204175746F0000".hexToBytes(),packets[2])
        assertEquals(3,packets.size)
        assertArrayEquals("0016000200000000020100054B3147200005190001AA".hexToBytes(),DashCommands.musicPlaying(false))
    }
    @Test fun idleNeverReactivatesWidgetAndOnlyRefreshesMetadataEveryFiveSeconds() {
        val updates=DashMediaUpdates()
        updates.next(label,0)
        for(t in 250L..4_750L step 250) assertTrue(updates.next(label,t).isEmpty())
        val refresh=updates.next(label,5_000).single()
        assertEquals(0x0D,refresh[18].toInt())
        assertTrue(updates.next(label,5_250).isEmpty())
    }
    @Test fun actionAndRestoreUpdateOnlyTextAndNewSessionEnablesAgain() {
        val updates=DashMediaUpdates(); updates.next(label,0)
        val action=updates.next(DashMediaCard.panelLabel("Volume +"),250).single()
        assertArrayEquals("Volume +\u0000\u0000".toByteArray(),action.copyOfRange(21,action.size))
        val restored=updates.next(label,3250).single()
        assertArrayEquals("Android Auto\u0000\u0000".toByteArray(),restored.copyOfRange(21,restored.size))
        assertArrayEquals(DashCommands.musicActive(false),updates.next(null,3500).single())
        assertTrue(updates.next(null,3750).isEmpty())
        assertEquals(3,updates.next(label,4000).size)
        assertEquals(3,DashMediaUpdates().next(label,4000).size)
    }
}
