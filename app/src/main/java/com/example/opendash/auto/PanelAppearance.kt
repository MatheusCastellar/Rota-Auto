package com.example.opendash.auto

import kotlin.math.roundToInt

enum class CurveStyle(val label:String) {
    LATERAL("Lateral"), ARC("Arco vertical"), BALANCED("Equilibrada");
    companion object {
        const val PREFERENCE="panel-curve-style"
        fun resolve(value:String?)=entries.firstOrNull { it.name==value } ?: LATERAL
    }
}

enum class BlurArea(val label:String) {
    OFF("Desligado"), SIDES("Só laterais"), SIDES_BOTTOM("Laterais e embaixo"), ALL("Todo o fundo");
    companion object {
        const val PREFERENCE="panel-blur-area"
        fun resolve(value:String?,legacyEnabled:Boolean=true)=entries.firstOrNull { it.name==value }
            ?: if(legacyEnabled) SIDES else OFF
    }
}

object PanelAlignment {
    /** Offset from the unshifted image rectangle to the chosen edge/center of the video canvas. */
    fun offset(start:Float,length:Float,canvas:Float,alignment:Int):Int =
        ((canvas-length)*alignment.coerceIn(0,2)/2-start).roundToInt()
}
