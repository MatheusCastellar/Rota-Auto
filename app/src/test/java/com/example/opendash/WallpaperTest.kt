package com.example.opendash

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperTest {
    @Test fun destinationAlwaysWinsOverWallpaperIncludingWhileWaitingForGps() {
        assertFalse(WallpaperLayout.shouldShow(true,true,true))
        assertTrue(WallpaperLayout.shouldShow(false,true,true))
        assertFalse(WallpaperLayout.shouldShow(false,false,true))
        assertFalse(WallpaperLayout.shouldShow(false,true,false))
    }
    @Test fun fitPreservesWholePortraitAndFillCropsCentrally() {
        val fit=WallpaperLayout.rectangle(300,600,false)
        assertEquals(150f,fit.width(),.01f); assertEquals(300f,fit.height(),.01f)
        assertEquals(263f,fit.centerX(),.01f)
        val fill=WallpaperLayout.rectangle(300,600,true)
        assertEquals(526f,fill.width(),.01f); assertTrue(fill.top<0); assertTrue(fill.bottom>300)
        assertEquals(150f,fill.centerY(),.01f)
    }
    @Test fun importIsBoundedPersistsAndInvalidReplacementPreservesOldPhoto() {
        val app=RuntimeEnvironment.getApplication()
        val original=File(app.cacheDir,"original.png")
        val large=Bitmap.createBitmap(2000,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        original.outputStream().use { assertTrue(large.compress(Bitmap.CompressFormat.PNG,100,it)) }; large.recycle()
        val store=WallpaperStore(app)
        store.importPhoto(Uri.fromFile(original))
        val loaded=WallpaperStore(app).load()!!
        assertEquals(1600,loaded.width); assertEquals(80,loaded.height); loaded.recycle()
        val bad=File(app.cacheDir,"invalid.png").apply { writeText("not an image") }
        assertTrue(runCatching { store.importPhoto(Uri.fromFile(bad)) }.isFailure)
        assertNotNull(store.load()?.also { it.recycle() })
        store.remove()
        assertFalse(store.exists); assertNull(store.load())
        assertTrue(original.exists())
        assertFalse(app.getSharedPreferences("rota",0).getBoolean("wallpaper_enabled",true))
    }
}
