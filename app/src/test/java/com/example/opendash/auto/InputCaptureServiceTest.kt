package com.example.opendash.auto

import android.content.Context
import android.os.Looper
import com.example.opendash.dash.DashSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class InputCaptureServiceTest {
    @Test fun captureBlocksBindingsButKeepsRawUnknownAndDuplicateEventsAndStopRestoresActions() {
        val controller=Robolectric.buildService(AutoBridgeService::class.java).create()
        val service=controller.get()
        val p=service.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        p.edit().putInt(BikeControlLayout.PROFILE,1).putBoolean("enabled",true)
            .putString(BikeControlLayout.key(1,TripperControls.Action.LEFT,false),BikeCommand.PROFILE_NEXT.name).apply()
        val dash=service.javaClass.getDeclaredField("dash").apply { isAccessible=true }.get(service) as DashSession
        fun input(code:Int) { val bytes=byteArrayOf(code.toByte()); dash.onRawPacket?.invoke(bytes,"test:2002"); dash.onButtonPacket?.invoke(bytes); dash.onButton?.invoke(code.toByte()) }
        try {
            BikeInputCapture.setBlockActions(true); BikeInputCapture.start()
            input(0x0a); input(0x0a); input(0xff)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,BikeControlLayout.active(p)); assertEquals(3,BikeInputCapture.state.value.count)
            assertTrue(BikeInputCapture.snapshot().contains("hex=FF"))
            assertTrue(BikeInputCapture.snapshot().contains("filtrado=true"))
            assertTrue(BikeInputCapture.snapshot().contains("Ação bloqueada"))
            BikeInputCapture.selectGlyph(90)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(90,dash.testManeuver); assertEquals(0,BikeInputCapture.state.value.buttons)
            input(0xff)
            assertEquals(1,BikeInputCapture.state.value.buttons)
            assertTrue(BikeInputCapture.snapshot().contains("símbolo solicitado=90"))
            BikeInputCapture.stop()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNull(dash.testManeuver)
            val stopped=BikeInputCapture.snapshot()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            input(0x0a)
            assertEquals(2,BikeControlLayout.active(p)); assertEquals(stopped,BikeInputCapture.snapshot())
            BikeInputCapture.start(); BikeInputCapture.setBlockActions(false)
            p.edit().putInt(BikeControlLayout.PROFILE,1).apply()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            input(0x0a)
            assertEquals(2,BikeControlLayout.active(p)); assertEquals(1,BikeInputCapture.state.value.count)
        } finally { BikeInputCapture.stop(); BikeInputCapture.setBlockActions(true); controller.destroy() }
    }
}
