package com.example.opendash.auto

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/** The phone's media session owns music; no media keys or volume go through AA. */
internal class BikeMediaControls(context:Context,private val assistant:()->Boolean) {
    private val audio=context.getSystemService(AudioManager::class.java)
    fun execute(action:TripperControls.Action)=execute(BikeControlLayout.default(false,action))
    fun execute(command:BikeCommand):String = when(command) {
        BikeCommand.PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,"Play / pause enviado")
        BikeCommand.PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS,"Faixa anterior enviada")
        BikeCommand.NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT,"Próxima faixa enviada")
        BikeCommand.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        BikeCommand.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        BikeCommand.VOICE -> if(assistant()) "Assistente de voz acionado" else "Voz indisponível: inicie o Android Auto com permissão de microfone"
        BikeCommand.NONE -> "Sem ação"
        else -> "Comando de navegação é enviado pela ponte Android Auto"
    }
    private fun media(key:Int,message:String):String {
        val down=SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(down,down,KeyEvent.ACTION_DOWN,key,0))
        audio.dispatchMediaKeyEvent(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,key,0))
        return message
    }
    private fun volume(direction:Int):String {
        if(audio.isVolumeFixed) return "Volume controlado pelo dispositivo de áudio conectado"
        val calling=audio.mode==AudioManager.MODE_IN_CALL || audio.mode==AudioManager.MODE_IN_COMMUNICATION
        val stream=if(calling) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        audio.adjustStreamVolume(stream,direction,0) // Keep the phone/telephony owner of routing.
        return "Volume ${if(calling) "da chamada" else "da mídia"}: ${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}"
    }
}
