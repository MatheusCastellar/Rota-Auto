package com.example.opendash.auto

import android.content.Context
import android.content.SharedPreferences

internal enum class MenuDirection(val label:String,val symbol:String,val action:TripperControls.Action) {
    UP("Cima","↑",TripperControls.Action.UP), RIGHT("Direita","→",TripperControls.Action.RIGHT),
    DOWN("Baixo","↓",TripperControls.Action.DOWN), LEFT("Esquerda","←",TripperControls.Action.LEFT),
    CENTER("Centro","○",TripperControls.Action.SELECT)
}

internal object MenuControls {
    const val ENABLED="menu-controls-enabled"
    const val ACTIVE="menu-controls-profile"
    const val OPENER="menu-controls-opener"
    const val CYCLE="menu-controls-cycle"
    const val SECONDS="menu-controls-seconds"
    const val IDLE_MS=7_000L
    fun duration(p:SharedPreferences)=p.getInt(SECONDS,7).coerceIn(3,15)*1000L
    val commands=BikeCommand.entries.filter { it!=BikeCommand.PROFILE_NEXT }
    fun active(p:SharedPreferences)=p.getInt(ACTIVE,1).takeIf { it in 1..3 } ?: 1
    fun opener(p:SharedPreferences)=direction(p.getString(OPENER,null)) ?: MenuDirection.DOWN
    fun cycle(p:SharedPreferences)=direction(p.getString(CYCLE,null)) ?: MenuDirection.DOWN
    private fun direction(value:String?)=MenuDirection.entries.firstOrNull { it.name==value }
    fun direction(action:TripperControls.Action):MenuDirection?=when(action) {
        TripperControls.Action.MAP_LEFT -> MenuDirection.LEFT
        TripperControls.Action.MAP_RIGHT -> MenuDirection.RIGHT
        TripperControls.Action.MAP_DOWN -> MenuDirection.DOWN
        TripperControls.Action.MAP_ENTER -> MenuDirection.CENTER
        else -> MenuDirection.entries.firstOrNull { it.action==action }
    }
    // Profile 0 is the single shared set used while our menu is closed.
    fun key(profile:Int,d:MenuDirection)="menu-bind.$profile.${d.name}"
    fun appKey(profile:Int,d:MenuDirection)=key(profile,d)+".app"
    val bindingKeys=(0..3).flatMap { p -> MenuDirection.entries.map { key(p,it) } }.toSet()
    val appKeys=(0..3).flatMap { p -> MenuDirection.entries.map { appKey(p,it) } }.toSet()
    fun default(profile:Int,d:MenuDirection):BikeCommand {
        if(profile==0) return when(d) {
            MenuDirection.UP -> BikeCommand.VOICE_AND_BAR; MenuDirection.LEFT -> BikeCommand.VOLUME_DOWN
            MenuDirection.CENTER -> BikeCommand.PLAY_PAUSE; MenuDirection.RIGHT -> BikeCommand.VOLUME_UP
            MenuDirection.DOWN -> BikeCommand.VOICE_AND_BAR
        }
        if(d==MenuDirection.DOWN) return BikeCommand.NONE
        val row=when(profile) {
            1 -> listOf(BikeCommand.VOICE_AND_BAR,BikeCommand.VOLUME_UP,BikeCommand.NONE,BikeCommand.VOLUME_DOWN,BikeCommand.PLAY_PAUSE)
            2 -> listOf(BikeCommand.VOICE_AND_BAR,BikeCommand.NEXT,BikeCommand.NONE,BikeCommand.PREVIOUS,BikeCommand.PLAY_PAUSE)
            else -> listOf(BikeCommand.AA_DASHBOARD_RIGHT,BikeCommand.OPEN_MAPS,BikeCommand.NONE,BikeCommand.OPEN_SPOTIFY,BikeCommand.NONE)
        }
        return row[d.ordinal]
    }
    fun binding(p:SharedPreferences,profile:Int,d:MenuDirection)=BikeBinding(
        commands.firstOrNull { it.name==p.getString(key(profile,d),null) } ?: default(profile,d),p.getString(appKey(profile,d),null))
    fun popup(context:Context,profile:Int=active(context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE))):ProfilePopupState {
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        return ProfilePopupState(ProfileNames.label(context,true,profile),MenuDirection.entries.map { d ->
            val b=if(d==cycle(prefs)) BikeBinding(BikeCommand.PROFILE_NEXT) else binding(prefs,profile,d)
            ProfileSlot(CommandNames.short(b.command,UiLanguage.selected(context),
                if(b.command==BikeCommand.OPEN_OTHER) CarApplications.label(context,b.appPackage) ?: b.appPackage?.substringAfterLast('.') else null),null,b.command==BikeCommand.NONE)
        },duration(prefs))
    }
}

/** One press only. Native panel map/media states are never inferred from buttons. */
internal class MenuSession(var profile:Int=1,var idleMs:Long=MenuControls.IDLE_MS) {
    enum class Kind { OPEN, PROFILE, ACTION }
    data class Result(val kind:Kind,val bindingProfile:Int)
    var deadline:Long?=null; private set
    fun close() { deadline=null; profile=1 }
    fun setDuration(ms:Long,now:Long) {
        val next=ms.coerceIn(3000L,15000L)
        deadline=deadline?.let { it-idleMs+next }
        idleMs=next; expire(now)
    }
    fun expire(now:Long):Boolean {
        if(deadline?.let { now>=it }==true) { close(); return true }
        return false
    }
    fun touch(now:Long) { if(deadline!=null) deadline=now+idleMs }
    fun press(d:MenuDirection,now:Long,opener:MenuDirection,cycle:MenuDirection):Result {
        expire(now)
        if(deadline==null) {
            if(d!=opener) return Result(Kind.ACTION,0)
            profile=1; deadline=now+idleMs; return Result(Kind.OPEN,profile)
        }
        touch(now)
        if(d==cycle) { profile=profile%3+1; return Result(Kind.PROFILE,profile) }
        return Result(Kind.ACTION,profile)
    }
}
