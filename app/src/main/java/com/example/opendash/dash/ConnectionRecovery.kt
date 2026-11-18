package com.example.opendash.dash

/** Retry a known bike indefinitely with bounded backoff; a dismissed chooser stays dismissed. */
internal class ConnectionRecovery {
    private var losses=0
    private var timeouts=0
    fun reset() { losses=0; timeouts=0 }
    fun lostDelay():Long = listOf(1500L,3000L,6000L,12000L,20000L)[(losses++).coerceAtMost(4)]
    fun unavailableRetry(prefix:Boolean):Long? {
        if(prefix) return null
        val wait=listOf(2000L,5000L,10000L)[timeouts.coerceAtMost(2)]
        timeouts=(timeouts+1).coerceAtMost(2)
        return wait
    }
}
