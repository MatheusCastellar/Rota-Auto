package com.example.opendash.auto

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class InputCaptureLog(private val limit:Int=2000,private val characterLimit:Int=2*1024*1024) {
    var active=false; private set
    var count=0; private set
    var discarded=0; private set
    private var startMs=0L
    private var previous:Long?=null
    private var header=""
    private val lines=ArrayDeque<String>()
    private var characters=0
    val last:String get()=lines.lastOrNull().orEmpty().take(240)
    fun start(now:Long,date:String) {
        lines.clear(); characters=0; count=0; discarded=0; previous=null; startMs=now; active=true
        header="Rota Auto — captura de pacotes da moto\nInício: $date\nDatagramas completos recebidos pelo app, antes da interpretação; inclui desconhecidos, confirmações e dados brutos. Não é captura de todo o Wi-Fi.\n"
        add("+0 ms | START")
    }
    fun record(now:Long,payload:ByteArray,profile:Int,source:String="unknown",context:String="normal") {
        if(!active) return
        val gap=previous?.let { now-it }; previous=now; count++
        val hex=buildString(payload.size*3) { payload.forEachIndexed { i,b ->
            if(i>0) append(' '); val v=b.toInt() and 255; append("0123456789ABCDEF"[v ushr 4]); append("0123456789ABCDEF"[v and 15])
        } }
        add("+${now-startMs} ms | RX $source | bytes=${payload.size} | intervalo=${gap?.toString() ?: "—"} ms | perfil=$profile | $context | hex=$hex")
    }
    fun note(now:Long,value:String) { if(active) add("+${now-startMs} ms | $value") }
    fun stop(now:Long,reason:String="STOP") { if(active) { add("+${now-startMs} ms | $reason"); active=false } }
    private fun add(line:String) {
        while(lines.isNotEmpty() && (lines.size>=limit || characters+line.length>characterLimit)) { characters-=lines.removeFirst().length; discarded++ }
        if(line.length>characterLimit) { discarded++; return }
        lines.addLast(line); characters+=line.length
    }
    fun snapshot()=header+"Pacotes: $count | registros antigos descartados: $discarded | captura ativa: $active\n"+lines.joinToString("\n")+"\n"
}

/** One bounded local capture. Disk work is coalesced off the input/video thread. */
internal object BikeInputCapture {
    data class State(val active:Boolean=false,val count:Int=0,val last:String="",val available:Boolean=false,val diskError:Boolean=false,val blockActions:Boolean=true,
        val glyph:Int?=null,val buttons:Int=0,val context:String="Fora")
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val log=InputCaptureLog()
    private val writer=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"bike-input-log").apply { isDaemon=true } }
    val fileName="bike-inputs.log"
    private var file:AtomicFile?=null
    private var pending=false
    private var started=false
    private var previous=""
    @Synchronized fun initialize(context:Context) {
        if(file!=null) return
        val target=AtomicFile(File(context.applicationContext.filesDir,fileName)); file=target
        writer.execute {
            val saved=runCatching { target.openRead().use { input ->
                val bytes=ByteArray(8*1024*1024); var size=0
                while(size<bytes.size) { val n=input.read(bytes,size,bytes.size-size); if(n<0) break; size+=n }
                String(bytes,0,size,Charsets.UTF_8)
            } }.getOrDefault("")
            synchronized(this) { if(!started) { previous=saved; mutable.value=State(count=if(saved.isNotBlank()) -1 else 0,available=saved.isNotBlank(),blockActions=mutable.value.blockActions) } }
        }
    }
    val blocksActions:Boolean get()=GuidedDiagnostics.active || state.value.active && state.value.blockActions
    @Synchronized fun setBlockActions(value:Boolean) {
        mutable.value=mutable.value.copy(blockActions=value)
        if(log.active) { log.note(SystemClock.elapsedRealtime(),"Bloquear ações: $value"); changed() }
    }
    @Synchronized fun start() {
        started=true; previous=""
        mutable.value=mutable.value.copy(glyph=null,buttons=0)
        val date=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",java.util.Locale.ROOT).format(java.util.Date())
        log.start(SystemClock.elapsedRealtime(),date)
        log.note(SystemClock.elapsedRealtime(),"Bloquear ações: "+mutable.value.blockActions); changed()
    }
    @Synchronized fun stop(reason:String="STOP") { if(log.active) { log.stop(SystemClock.elapsedRealtime(),reason); mutable.value=mutable.value.copy(glyph=null); changed() } }
    @Synchronized fun record(payload:ByteArray,profile:Int,source:String="unknown") {
        GuidedDiagnostics.packet("RX $source",payload)
        if(log.active) {
        val s=mutable.value
        log.record(SystemClock.elapsedRealtime(),payload,profile,source,"símbolo solicitado=${s.glyph ?: "normal"}; contexto=${s.context}"); changed()
    } }
    @Synchronized fun button(payload:ByteArray) {
        GuidedDiagnostics.button(payload)
        if(log.active) {
        mutable.value=mutable.value.copy(buttons=mutable.value.buttons+1)
        note("BOTÃO 09 00 | hex="+payload.joinToString(" ") { "%02X".format(it.toInt() and 255) })
    } }
    @Synchronized fun selectGlyph(value:Int?) {
        require(value==null || value in 0..255)
        if(!log.active) return
        mutable.value=mutable.value.copy(glyph=value,buttons=0)
        note("TESTE | símbolo=${value ?: "normal"}; contexto=${mutable.value.context}")
    }
    @Synchronized fun context(value:String) {
        mutable.value=mutable.value.copy(context=value,buttons=0)
        note("Contexto informado pelo usuário: $value")
    }
    @Synchronized fun observation(value:String) {
        note("OBSERVAÇÃO USUÁRIO | símbolo=${mutable.value.glyph ?: "normal"}; contexto=${mutable.value.context}; $value")
    }
    @Synchronized fun note(value:String) { GuidedDiagnostics.event(value); if(log.active) { log.note(SystemClock.elapsedRealtime(),value); changed() } }
    @Synchronized fun snapshot()=if(started) log.snapshot() else previous
    private fun changed() {
        mutable.value=mutable.value.copy(active=log.active,count=log.count,last=log.last,available=true)
        if(pending || file==null) return
        pending=true
        writer.schedule({
            val text=synchronized(this) { pending=false; snapshot() }
            val target=file ?: return@schedule
            var stream:java.io.FileOutputStream?=null
            val ok=runCatching { stream=target.startWrite(); stream!!.write(text.toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }.isSuccess
            if(!ok) target.failWrite(stream)
            synchronized(this) { mutable.value=mutable.value.copy(diskError=!ok) }
        },250,TimeUnit.MILLISECONDS)
    }
}
