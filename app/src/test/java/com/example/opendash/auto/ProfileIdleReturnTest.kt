package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class ProfileIdleReturnTest {
    @Test fun disabledMenuAndFirstProfileNeverArmTimer() {
        val idle=ProfileIdleReturn()
        for((enabled,menu,profile) in listOf(Triple(false,false,2),Triple(true,true,3),Triple(true,false,1))) {
            idle.reset(1000,enabled,menu,profile); assertNull(idle.deadline); assertFalse(idle.expired(999999))
        }
    }
    @Test fun everyInputRestartsFullFifteenSecondsAndDisablingCancelsIt() {
        val idle=ProfileIdleReturn(); idle.reset(1000,true,false,3)
        assertFalse(idle.expired(15999))
        idle.reset(15999,true,false,3)
        assertFalse(idle.expired(16000)); assertFalse(idle.expired(30998)); assertTrue(idle.expired(30999))
        idle.reset(31000,false,false,3); assertFalse(idle.expired(999999))
    }
}
