package com.example.opendash.auto

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local recovery copy of portable settings only; no Wi-Fi credentials or diagnostic data. */
internal object SettingsRecovery {
    data class Saved(val at:Long,val reason:String,val backup:SettingsImport.Backup)
    private fun file(context:Context)=File(context.filesDir,"previous-settings.json")
    @Synchronized fun save(context:Context,reason:String) {
        val data=JSONObject().put("version",1).put("at",System.currentTimeMillis()).put("reason",reason)
            .put("settings",JSONObject(SettingsExport.create(context,false))).toString().toByteArray(Charsets.UTF_8)
        val destination=file(context)
        val pending=File(context.filesDir,"previous-settings.tmp")
        try {
            FileOutputStream(pending).use { it.write(data); it.fd.sync() }
            Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
        check(destination.readBytes().contentEquals(data)) { "Não foi possível guardar a configuração anterior." }
    }
    @Synchronized fun read(context:Context):Saved? {
        val destination=file(context)
        if(!destination.exists()) return null
        val json=JSONObject(destination.readText(Charsets.UTF_8))
        require(json.getInt("version")==1)
        return Saved(json.getLong("at"),json.getString("reason"),SettingsImport.parse(json.getJSONObject("settings").toString()))
    }
    fun restore(context:Context,saved:Saved) {
        // Apply saves the current settings first, allowing the user to undo this restoration too.
        SettingsImport.apply(context,saved.backup,recoveryReason="Voltar à configuração anterior")
    }
}
