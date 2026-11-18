package com.example.opendash.auto

import android.Manifest
import android.content.Context
import android.media.*
import com.andrerinas.openheadunit.aap.MicSessionController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AaMicrophoneRoutingTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun permissions() { Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.BLUETOOTH_CONNECT) }
    private fun device(type:Int)=AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    private fun capture(mic:AaMicrophone,check:()->Unit) {
        val current=AtomicBoolean(true)
        val data=CountDownLatch(1)
        try {
            assertEquals(0,mic.start(MicSessionController.CaptureCallbacks(
                { _,n,_ -> if(n>0) { data.countDown(); current.set(false) } },
                { fail("Capture failed") },{ current.get() })))
            assertTrue("Recorder never produced data",data.await(2,TimeUnit.SECONDS))
            check()
        } finally { current.set(false); mic.stop() }
    }
    @Test fun scoSelectionCapturesWithoutEnteringCallModeAndReleasesOwnRequest() {
        permissions()
        val audio=app.getSystemService(AudioManager::class.java)
        val shadow=Shadows.shadowOf(audio)
        val sco=device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadow.setAvailableCommunicationDevices(listOf(sco))
        shadow.setInputDevices(listOf(sco))
        val mic=AaMicrophone(app,{ true },{})
        capture(mic) {
            assertEquals(AudioManager.MODE_NORMAL,audio.mode)
            assertEquals(sco,audio.communicationDevice)
            assertNull(shadow.lastAudioFocusRequest)
        }
        assertNull(audio.communicationDevice)
        assertEquals(AudioManager.MODE_NORMAL,audio.mode)
    }
    @Test fun rejectedHeadsetRouteFallsBackWithoutChangingPhoneMode() {
        permissions()
        val audio=app.getSystemService(AudioManager::class.java)
        val shadow=Shadows.shadowOf(audio)
        shadow.setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC)))
        shadow.lockCommunicationDevice(true)
        capture(AaMicrophone(app,{ true },{})) { assertEquals(AudioManager.MODE_NORMAL,audio.mode) }
        assertNull(audio.communicationDevice)
    }
    @Test fun activeCallIsNotOverridden() {
        permissions()
        val audio=app.getSystemService(AudioManager::class.java)
        audio.mode=AudioManager.MODE_IN_CALL
        val mic=AaMicrophone(app,{ true },{})
        try {
            assertEquals(-1,mic.start(MicSessionController.CaptureCallbacks({ _,_,_ -> fail() },{ fail() },{ true })))
            assertEquals(AudioManager.MODE_IN_CALL,audio.mode)
        } finally { mic.stop() }
        assertEquals(AudioManager.MODE_IN_CALL,audio.mode)
    }
    @Test fun phoneMicPreferenceDoesNotAcquireBluetoothRoute() {
        permissions()
        app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).edit().putBoolean("intercom-mic",false).commit()
        val audio=app.getSystemService(AudioManager::class.java)
        val shadow=Shadows.shadowOf(audio)
        shadow.setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC)))
        capture(AaMicrophone(app,{ true },{})) {
            assertNull(audio.communicationDevice)
            assertEquals(AudioManager.MODE_NORMAL,audio.mode)
        }
    }
    @Test @Config(sdk=[29]) fun android10BuiltInMicKeepsNormalMode() {
        permissions()
        val audio=app.getSystemService(AudioManager::class.java)
        Shadows.shadowOf(audio).setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC)))
        capture(AaMicrophone(app,{ true },{})) { assertEquals(AudioManager.MODE_NORMAL,audio.mode) }
    }
}
