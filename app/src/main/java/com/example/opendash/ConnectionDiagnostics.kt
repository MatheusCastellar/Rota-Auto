package com.example.opendash

import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/** Local, bounded diagnostics. No SSIDs, credentials, coordinates or packet payloads. */
object ConnectionDiagnostics {
    private val events=ArrayDeque<String>()
    val rtp=AtomicLong()
    val decoded=AtomicLong()
    val encoded=AtomicLong()
    @Volatile var lastDecoded=0L
    @Volatile var authenticated=false
    @Synchronized fun reset() {
        events.clear(); rtp.set(0); decoded.set(0); encoded.set(0); lastDecoded=0; authenticated=false
        event("Início da conexão")
    }
    @Synchronized fun event(text:String) {
        // Several AA handshakes must fit, otherwise retries erase the triggering
        // screen/USB transition before the user can reconnect for inspection.
        if(events.size>=240) events.removeFirst()
        val elapsed=SystemClock.elapsedRealtime()
        events.addLast("${elapsed/1000}.${(elapsed%1000).toString().padStart(3,'0')}s · $text")
    }
    fun onDecoded() { decoded.incrementAndGet(); lastDecoded=SystemClock.elapsedRealtime() }
    @Synchronized fun report():String="Rota Auto ${BuildConfig.VERSION_NAME}\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n"+
        "Autenticação: ${if(authenticated) "confirmada" else "não confirmada"}\nQuadros codificados: ${encoded.get()}\nPacotes de vídeo enviados: ${rtp.get()}\nRespostas de vídeo do painel: ${decoded.get()}\n\n"+
        events.joinToString("\n")+"\n\nDiagnóstico local, sem senha, nome da rede ou localização. Respostas do protocolo não substituem conferir a imagem no painel."
}
