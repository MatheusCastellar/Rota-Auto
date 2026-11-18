package com.example.opendash.dash
import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun chooserCancellationDoesNotLoopAndSavedTimeoutsKeepRetrying() {
        val p=ConnectionRecovery()
        assertNull(p.unavailableRetry(true))
        assertEquals(listOf(2000L,5000L,10000L,10000L),(0..3).map { p.unavailableRetry(false) })
        repeat(1000) { assertEquals(10000L,p.unavailableRetry(false)) }
        p.reset(); assertEquals(2000L,p.unavailableRetry(false))
    }
    @Test fun repeatedNetworkLossBacksOffAndResetsAfterSuccess() {
        val p=ConnectionRecovery()
        assertEquals(listOf(1500L,3000L,6000L,12000L,20000L,20000L),(0..5).map { p.lostDelay() })
        p.reset(); assertEquals(1500L,p.lostDelay())
    }
}
