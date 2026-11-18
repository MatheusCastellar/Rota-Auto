package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class VideoCadenceTest {
    @Test fun changingRatePreservesTimelineAndAppliesOnNextFrame() {
        val cadence=VideoCadence(250)
        assertTrue(cadence.take(0))
        cadence.changed(); assertFalse(cadence.take(100))
        cadence.setInterval(83); assertTrue(cadence.take(100))
        cadence.setInterval(250); assertFalse(cadence.take(183))
        assertTrue(cadence.take(350))
        assertFalse(cadence.take(600)); assertTrue(cadence.take(850))
    }

    @Test fun staticPictureSendsTwoFramesPerSecondInsteadOfTwelve() {
        val cadence=VideoCadence(VideoProfile.forFps(12).intervalMs)
        assertEquals(listOf(0L,500L,1000L,1500L), (0L until 2000L).filter { cadence.take(it) })
    }
    @Test fun latestChangeWaitsOnlyForSelectedRateAndIsNotLostByEarlyPolling() {
        val cadence=VideoCadence(83)
        assertTrue(cadence.take(0)); cadence.changed()
        assertFalse(cadence.take(30)); cadence.changed()
        assertFalse(cadence.take(82)); assertTrue(cadence.take(83))
        cadence.changed(); assertFalse(cadence.take(165)); assertTrue(cadence.take(166))
        assertFalse(cadence.take(249)); assertTrue(cadence.take(666))
        cadence.changed(); assertTrue(cadence.take(750))
    }
    @Test fun movingPictureStillUsesTheSelectedMaximumRate() {
        for(fps in listOf(4,8,12)) {
            val cadence=VideoCadence(VideoProfile.forFps(fps).intervalMs)
            val frames=(0L until 1000L).count { cadence.changed(); cadence.take(it) }
            assertTrue("$fps fps produced $frames",frames in fps..fps+1)
        }
    }
}
