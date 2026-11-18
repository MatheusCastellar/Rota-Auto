package com.example.opendash.dash

import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DashTransportRecoveryTest {
    private class Transport:DashTransport {
        var closed=false; @Volatile var sends=0; var broken=true
        override fun send(data:ByteArray) { if(broken) throw java.io.IOException("ENETUNREACH"); sends++ }
        override fun sendRtp(data:ByteArray)=send(data)
        override suspend fun receive():ByteArray?=null
        override fun close() { closed=true }
    }
    private fun install(session:DashSession,transport:DashTransport) {
        session.javaClass.getDeclaredField("socket").apply { isAccessible=true }.set(session,transport)
        @Suppress("UNCHECKED_CAST")
        val state=session.javaClass.getDeclaredField("_state").apply { isAccessible=true }.get(session) as MutableStateFlow<DashState>
        state.value=DashState.STREAMING
    }
    @Test fun videoAndControlFailuresAreReportedOnceAndRecoveredSocketCanSend() {
        for(control in listOf(false,true)) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            val session=DashSession(scope); val broken=Transport(); install(session,broken)
            var errors=0; var networkErrors=0
            session.onError={ errors++ }; session.onTransportError={ networkErrors++ }
            try {
                repeat(3) {
                    if(control) session.javaClass.getDeclaredMethod("send",DashTransport::class.java,ByteArray::class.java)
                        .apply { isAccessible=true }.invoke(session,broken,byteArrayOf(1))
                    else session.sendRtp(byteArrayOf(1))
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1,errors); assertEquals(1,networkErrors)
                assertTrue(broken.closed); assertEquals(DashState.ERROR,session.state.value)
                val fresh=Transport().apply { this.broken=false }; install(session,fresh)
                session.sendRtp(byteArrayOf(2))
                assertEquals(1,fresh.sends); assertEquals(DashState.STREAMING,session.state.value)
            } finally { session.disconnect(); scope.cancel() }
        }
    }
    @Test fun visibilityChangeSendsImmediatelyWithoutRestartingNativeNavigation() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=DashSession(scope); val transport=Transport().apply { broken=false }; install(session,transport)
        session.javaClass.getDeclaredField("navChromeEnabled").apply { isAccessible=true }.setBoolean(session,true)
        try {
            session.setProjectionGuidance(true,0x14,250,0x30)
            session.cleanProjection=true
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            repeat(100) { if(transport.sends<1) Thread.sleep(10) }; assertEquals(1,transport.sends)
            session.cleanProjection=true
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,transport.sends)
            session.cleanProjection=false
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            repeat(100) { if(transport.sends<2) Thread.sleep(10) }; assertEquals(2,transport.sends)
            assertFalse(transport.closed); assertEquals(DashState.STREAMING,session.state.value)
        } finally { session.disconnect(); scope.cancel() }
    }
    @Test fun delayedFailureFromOldSocketCannotInvalidateItsReplacement() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val session=DashSession(scope); val old=Transport(); install(session,old)
        var errors=0; session.onError={ errors++ }
        try {
            session.sendRtp(byteArrayOf(1)) // failure callback queued, then user reconnects
            session.disconnect()
            val fresh=Transport().apply { this.broken=false }; install(session,fresh)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,errors); assertFalse(fresh.closed)
            session.sendRtp(byteArrayOf(2)); assertEquals(1,fresh.sends)
        } finally { session.disconnect(); scope.cancel() }
    }
}
