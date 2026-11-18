package com.example.opendash.auto

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OtherAppBindingTest {
    @Test fun gesturesKeepEachChosenAppIncludingPendingSingleAndDoublePress() {
        val gestures=BikeButtonGestures()
        val a=BikeBinding(BikeCommand.OPEN_OTHER,"com.example.maps")
        val b=BikeBinding(BikeCommand.OPEN_OTHER,"com.example.music")
        val none=BikeBinding(BikeCommand.NONE)
        assertEquals(listOf(a),gestures.pressBindings(TripperControls.Action.LEFT,0,a,none))
        assertTrue(gestures.pressBindings(TripperControls.Action.RIGHT,500,a,b).isEmpty())
        assertEquals(listOf(b),gestures.pressBindings(TripperControls.Action.RIGHT,1000,a,b))
        assertTrue(gestures.pressBindings(TripperControls.Action.RIGHT,2000,a,b).isEmpty())
        assertEquals(listOf(a),gestures.pressBindings(TripperControls.Action.LEFT,2200,none,none))
        assertTrue(gestures.flushBindings(6000).isEmpty())
    }
    @Test fun exportImportKeepsDifferentAppsPerGestureAndProfileAndRemovesStaleSelections() {
        val app=RuntimeEnvironment.getApplication()
        val p=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        for(profile in 1..3) for(double in listOf(false,true)) p.edit()
            .putString(BikeControlLayout.key(profile,TripperControls.Action.LEFT,double),"OPEN_OTHER")
            .putString(BikeControlLayout.appKey(profile,TripperControls.Action.LEFT,double),"com.example.p${profile}${if(double) "double" else "tap"}").commit()
        val raw=SettingsExport.create(app,false)
        val backup=SettingsImport.parse(raw)
        p.edit().clear().putString(BikeControlLayout.appKey(1,TripperControls.Action.RIGHT,false),"com.stale.app").commit()
        SettingsImport.apply(app,backup)
        for(profile in 1..3) for(double in listOf(false,true)) assertEquals("com.example.p${profile}${if(double) "double" else "tap"}",
            BikeControlLayout.binding(p,profile,TripperControls.Action.LEFT,double).appPackage)
        assertNull(p.getString(BikeControlLayout.appKey(1,TripperControls.Action.RIGHT,false),null))
        val corrupt=JSONObject(raw)
        corrupt.getJSONObject("profiles").getJSONObject("1").getJSONObject("LEFT").put("tapApp","intent://not-a-package")
        assertTrue(runCatching { SettingsImport.parse(corrupt.toString()) }.isFailure)
    }
    @Test fun originalShortcutsAndProfilesAreNotReplacedByCustomChoice() {
        assertEquals(0x10002,BikeCommand.OPEN_MAPS.aaKey)
        assertEquals(0x10001,BikeCommand.OPEN_SPOTIFY.aaKey)
        assertNull(BikeCommand.OPEN_OTHER.aaKey)
        for(profile in 1..3) for(button in TripperControls.mediaActions) for(double in listOf(false,true))
            assertNotEquals(BikeCommand.OPEN_OTHER,BikeControlLayout.profileDefault(profile,button,double))
    }
    private fun template():AaSourceFrame=AaSourceFrame(32,32,IntArray(1024) { i ->
        val x=i%32; val y=i/32
        when { x in 10..13 && y in 5..27 -> 0xffffffff.toInt(); y in 17..20 && x in 8..25 -> 0xffffffff.toInt(); else -> 0xff2387dc.toInt() }
    })
    private fun frame(vararg positions:Pair<Int,Int>):AaSourceFrame {
        val source=template(); val pixels=IntArray(96000) { 0xff121212.toInt() }
        for((left,top) in positions) for(y in 0..31) for(x in 0..31) pixels[(top+y)*400+left+x]=source.pixels[y*32+x]
        return AaSourceFrame(400,240,pixels)
    }
    @Test fun iconRecognitionFindsOnlyAnUnambiguousInstalledIcon() {
        val matcher=CarAppIcon.fromTemplate(template())!!
        val point=matcher.find(frame(100 to 80))!!
        assertTrue(kotlin.math.abs(point.x-232)<=4); assertTrue(kotlin.math.abs(point.y-192)<=4)
        assertNull(matcher.find(frame()))
        assertNull(matcher.find(frame(100 to 80,250 to 80)))
        assertNull(CarAppIcon.fromTemplate(AaSourceFrame(32,32,IntArray(1024) { -1 })))
    }
}
