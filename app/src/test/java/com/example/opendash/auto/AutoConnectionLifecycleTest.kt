package com.example.opendash.auto

import android.content.Intent
import android.os.PowerManager
import android.net.*
import android.net.wifi.WifiInfo
import android.os.Looper
import com.example.opendash.ConnectionDiagnostics
import com.example.opendash.dash.DashConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AutoConnectionLifecycleTest {
    @Test fun unreachableVideoRefreshesBikeNetworkWithoutStoppingProjectionAndIgnoresOldCallbacks() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get(); val cm=Shadows.shadowOf(service.getSystemService(ConnectivityManager::class.java))
        DashConfig.get(service).ssid="RE_RECOVER"
        fun field(name:String)=AutoBridgeService::class.java.getDeclaredField(name).apply { isAccessible=true }
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE),0,1)
            val oldCallback=cm.networkCallbacks.single(); val oldNetwork=ShadowNetwork.newInstance(501)
            oldCallback.onAvailable(oldNetwork); Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dash=field("dash").get(service) as com.example.opendash.dash.DashSession
            val transport=object:com.example.opendash.dash.DashTransport {
                var closed=false
                override fun send(data:ByteArray) {}
                override fun sendRtp(data:ByteArray) { throw java.io.IOException("sendto failed: ENETUNREACH") }
                override suspend fun receive():ByteArray?=null
                override fun close() { closed=true }
            }
            fun dashField(name:String)=dash.javaClass.getDeclaredField(name).apply { isAccessible=true }
            dashField("socket").set(dash,transport); dashField("selectedNetwork").set(dash,oldNetwork)
            @Suppress("UNCHECKED_CAST")
            val state=dashField("_state").get(dash) as kotlinx.coroutines.flow.MutableStateFlow<com.example.opendash.dash.DashState>
            state.value=com.example.opendash.dash.DashState.STREAMING
            field("videoReady").setBoolean(service,true)
            val generation=field("projectionGeneration").getInt(service)
            dash.sendRtp(byteArrayOf(1,2,3))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(transport.closed)
            assertTrue(field("videoReady").getBoolean(service))
            assertEquals(generation,field("projectionGeneration").getInt(service))
            assertEquals("",AutoBridgeService.state.value.error)
            assertFalse(AutoBridgeService.state.value.bike.startsWith("Wi-Fi conectado"))
            assertFalse(cm.networkCallbacks.contains(oldCallback))
            field("videoReady").setBoolean(service,false) // no real codecs/socket handshake in this test
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
            val current=cm.networkCallbacks.single(); assertNotSame(oldCallback,current)
            current.onAvailable(ShadowNetwork.newInstance(502))
            oldCallback.onLost(oldNetwork); oldCallback.onUnavailable()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AutoBridgeService.state.value.bike.startsWith("Wi-Fi conectado"))
            assertEquals("",AutoBridgeService.state.value.error)
        } finally { c.destroy() }
    }

    @Test fun savedBikeKeepsRetryingAndServiceStopCancelsAllAttempts() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        val cm=Shadows.shadowOf(service.getSystemService(ConnectivityManager::class.java))
        DashConfig.get(service).ssid="RE_RETRY"
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE),0,1)
            repeat(6) { attempt ->
                val previous=cm.networkCallbacks.single()
                previous.onUnavailable()
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(if(attempt==0) 2 else if(attempt==1) 5 else 10))
                assertNotSame(previous,cm.networkCallbacks.single())
                assertTrue(AutoBridgeService.state.value.bikeError.isEmpty())
            }
        } finally { c.destroy() }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(60))
        assertTrue(cm.networkCallbacks.isEmpty())
    }
    @Test fun newBikeCanBeChosenWhileActiveAndCancellationKeepsSavedBike() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        val cm=Shadows.shadowOf(service.getSystemService(ConnectivityManager::class.java))
        val config=DashConfig.get(service)
        config.ssid="RE_SALVA"
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE),0,1)
            val old=cm.networkCallbacks.single()
            assertTrue(AutoBridgeService.state.value.active)
            val choose=Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.NEW_BIKE)
            service.onStartCommand(choose,0,2)
            val cancelled=cm.networkCallbacks.single()
            assertNotSame(old,cancelled)
            cancelled.onUnavailable()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("RE_SALVA",config.ssid)
            service.onStartCommand(choose,0,3)
            val chosen=cm.networkCallbacks.single()
            val network=ShadowNetwork.newInstance(99)
            chosen.onAvailable(network)
            chosen.onCapabilitiesChanged(network,ShadowNetworkCapabilities.newInstance().apply {
                Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                Shadows.shadowOf(this).setTransportInfo(WifiInfo.Builder().setSsid("RE_NOVA".toByteArray()).build())
            })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("RE_NOVA",config.ssid)
            assertTrue(AutoBridgeService.state.value.bike.startsWith("Wi-Fi conectado"))
            assertEquals("Android Auto parado",AutoBridgeService.state.value.aa)
        } finally { c.destroy() }
    }
    @Test fun explicitReconnectReplacesOldRequestAndIgnoresItsLateFailure() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        val cm=Shadows.shadowOf(service.getSystemService(ConnectivityManager::class.java))
        DashConfig.get(service).ssid="RE_TESTE"
        try {
            val intent=Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE)
            service.onStartCommand(intent,0,1)
            val old=cm.networkCallbacks.single()
            old.onAvailable(ShadowNetwork.newInstance(81))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            service.onStartCommand(intent,0,2)
            val current=cm.networkCallbacks.single()
            assertNotSame(old,current)
            old.onUnavailable()
            current.onAvailable(ShadowNetwork.newInstance(82))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AutoBridgeService.state.value.bike.startsWith("Wi-Fi conectado"))
            assertEquals("RE_TESTE",DashConfig.get(service).ssid)
            assertEquals("Android Auto parado",AutoBridgeService.state.value.aa)
        } finally { c.destroy() }
    }
    @Test fun blackScreenIsExplicitAndReleasesDisplayFlagWithoutStoppingBridge() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE),0,1)
            val activity=Robolectric.buildActivity(BlackScreenActivity::class.java).setup()
            val window=activity.get().window
            val keep=android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            assertTrue(window.attributes.flags and keep!=0)
            assertEquals(0f,window.attributes.screenBrightness,0f)
            assertFalse(activity.get().isFinishing)
            activity.pause().stop()
            assertEquals(0,window.attributes.flags and keep)
            assertEquals(-1f,window.attributes.screenBrightness,0f)
            assertTrue(AutoBridgeService.state.value.active)
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
            activity.destroy()
        } finally { c.destroy() }
    }
    @Test fun screenOffAndActivityStopDoNotCancelWifiRequest() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        val cm=Shadows.shadowOf(service.getSystemService(ConnectivityManager::class.java))
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE),0,1)
            val callback=cm.networkCallbacks.single()
            val activity=Robolectric.buildActivity(AutoActivity::class.java).setup()
            activity.pause().stop().destroy()
            Shadows.shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            val display=service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
            Shadows.shadowOf(display).setState(android.view.Display.STATE_OFF)
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(cm.networkCallbacks.contains(callback))
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
            assertTrue(AutoBridgeService.state.value.screen.contains("Tela principal apagada"))
            assertTrue(AutoBridgeService.state.value.screen.contains("CPU=true"))
        } finally { c.destroy() }
    }
    @Test fun interactiveVirtualDisplayDoesNotMeanPhoneScreenIsOn() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        try {
            Shadows.shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            Shadows.shadowOf(service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0))
                .setState(android.view.Display.STATE_OFF)
            service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AutoBridgeService.state.value.screen.contains("Tela principal apagada"))
            assertTrue(AutoBridgeService.state.value.screen.contains("sistema interativo=true"))
        } finally { c.destroy() }
    }
    @Test fun bikeRequestAndConnectedWifiSurviveProjectionFailureWithoutStartingPanel() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        val cm=service.getSystemService(ConnectivityManager::class.java)
        val shadowCm=Shadows.shadowOf(cm)
        DashConfig.get(service).forgetDash()
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.BIKE),0,1)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Android Auto parado",AutoBridgeService.state.value.aa)
            assertEquals("",AutoBridgeService.state.value.codec)
            assertTrue(AutoBridgeService.state.value.active)
            val callback=shadowCm.networkCallbacks.single()
            service.stopProjection("Servidor Android Auto indisponível")
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
            assertTrue(shadowCm.networkCallbacks.contains(callback))
            val network=ShadowNetwork.newInstance(81)
            val caps=ShadowNetworkCapabilities.newInstance().apply {
                Shadows.shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                Shadows.shadowOf(this).setTransportInfo(WifiInfo.Builder().setSsid("RE_TESTE".toByteArray()).build())
            }
            callback.onAvailable(network)
            callback.onCapabilitiesChanged(network,caps)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(AutoBridgeService.state.value.bike.startsWith("Wi-Fi conectado"))
            assertTrue(AutoBridgeService.state.value.problems.contains("Servidor Android Auto indisponível"))
            assertFalse(ConnectionDiagnostics.report().contains("Abrindo comunicação com o painel"))
            assertEquals(0L,ConnectionDiagnostics.rtp.get())
            service.stopProjection("Falha de vídeo")
            assertTrue(AutoBridgeService.state.value.bike.startsWith("Wi-Fi conectado"))
            assertTrue(shadowCm.networkCallbacks.contains(callback))
        } finally { c.destroy() }
        assertTrue(shadowCm.networkCallbacks.isEmpty())
    }
    @Test fun wifiErrorAndProjectionErrorRemainVisibleSeparately() {
        val c=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=c.get()
        try {
            service.onStartCommand(Intent(service,AutoBridgeService::class.java).setAction(AutoBridgeService.NEW_BIKE),0,1)
            val callbacks=Shadows.shadowOf(service.getSystemService(ConnectivityManager::class.java)).networkCallbacks
            callbacks.single().onUnavailable()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val wifiError=AutoBridgeService.state.value.bikeError
            assertTrue(wifiError.isNotEmpty())
            service.stopProjection("Servidor não respondeu")
            assertEquals(wifiError,AutoBridgeService.state.value.bikeError)
            assertTrue(AutoBridgeService.state.value.problems.contains("Servidor não respondeu"))
            assertTrue(AutoBridgeService.state.value.problems.contains(wifiError))
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        } finally { c.destroy() }
    }
}
