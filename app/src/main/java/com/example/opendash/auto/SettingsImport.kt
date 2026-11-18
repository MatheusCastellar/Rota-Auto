package com.example.opendash.auto

import android.content.Context
import org.json.JSONObject

internal object SettingsImport {
    const val MAX_BYTES=131072
    private val booleans=setOf(ProfileIdleReturn.KEY,MenuControls.ENABLED,ProfilePopup.ENABLED,VideoProfile.MANUAL,PhoneAudio.INPUT,PhoneAudio.OUTPUT,"intercom-mic","enabled","clean-projection","hide-aa-bar","panel-blur",TemporaryBar.TOGGLE,PanelWidget.ENABLED,PanelWidget.FRONT)
    private val ranges=mapOf(MenuControls.SECONDS to 3..15,MenuControls.ACTIVE to 1..3,ProfilePopup.DURATION to 1..20,PanelWidget.HEIGHT to 75..200,TemporaryBar.SECONDS to 1..30,VideoProfile.BITRATE to 100..2000,PanelWidget.SIZE to 25..200,
        PanelWidget.X to -526..526,PanelWidget.Y to -300..300,VoiceGain.PREFERENCE to 100..250, PanelLayout.DISTORTION to 0..100,
        PanelLayout.POSITION_X to -526..526,PanelLayout.POSITION_Y to -300..300,
        PanelLayout.SIZE_X to 50..150,PanelLayout.SIZE_Y to 50..150,
        PanelLogo.SIZE to 25..200,PanelLogo.POSITION_X to -526..526,PanelLogo.POSITION_Y to -300..300)
    private val enums=mapOf(MapDayNight.KEY to MapDayNight.modes,PanelWidget.LAYOUT to WidgetLayout.entries.map { it.name },UiLanguage.KEY to UiLanguage.codes,PanelWidget.BACKGROUND to OverlayBackground.entries.map { it.name },PanelLogo.BACKGROUND to OverlayBackground.entries.map { it.name },PanelLayout.PREFERENCE to PanelLayout.entries.map { it.name },
        PanelLogo.PREFERENCE to PanelLogo.entries.map { it.name },CurveStyle.PREFERENCE to CurveStyle.entries.map { it.name },
        BlurArea.PREFERENCE to BlurArea.entries.map { it.name },MenuControls.OPENER to MenuDirection.entries.map { it.name },MenuControls.CYCLE to MenuDirection.entries.map { it.name })+
        MenuControls.bindingKeys.associateWith { MenuControls.commands.map { it.name } }
    val optionKeys=booleans+ranges.keys+enums.keys+MenuControls.appKeys+ProfileNames.keys+"video-fps"
    data class Backup(val profileOnly:Boolean,val active:Int,val profiles:Map<Int,Map<String,String>>,
        val options:Map<String,Any>,val calibration:Map<String,Int>,val profileName:String?=null)
    private fun integer(value:Any):Int {
        require(value is Int || value is Long) { "Número inválido no arquivo." }
        val n=(value as Number).toLong(); require(n in Int.MIN_VALUE..Int.MAX_VALUE) { "Número fora do limite." }; return n.toInt()
    }
    fun parse(text:String):Backup {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Arquivo grande demais." }
        val root=JSONObject(text)
        val version=integer(root.get("version"))
        require(root.getString("format")=="rota-auto-settings" && version in 1..4) { "Formato de backup não compatível." }
        val scope=root.getString("scope"); require(scope in listOf("profile","all")) { "Tipo de backup inválido." }
        val active=integer(root.get("activeProfile")); require(active in 1..3) { "Perfil inválido." }
        val rawProfiles=root.getJSONObject("profiles")
        val ids=rawProfiles.keys().asSequence().toList()
        require(ids.isNotEmpty() && ids.all { it in listOf("1","2","3") }) { "Perfis inválidos." }
        require(if(scope=="all") ids.toSet()==setOf("1","2","3") else ids==listOf(active.toString())) { "Backup de perfis incompleto." }
        val profiles=ids.associate { id ->
            val source=rawProfiles.getJSONObject(id)
            val bindings=mutableMapOf<String,String>()
            for(button in TripperControls.mediaActions) {
                val legacy=version==1 && button.outside
                val gestures=if(legacy && button==TripperControls.Action.MAP_ENTER) JSONObject().put("tap","NONE").put("double","NONE") else
                    source.getJSONObject(if(legacy) BikeControlLayout.legacyButton(button).name else button.name)
                for(double in listOf(false,true)) {
                    val gesture=if(double) "double" else "tap"
                    val command=gestures.getString(gesture)
                    require(BikeCommand.entries.any { it.name==command }) { "Comando desconhecido: $command" }
                    bindings["${button.name}.$gesture"]=command
                    if(command==BikeCommand.OPEN_OTHER.name && gestures.has(gesture+"App")) {
                        val packageName=gestures.getString(gesture+"App")
                        require(CarApplications.validPackage(packageName)) { "Aplicativo inválido no arquivo." }
                        bindings["${button.name}.${gesture}App"]=packageName
                    }
                }
            }
            id.toInt() to bindings.toMap()
        }
        val options=mutableMapOf<String,Any>()
        val calibration=mutableMapOf<String,Int>()
        if(scope=="all") {
            val raw=root.getJSONObject("options")
            for(key in raw.keys()) {
                if(key !in optionKeys) continue
                val value=raw.get(key)
                options[key]=when {
                    key in ProfileNames.keys -> { require(value is String && value.length<=200) { "Nome inválido." }; ProfileNames.clean(value) }
                    key==MenuControls.ACTIVE && integer(value)==4 -> 1 // older four-profile backups
                    key in MenuControls.appKeys -> { require(value is String && CarApplications.validPackage(value)) { "Aplicativo inválido no arquivo." }; value }
                    key in booleans -> { require(value is Boolean) { "Opção inválida: $key" }; value }
                    key in enums -> { require(value is String && value in enums.getValue(key)) { "Opção inválida: $key" }; value }
                    else -> { val n=integer(value); require(if(key=="video-fps") n in listOf(4,8,12) else n in ranges.getValue(key)) { "Valor fora do limite: $key" }; n }
                }
            }
            val rawCalibration=root.getJSONObject("calibration")
            for(key in rawCalibration.keys()) {
                require(TripperControls.Action.entries.any { it.name==key }) { "Calibração inválida." }
                val code=integer(rawCalibration.get(key)); require(code in 1..255) { "Código de botão inválido." }
                require(code !in listOf(5,24) || key=="SELECT") { "O código central não pode ser usado para outro botão." }
                require(code !in calibration.values) { "Dois botões têm o mesmo código." }
                calibration[key]=code
            }
        }
        val name=if(root.has("profileName")) root.get("profileName").let {
            require(it is String && it.length<=200) { "Nome inválido." }; ProfileNames.clean(it)
        } else null
        return Backup(scope=="profile",active,profiles,options.toMap(),calibration.toMap(),name)
    }
    fun apply(context:Context,backup:Backup,targetProfile:Int=backup.active,recoveryReason:String="Importar configurações") {
        require(targetProfile in 1..3)
        SettingsRecovery.save(context,recoveryReason)
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        BikeControlLayout.migrateSplitInputs(prefs)
        val edit=prefs.edit()
        if(backup.profileOnly) backup.profileName?.let { edit.putString(ProfileNames.key(false,targetProfile),it) }
        if(!backup.profileOnly) {
            optionKeys.forEach(edit::remove)
            edit.remove(BikeControlLayout.MODE).putBoolean(AaSensors.PARKED,false)
            for((key,value) in backup.options) when(value) {
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
                is String -> edit.putString(key,value)
            }
        }
        for((source,bindings) in backup.profiles) {
            val target=if(backup.profileOnly) targetProfile else source
            for(button in TripperControls.mediaActions) for(double in listOf(false,true)) {
                edit.putString(BikeControlLayout.key(target,button,double),bindings.getValue("${button.name}.${if(double) "double" else "tap"}"))
                val app=bindings["${button.name}.${if(double) "double" else "tap"}App"]
                if(app==null) edit.remove(BikeControlLayout.appKey(target,button,double))
                else edit.putString(BikeControlLayout.appKey(target,button,double),app)
            }
        }
        edit.putInt(BikeControlLayout.PROFILE,if(backup.profileOnly) targetProfile else backup.active).putBoolean("split-inputs-v1",true)
        if(!backup.profileOnly) {
            check(context.getSharedPreferences("auto-media-buttons",Context.MODE_PRIVATE).edit().clear().commit()) { "Não foi possível salvar os controles." }
        }
        check(edit.commit()) { "Não foi possível salvar as configurações." }
    }
}
