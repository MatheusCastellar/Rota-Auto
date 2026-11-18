package com.example.opendash.auto

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

internal data class DiagnosticStep(val title:String,val instruction:String,val context:String="",val glyph:Int?=65,val seconds:Int=5)

/** Explicit user observations, never a detector of the bike's native screen. */
internal object GuidedDiagnostics {
    val steps=buildList {
        add(DiagnosticStep("Conexão", "Ligue o Android Auto e conecte à moto pelos botões abaixo. Quando houver imagem no painel, toque em Começar etapa. Não é preciso ligar o motor.",seconds=10))
        add(DiagnosticStep("Mapa — sem apertar nada", "Deixe o painel na linha Android Auto, fora dos controles de música. Confirme visualmente, comece a etapa e não aperte o guidão durante a contagem.","Fora",seconds=10))
        for(button in listOf("direita","esquerda","baixo","cima")) add(DiagnosticStep("Mapa — $button", "Mantenha a linha Android Auto. Comece a etapa e aperte $button uma vez. Se a tela mudar, anote no resultado.","Fora"))
        add(DiagnosticStep("Entrar nos controles", "Comece a etapa ainda na linha Android Auto. Abra os controles ‹ ⏯ › da moto. Ao terminar a contagem, confirme se essa linha apareceu.","Transição para dentro",seconds=10))
        add(DiagnosticStep("Música — sem apertar nada", "Confira que a linha ‹ ⏯ › está visível. Comece a etapa e não aperte nenhum botão durante a contagem.","Dentro",seconds=10))
        for(button in listOf("direita","esquerda","baixo","cima","centro")) add(DiagnosticStep("Música — $button", "Deixe a linha ‹ ⏯ › visível. Comece a etapa e aperte $button uma vez. Anote se a tela mudou.","Dentro"))
        add(DiagnosticStep("Sair dos controles", "Comece ainda na linha ‹ ⏯ ›. Volte à linha Android Auto usando o comando normal da moto. Confirme a tela no resultado. O app não deduz o modo pelo botão.","Transição para fora",seconds=10))
        add(DiagnosticStep("Mapa — após voltar", "Confira a linha Android Auto. Comece e não aperte nada durante a contagem.","Fora",seconds=10))
        for(glyph in listOf(65,90,255)) {
            add(DiagnosticStep("Mapa — símbolo $glyph", "Deixe a linha Android Auto visível. Comece a etapa: o app aplicará o símbolo $glyph. Espere dois segundos e aperte direita uma vez. Anote se a bola apareceu e se houve reação.","Fora",glyph,8))
            add(DiagnosticStep("Música — símbolo $glyph", "Abra a linha ‹ ⏯ › antes de começar. O símbolo $glyph será aplicado. Espere dois segundos e aperte direita uma vez. Anote a aparência e a reação.","Dentro",glyph,8))
        }
        add(DiagnosticStep("Navegação do Maps", "Em Ajustes, deixe Ocultar bola desligado. Inicie uma rota no Google Maps e volte aqui. Comece a etapa; o teste de símbolo será removido. Confira a seta e a distância da bolinha, e a distância ao destino na faixa marrom.","Mapa com rota",null,15))
        add(DiagnosticStep("Fim da rota", "Comece a etapa e cancele a rota no Google Maps. Volte aqui e anote se a bolinha ficou neutra e a faixa voltou a 0 m.","Mapa sem rota",null,15))
        add(DiagnosticStep("Reconexão", "Comece a etapa. Desligue o painel, espere alguns segundos e ligue novamente com o Wi-Fi da moto habilitado. Aguarde a recuperação. Você pode esperar mais que a contagem antes de marcar o resultado.","Reconexão",null,30))
        add(DiagnosticStep("Tela bloqueada", "Comece a etapa e bloqueie o celular por cerca de 20 segundos. Observe se a imagem permanece na moto. Desbloqueie e marque o resultado.","Tela bloqueada",null,20))
    }
    @Volatile var active=false; private set
    @Volatile var index=0; private set
    @Volatile var measuring=false; private set
    @Volatile var startedAt=0L; private set
    @Volatile var error=""; private set
    private val rxCount=AtomicLong(); val rx:Long get()=rxCount.get()
    private val buttonCount=AtomicLong(); val buttons:Long get()=buttonCount.get()
    @Volatile var navigation=AaNavigation(); private set
    @Volatile var lastButton=""; private set
    private var beginning=0L
    private var previousBlock=true
    private var file:File?=null
    private val reserved=AtomicLong()
    private val lost=AtomicLong()
    private val writer=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task,"guided-diagnostics-log").apply { isDaemon=true } })
    val remaining:Int get()=if(!measuring) 0 else (steps[index].seconds-(SystemClock.elapsedRealtime()-startedAt)/1000).toInt().coerceAtLeast(0)
    fun initialize(context:Context) {
        if(file==null) file=File(context.filesDir,"guided-diagnostics.txt")
        BikeInputCapture.initialize(context)
    }
    fun available()=file?.exists()==true
    fun start(context:Context) {
        initialize(context)
        if(active) return
        // Keep older complete runs instead of truncating them when a new test starts.
        file=File(context.filesDir,"guided-diagnostics.txt")
        index=0; measuring=false; beginning=SystemClock.elapsedRealtime(); reserved.set(0); lost.set(0)
        error=""; rxCount.set(0); buttonCount.set(0); lastButton=""; active=true
        val target=file!!
        writer.execute {
            try {
                if(target.exists()) target.copyTo(File(context.filesDir,"guided-diagnostics-previous.txt"),overwrite=true)
                target.writeText("Rota Auto — diagnóstico guiado\n${java.util.Date()}\nAndroid ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}\nVersão ${context.packageManager.getPackageInfo(context.packageName,0).versionName}\nMarcas Fora/Dentro são observações do usuário, não detecção automática. RX do socket da moto; TX de controle; canal de navegação AA. Sem áudio, vídeo, senha ou chave de autenticação. Ruas da rota podem aparecer.\n")
            } catch(_:Exception) { error="Falha ao abrir o arquivo de diagnóstico." }
        }
        previousBlock=BikeInputCapture.state.value.blockActions
        BikeInputCapture.stop("Substituída pelo diagnóstico guiado")
        BikeInputCapture.setBlockActions(true); BikeInputCapture.start()
        val prefs=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
        event("START fps=${prefs.getInt("video-fps",VideoProfile.DEFAULT_FPS)} bitrate=${VideoProfile.fromPreferences(prefs)} ocultar=${prefs.getBoolean("clean-projection",true)} perfil=${BikeControlLayout.active(prefs)}")
    }
    fun beginStep() {
        if(!active || measuring) return
        val step=steps[index]
        event("PREPARADO etapa=${index+1} ${step.title}; contexto informado=${step.context}")
        val state=AutoBridgeService.state.value
        event("ESTADO ${state.aa}; ${state.bike}; navegação=$navigation")
        BikeInputCapture.context(step.context); BikeInputCapture.selectGlyph(step.glyph)
        startedAt=SystemClock.elapsedRealtime(); measuring=true
        event("COMEÇAR etapa=${index+1}; símbolo=${step.glyph ?: "navegação real"}")
    }
    fun answer(result:String,note:String="",skip:Boolean=false) {
        if(!active || (!skip && (!measuring || remaining>0))) return
        event("RESULTADO etapa=${index+1} ${steps[index].title}; $result; anotação=${note.replace('\n',' ').take(1000)}")
        measuring=false; index++
        if(index>=steps.size) { index=steps.lastIndex; finish("CONCLUÍDO") }
    }
    fun repeat() {
        if(!active) return
        event("REPETIR etapa=${index+1}"); measuring=false
    }
    fun finish(reason:String="ENCERRADO PELO USUÁRIO") {
        if(!active) return
        event("$reason; RX=$rx; botões=$buttons; registros recusados=${lost.get()}; bytes reservados=${reserved.get()}")
        active=false; measuring=false; BikeInputCapture.stop("STOP · diagnóstico guiado")
        BikeInputCapture.setBlockActions(previousBlock)
    }
    fun packet(kind:String,payload:ByteArray) {
        if(!active) return
        if(kind.startsWith("RX")) rxCount.incrementAndGet()
        event("$kind bytes=${payload.size} hex="+buildString(payload.size*2) { for(b in payload) { val v=b.toInt() and 255; append("0123456789abcdef"[v ushr 4]); append("0123456789abcdef"[v and 15]) } })
    }
    fun button(payload:ByteArray) {
        if(!active) return
        buttonCount.incrementAndGet(); lastButton=payload.joinToString(" ") { "%02X".format(it.toInt() and 255) }
        event("BOTÃO $lastButton")
    }
    fun nav(value:AaNavigation) { navigation=value; event("NAVEGAÇÃO ativa=${value.active}; símbolo=${value.glyph}; próxima=${value.distanceMeters}; destino=${value.destinationMeters}") }
    fun event(value:String) {
        if(!active) return
        val target=file ?: return
        val line="+${SystemClock.elapsedRealtime()-beginning} ms | etapa=${index+1} | $value\n"
        if(reserved.addAndGet(line.toByteArray().size.toLong())>50*1024*1024) { lost.incrementAndGet(); error="Limite de 50 MB atingido. Encerre e salve o teste."; return }
        try { writer.execute { try { target.appendText(line) } catch(_:Exception) { error="Falha de gravação. Libere espaço e salve o teste." } } }
        catch(_:RejectedExecutionException) { lost.incrementAndGet(); error="Alguns registros não foram salvos; perda registrada no relatório." }
    }
    /** Called on Dispatchers.IO, after finishing. FIFO barrier drains all accepted writes. */
    fun exportTo(output:java.io.OutputStream) {
        val task=FutureTask<Unit> {
            checkNotNull(file).inputStream().use { it.copyTo(output) }
            output.write("\nEXPORTAÇÃO: registros recusados=${lost.get()}; erro=$error\n".toByteArray())
            Unit
        }
        writer.queue.put(task)
        // A previous run may be exported after process restart, before the first task.
        writer.prestartCoreThread()
        task.get(30,TimeUnit.SECONDS)
    }
}

