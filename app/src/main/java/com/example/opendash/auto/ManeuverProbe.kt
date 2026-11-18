package com.example.opendash.auto

/** Labels from the TripperDashPlusPlus empirical catalog; not a firmware guarantee. */
internal object ManeuverProbe {
    fun category(code:Int):String = when(code) {
        in 0x5a..0xff -> "Invisível no catálogo"
        0x2b,0x2c,0x41,0x43,0x44,0x45 -> "Bolinha vazia"
        0x09,0x3b -> "Seguir em frente"
        0x14 -> "Virar à esquerda"
        0x15 -> "Virar à direita"
        0x1c -> "Recalculando"
        0x3c -> "Destino"
        else -> "Símbolo visível"
    }
    fun label(code:Int)="%03d · 0x%02X · %s".format(java.util.Locale.ROOT,code,code,category(code))
}
