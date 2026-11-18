package com.example.opendash.auto

import android.icu.text.BreakIterator
import java.util.Locale

/** Compact HUD labels only. The editor keeps its descriptive, existing labels. */
internal object CommandNames {
    const val LIMIT=10
    fun short(command:BikeCommand,language:String,appName:String?=null):String {
        val names=when(command) {
            BikeCommand.NONE -> listOf("Sem ação","No action","Sin acción","कोई नहीं")
            BikeCommand.VOICE -> listOf("Voz","Voice","Voz","आवाज़")
            BikeCommand.PLAY_PAUSE -> listOf("Toca/pausa","Play/pause","Play/pausa","चलाएँ/रोकें")
            BikeCommand.PREVIOUS -> listOf("Anterior","Prev track","Anterior","पिछला गीत")
            BikeCommand.NEXT -> listOf("Próxima","Next track","Siguiente","अगला गीत")
            BikeCommand.PROFILE_NEXT -> listOf("Perfil +","Profile +","Perfil +","प्रोफ़ाइल +")
            BikeCommand.VOLUME_UP -> listOf("Volume +","Volume +","Volumen +","वॉल्यूम +")
            BikeCommand.VOLUME_DOWN -> listOf("Volume −","Volume −","Volumen −","वॉल्यूम −")
            BikeCommand.BACK -> listOf("Voltar","Back","Volver","वापस")
            BikeCommand.AA_UP -> listOf("Cima","Up","Arriba","ऊपर")
            BikeCommand.AA_DOWN -> listOf("Baixo","Down","Abajo","नीचे")
            BikeCommand.AA_LEFT -> listOf("Esquerda","Left","Izquierda","बाएँ")
            BikeCommand.AA_RIGHT -> listOf("Direita","Right","Derecha","दाएँ")
            BikeCommand.AA_SELECT -> listOf("Confirmar","Confirm","Confirmar","पुष्टि")
            BikeCommand.AA_ENTER -> listOf("Enter","Enter","Intro","एंटर")
            BikeCommand.AA_HOME -> listOf("Início","Home","Inicio","होम")
            BikeCommand.AA_APPS -> listOf("Apps","Apps","Apps","ऐप्स")
            BikeCommand.OPEN_MAPS -> listOf("Maps","Maps","Maps","Maps")
            BikeCommand.OPEN_SPOTIFY -> listOf("Spotify","Spotify","Spotify","Spotify")
            BikeCommand.AA_DASHBOARD_LEFT -> listOf("Painel ←","Panel ←","Panel ←","पैनल ←")
            BikeCommand.AA_DASHBOARD_RIGHT -> listOf("Painel →","Panel →","Panel →","पैनल →")
            BikeCommand.AA_PREVIOUS_ITEM -> listOf("Item −","Item −","Elemento −","विकल्प −")
            BikeCommand.AA_NEXT_ITEM -> listOf("Item +","Item +","Elemento +","विकल्प +")
            BikeCommand.SHOW_BAR -> listOf("Barra","Taskbar","Barra","बार")
            BikeCommand.VOICE_AND_BAR -> listOf("Voz+barra","Voice+bar","Voz+barra","आवाज़+बार")
            BikeCommand.OPEN_WHATSAPP -> listOf("WhatsApp","WhatsApp","WhatsApp","WhatsApp")
            BikeCommand.SWITCH_MAPS_SPOTIFY -> listOf("Maps/Spot.","Maps/Spot.","Maps/Spot.","Maps/Spot.")
            BikeCommand.TOGGLE_GPS_BUBBLE -> listOf("Bolinha","GPS circle","Círculo","GPS गोला")
            BikeCommand.OPEN_OTHER -> listOf("Escolher","Choose app","Elegir app","ऐप चुनें")
        }
        val index=UiLanguage.codes.indexOf(language).coerceAtLeast(0)
        return fit(if(command==BikeCommand.OPEN_OTHER && !appName.isNullOrBlank()) appName else names[index])
    }
    // Grapheme boundaries keep Hindi vowel marks, joined letters and emoji intact.
    fun fit(value:String,limit:Int=LIMIT):String {
        val clean=value.replace(Regex("[\\p{Cc}\\p{Cf}&&[^\\u200C\\u200D]]"),"").replace(Regex("\\s+")," ").trim()
        val iterator=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(clean) }
        val ends=mutableListOf<Int>()
        iterator.first()
        while(true) { val end=iterator.next(); if(end==BreakIterator.DONE) break; ends+=end }
        return if(ends.size<=limit) clean else clean.substring(0,ends[limit-2]).trimEnd()+"…"
    }
}
