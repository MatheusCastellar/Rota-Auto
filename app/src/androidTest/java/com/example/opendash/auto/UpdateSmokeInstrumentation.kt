package com.example.opendash.auto

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

/** Invoked explicitly over ADB. Only a disposable updater cache file is written. */
class UpdateSmokeInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        val file=File(targetContext.cacheDir,"updates/provider-smoke.apk")
        try {
            file.parentFile!!.mkdirs(); file.writeText("provider-smoke")
            val uri=FileProvider.getUriForFile(targetContext,"${targetContext.packageName}.updates",file)
            check(uri.scheme=="content")
            check(targetContext.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }=="provider-smoke")
            check(runCatching { FileProvider.getUriForFile(targetContext,"${targetContext.packageName}.updates",File(targetContext.filesDir,"private.xml")) }.isFailure)
            result.putString("result","PASS: update cache URI is readable; private files excluded")
            finish(Activity.RESULT_OK,result)
        } catch(e:Exception) {
            result.putString("result","FAIL: ${e.javaClass.simpleName}: ${e.message}"); finish(Activity.RESULT_CANCELED,result)
        } finally { file.delete() }
    }
}
