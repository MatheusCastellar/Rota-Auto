package com.example.opendash.auto

import android.content.Context
import org.json.JSONObject

internal object SettingsExport {
    fun create(context:Context,profileOnly:Boolean):String {
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        BikeControlLayout.migrateSplitInputs(prefs)
        val profile=BikeControlLayout.active(prefs)
        val profiles=JSONObject()
        for(p in if(profileOnly) listOf(profile) else (1..3).toList()) {
            val bindings=JSONObject()
            for(button in TripperControls.mediaActions) {
                val gestures=JSONObject()
                for(double in listOf(false,true)) {
                    val gesture=if(double) "double" else "tap"
                    val binding=BikeControlLayout.binding(prefs,p,button,double)
                    gestures.put(gesture,binding.command.name)
                    if(binding.command==BikeCommand.OPEN_OTHER && CarApplications.validPackage(binding.appPackage)) gestures.put(gesture+"App",binding.appPackage)
                }
                bindings.put(button.name,gestures)
            }
            profiles.put(p.toString(),bindings)
        }
        val result=JSONObject().put("format","rota-auto-settings").put("version",4)
            .put("appVersion",com.example.opendash.BuildConfig.VERSION_NAME)
            .put("scope",if(profileOnly) "profile" else "all").put("activeProfile",profile).put("profiles",profiles)
        if(profileOnly) result.put("profileName",prefs.getString(ProfileNames.key(false,profile),"") ?: "")
        if(!profileOnly) {
            // Explicit preference store; Wi-Fi credentials, diagnostics and locations are never exported.
            val options=JSONObject()
            prefs.all.toSortedMap().forEach { (key,value) ->
                if(key in SettingsImport.optionKeys) {
                    if(value is Boolean || value is Number || value is String) options.put(key,value)
                }
            }
            val calibration=JSONObject()
            // Physical codes are fixed by context. Only their assigned actions are portable.
            result.put("options",options).put("calibration",calibration)
        }
        return result.toString(2)
    }
}
