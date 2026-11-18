package com.example.opendash.auto

enum class BikeCommand(val label:String,val aaKey:Int?=null,val rotaryDelta:Int=0,val panelLabel:String=label,val tapX:Int?=null) {
    NONE("Sem ação"), VOICE("Voz"), PLAY_PAUSE("Play / pause"), PREVIOUS("Faixa anterior"), NEXT("Próxima faixa"), PROFILE_NEXT("Trocar perfil",panelLabel="Trocar perfil"),
    VOLUME_UP("Volume +"), VOLUME_DOWN("Volume −"), BACK("Voltar no Android Auto",aaKey=4,panelLabel="Voltar"),
    AA_UP("Android Auto · Cima",aaKey=19,panelLabel="Cima"),
    AA_DOWN("Android Auto · Baixo",aaKey=20,panelLabel="Baixo"),
    AA_LEFT("Android Auto · Esquerda",aaKey=21,panelLabel="Esquerda"),
    AA_RIGHT("Android Auto · Direita",aaKey=22,panelLabel="Direita"),
    AA_SELECT("Android Auto · Confirmar",aaKey=23,panelLabel="Confirmar"),
    AA_ENTER("Android Auto · Enter",aaKey=66,panelLabel="Enter"),
    AA_HOME("Android Auto · Início",aaKey=3,panelLabel="Início"),
    AA_APPS("Android Auto · Aplicativos",aaKey=187,panelLabel="Aplicativos"),
    OPEN_MAPS("Abrir Google Maps",aaKey=0x10002,panelLabel="Google Maps"),
    OPEN_SPOTIFY("Abrir Spotify",aaKey=0x10001,panelLabel="Spotify"),
    AA_DASHBOARD_LEFT("Painel / lista · ícone à esquerda",panelLabel="Painel / lista",tapX=40),
    AA_DASHBOARD_RIGHT("Painel / lista · ícone à direita",panelLabel="Painel / lista",tapX=760),
    AA_PREVIOUS_ITEM("Android Auto · Item anterior",rotaryDelta=-1,panelLabel="Item anterior"),
    AA_NEXT_ITEM("Android Auto · Próximo item",rotaryDelta=1,panelLabel="Próximo item"),
    SHOW_BAR("Mostrar / ocultar barra",panelLabel="Mostrar barra"),
    VOICE_AND_BAR("Voz + mostrar barra",panelLabel="Voz + barra"),
    OPEN_WHATSAPP("Abrir WhatsApp",panelLabel="WhatsApp"),
    SWITCH_MAPS_SPOTIFY("Alternar Maps/Spotify",panelLabel="Maps / Spotify"),
    TOGGLE_GPS_BUBBLE("Mostrar/ocultar bolinha do GPS",panelLabel="Bolinha GPS"),
    OPEN_OTHER("Abrir aplicativo: Outro",panelLabel="Abrir aplicativo");

    val requestsVoice get()=this==VOICE || this==VOICE_AND_BAR
    val requestsBar get()=this==SHOW_BAR || this==VOICE_AND_BAR

    /** Null means a phone/media command; false means AA refused it, never fall back to media. */
    fun sendToAndroidAuto(key:(Int)->Boolean,rotate:(Int)->Boolean,tap:(Int,Int)->Boolean={_,_->false}):Boolean? =
        aaKey?.let(key) ?: tapX?.let { tap(it,440) } ?: if(rotaryDelta!=0) rotate(rotaryDelta) else null
}

