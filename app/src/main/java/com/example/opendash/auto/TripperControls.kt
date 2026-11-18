package com.example.opendash.auto

/** Separate map/media events documented by WayFindR-Dash-Bridge. */
class TripperControls(private val mediaMode:Boolean=false) {
    enum class Action(val label:String,val key:Int) {
        UP("Cima",19), DOWN("Baixo",20), LEFT("Esquerda",21), RIGHT("Direita",22), SELECT("Centro",66), BACK("Voltar",4), HOME("Home",84),
        MAP_LEFT("Esquerda",21), MAP_RIGHT("Direita",22), MAP_DOWN("Baixo",20), MAP_ENTER("Centro",66);
        val outside get()=name.startsWith("MAP_")
        val description get()=if(outside) "Fora · $label" else label
    }
    companion object {
        val defaults=mapOf(6 to Action.UP,7 to Action.DOWN,10 to Action.LEFT,9 to Action.RIGHT,24 to Action.SELECT,18 to Action.BACK)
        val mapActions=listOf(Action.MAP_LEFT,Action.MAP_RIGHT,Action.MAP_DOWN,Action.MAP_ENTER)
        val mediaActions=mapActions+listOf(Action.SELECT,Action.LEFT,Action.RIGHT,Action.UP,Action.DOWN)
        // WayFindR-Dash-Bridge: outside centre opens the native controls; inside centre plays.
        val bridgeInputs=mapOf(0x13 to Action.MAP_RIGHT,0x14 to Action.MAP_LEFT,0x15 to Action.MAP_DOWN,0x18 to Action.MAP_ENTER,
            0x05 to Action.SELECT,0x09 to Action.RIGHT,0x0A to Action.LEFT,0x06 to Action.UP,0x07 to Action.DOWN)
        // WayFindr fw 11.63 observations: map-view RIGHT/LEFT/DOWN differ from media mode.
        private val mapAliases=mapOf(0x13 to 0x09,0x14 to 0x0A,0x15 to 0x07)
        private val centerCodes=setOf(5,24)
    }
    private fun initialBindings()=if(mediaMode) bridgeInputs else defaults
    private val mapping=initialBindings().toMutableMap()
    private var lastCode=-1
    private var lastAt=Long.MIN_VALUE
    var learning:Action?=null
        private set
    @Synchronized fun arm(action:Action?) { learning=if(mediaMode) null else action; lastCode=-1 }
    @Synchronized fun bind(code:Int,action:Action) {
        require(code in 1..255)
        if(mediaMode) return // Fixed independent inputs: calibration must not merge map and media.
        if(mediaMode && code in centerCodes && action!=Action.SELECT) return
        mapping.entries.removeAll { it.value==action }
        mapping[code]=action
    }
    @Synchronized fun reset() { mapping.clear(); mapping.putAll(initialBindings()); learning=null; lastCode=-1 }
    @Synchronized fun restore(bindings:Map<Action,Int>) {
        if(mediaMode) { reset(); return }
        require(bindings.values.all { it in 1..255 })
        mapping.clear()
        for((action,code) in bindings) bind(code,action)
        learning=null; lastCode=-1
    }
    /** Preserve a full physical calibration, but do not migrate the old RIGHT=confirm shortcut. */
    @Synchronized fun importNavigationBindings(bindings:Map<Action,Int>):Boolean {
        return false // Old navigation aliases conflate the two physical contexts.
    }
    @Synchronized fun useDirectionalSelection() {
        if(mediaMode) return
        mapping.clear()
        mapping.putAll(mapOf(6 to Action.UP,7 to Action.DOWN,9 to Action.SELECT,10 to Action.BACK))
        learning=null; lastCode=-1
    }
    @Synchronized fun codeFor(action:Action)=mapping.entries.firstOrNull { it.value==action }?.key
    data class Event(val action:Action?,val learned:Boolean=false,val ignored:Boolean=false,val conflict:Boolean=false)
    @Synchronized fun receive(code:Int,now:Long,repeatWindowMs:Long=220):Event {
        if(code !in 1..255) return Event(null,ignored=true)
        val action=if(mediaMode) mapping[code] else mapping[code] ?: mapAliases[code]?.let { mapping[it] }
        val dedup=if(mediaMode && learning==null && action!=null) 256+action.ordinal else code
        if(dedup==lastCode && now>=lastAt && now-lastAt<repeatWindowMs) return Event(null,ignored=true)
        lastCode=dedup; lastAt=now
        learning?.let {
            if(mediaMode && code in centerCodes && it!=Action.SELECT) return Event(null,conflict=true)
            bind(code,it); learning=null
            if(mediaMode) lastCode=256+it.ordinal
            return Event(it,learned=true)
        }
        return Event(action)
    }
}
