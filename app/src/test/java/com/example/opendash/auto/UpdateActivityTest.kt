package com.example.opendash.auto

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
class UpdateActivityTest {
    @Test fun automaticChecksAreOptionalAndOnlyUpdateFilesCanBeShared() {
        val app=RuntimeEnvironment.getApplication()
        val controls=app.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        controls.edit().putInt(BikeControlLayout.PROFILE,3).putInt("video-fps",8).commit()
        val before=controls.all.toMap()
        assertTrue(GitHubUpdates.preferences(app).getBoolean(GitHubUpdates.AUTO,true))
        val c=Robolectric.buildActivity(UpdateActivity::class.java).setup()
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        try {
            views(c.get().window.decorView).filterIsInstance<Switch>().single().isChecked=false
            assertFalse(GitHubUpdates.preferences(app).getBoolean(GitHubUpdates.AUTO,true))
            assertEquals(before,controls.all)
            val provider=app.packageManager.resolveContentProvider("${app.packageName}.updates",0)!!
            assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
            // FileProvider's Android '/' canonical path check cannot run on Windows '\\'.
            // Exercise URI sharing on-device; here check the actual manifest XML scope.
            val xml=app.resources.getXml(com.example.opendash.R.xml.update_paths)
            val roots=mutableListOf<Pair<String,String>>()
            while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && xml.name!="paths")
                    roots+=xml.name to xml.getAttributeValue(null,"path")
            }
            xml.close(); assertEquals(listOf("cache-path" to "updates/"),roots)
        } finally { c.pause().stop().destroy() }
    }
}