object BikeControlLayout {
    const val MODE="intercom-controls"
    const val PROFILE="control-profile"
    fun legacyButton(button:TripperControls.Action)=when(button) {
        TripperControls.Action.MAP_LEFT -> TripperControls.Action.LEFT
        TripperControls.Action.MAP_RIGHT -> TripperControls.Action.RIGHT
        TripperControls.Action.MAP_DOWN -> TripperControls.Action.DOWN
        else -> button
    }
    fun migrateSplitInputs(prefs:android.content.SharedPreferences) {
        if(prefs.getBoolean("split-inputs-v1",false)) return
        val edit=prefs.edit()
        for(profile in 1..3) for(button in TripperControls.mapActions) for(double in listOf(false,true)) {
            val target=key(profile,button,double)
            if(!prefs.contains(target)) {
                val old=legacyButton(button)
                val command=if(button==TripperControls.Action.MAP_ENTER) BikeCommand.NONE else
                    if(prefs.contains(key(profile,old,double))) resolve(profile==2,old,double,prefs.getString(key(profile,old,double),null))
                    else profileDefault(profile,button,double)
                edit.putString(target,command.name)
            }
        }
        edit.putBoolean("split-inputs-v1",true).apply()
    }
    fun active(prefs:android.content.SharedPreferences)=prefs.getInt(PROFILE,if(prefs.getBoolean(MODE,true)) 2 else 1).coerceIn(1,3)
    // Keep the original two namespaces so existing bindings survive the upgrade.
    fun key(profile:Int,button:TripperControls.Action,double:Boolean)=
        if(profile==3) "button.profile3.${button.name}.${if(double) "double" else "tap"}" else key(profile==2,button,double)
    fun appKey(profile:Int,button:TripperControls.Action,double:Boolean)=key(profile,button,double)+".app"
    fun binding(prefs:android.content.SharedPreferences,profile:Int,button:TripperControls.Action,double:Boolean)=BikeBinding(
        resolve(profile,button,double,prefs.getString(key(profile,button,double),null)),prefs.getString(appKey(profile,button,double),null))
    // Three independent factory profiles from Matheus's device, captured 2026-11-14.
    fun resolve(profile:Int,button:TripperControls.Action,double:Boolean,saved:String?):BikeCommand =
        BikeCommand.entries.firstOrNull { it.name==saved } ?: profileDefault(profile,button,double)
    fun profileDefault(profile:Int,button:TripperControls.Action,double:Boolean=false):BikeCommand {
        if(double) return BikeCommand.NONE
        return when(button) {
            TripperControls.Action.UP -> if(profile==3) BikeCommand.AA_DASHBOARD_RIGHT else BikeCommand.VOICE_AND_BAR
            TripperControls.Action.DOWN -> BikeCommand.PROFILE_NEXT
            TripperControls.Action.LEFT -> when(profile) { 2 -> BikeCommand.PREVIOUS; 3 -> BikeCommand.OPEN_MAPS; else -> BikeCommand.VOLUME_DOWN }
            TripperControls.Action.RIGHT -> when(profile) { 2 -> BikeCommand.NEXT; 3 -> BikeCommand.OPEN_SPOTIFY; else -> BikeCommand.VOLUME_UP }
            TripperControls.Action.SELECT -> if(profile==1) BikeCommand.PLAY_PAUSE else BikeCommand.NONE
            else -> BikeCommand.NONE
        }
    }
    fun key(intercom:Boolean,button:TripperControls.Action,double:Boolean)=
        "button.${if(intercom) "intercom" else "normal"}.${button.name}.${if(double) "double" else "tap"}"
    fun default(intercom:Boolean,button:TripperControls.Action,double:Boolean=false):BikeCommand {
        if(button==TripperControls.Action.MAP_ENTER) return BikeCommand.NONE
        if(button.outside) return default(intercom,legacyButton(button),double)
        if(double) return if(intercom && button==TripperControls.Action.DOWN) BikeCommand.VOLUME_DOWN else BikeCommand.NONE
        if(intercom) return when(button) {
            TripperControls.Action.UP -> BikeCommand.VOICE
            TripperControls.Action.DOWN -> BikeCommand.VOLUME_UP
            else -> BikeCommand.NONE
        }
        return when(button) {
            TripperControls.Action.UP -> BikeCommand.VOLUME_UP
            TripperControls.Action.DOWN -> BikeCommand.VOLUME_DOWN
            TripperControls.Action.LEFT -> BikeCommand.PREVIOUS
            TripperControls.Action.RIGHT -> BikeCommand.NEXT
            TripperControls.Action.SELECT -> BikeCommand.PLAY_PAUSE
            TripperControls.Action.HOME -> BikeCommand.VOICE
            TripperControls.Action.BACK -> BikeCommand.BACK
            else -> BikeCommand.NONE
        }
    }
    fun resolve(intercom:Boolean,button:TripperControls.Action,double:Boolean,saved:String?):BikeCommand =
        BikeCommand.entries.firstOrNull { it.name==saved } ?: default(intercom,button,double)
}

/** No physical hold/release is assumed: the panel supplies press events only. Main-thread owner. */
class BikeButtonGestures {
    // WayFindr observed missing rapid second DOWN events in panel logs; allow a deliberate re-press.
    companion object { const val WINDOW_MS=3_000L }
    private data class Pending(val button:TripperControls.Action,val at:Long,val single:BikeBinding,val double:BikeBinding)
    private var pending:Pending?=null
    val deadline:Long? get()=pending?.let { it.at+WINDOW_MS }
    fun cancel() { pending=null }
    fun flush(now:Long):List<BikeCommand> = flushBindings(now).map { it.command }
    fun flushBindings(now:Long):List<BikeBinding> {
        val p=pending ?: return emptyList()
        if(now<p.at+WINDOW_MS) return emptyList()
        pending=null
        return listOf(p.single).filter { it.command!=BikeCommand.NONE }
    }
    fun press(button:TripperControls.Action,now:Long,single:BikeCommand,double:BikeCommand):List<BikeCommand> =
        pressBindings(button,now,BikeBinding(single),BikeBinding(double)).map { it.command }
    fun pressBindings(button:TripperControls.Action,now:Long,single:BikeBinding,double:BikeBinding):List<BikeBinding> {
        val commands=flushBindings(now).toMutableList()
        val previous=pending
        if(previous!=null) {
            if(previous.button==button) {
                pending=null
                if(previous.double.command!=BikeCommand.NONE) commands+=previous.double
                return commands
            }
            pending=null
            if(previous.single.command!=BikeCommand.NONE) commands+=previous.single
        }
        if(double.command==BikeCommand.NONE) {
            if(single.command!=BikeCommand.NONE) commands+=single
        } else pending=Pending(button,now,single,double)
        return commands
    }
}
