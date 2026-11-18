package com.example.opendash.auto

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.proto.*
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.andrerinas.openheadunit.aap.MicSessionController
import com.google.protobuf.MessageLite
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.*
import javax.net.ssl.*

/** Loopback-only experimental receiver. No external TLS endpoints or global trust changes. */
internal class AaSession(private val context:Context,private val listener:Listener,
    private val microphoneEnabled:Boolean=false,
    private val captureFactory:(()->AaCapture)?=null,
    private val phoneAudio:Boolean=false, parkedInitially:Boolean=false,nightInitially:Boolean=false):AutoCloseable {
    interface Listener {
        fun status(message:String)
        fun diagnostic(message:String) {}
        fun navigation(value:AaNavigation) {}
        fun nowPlaying(track:AaNowPlaying) {}
        fun video(data:ByteArray,timeUs:Long,config:Boolean)
        fun audio(channel:Int,data:ByteArray)
        fun audio(channel:Int,data:ByteArray,consumed:()->Unit) { audio(channel,data); consumed() }
        fun audioStop(channel:Int)
        fun ended(error:String?)
        fun ended(error:String?,retryable:Boolean) { ended(error) }
    }
    @Volatile private var closed=false
    @Volatile private var ready=false
    @Volatile private var inputReady=false
    @Volatile private var socket:Socket?=null
    private lateinit var engine:SSLEngine
    private lateinit var input:InputStream
    private lateinit var output:OutputStream
    private val tlsLock=Any()
    private val writeLock=Any()
    private val sessions=ConcurrentHashMap<Int,Int>()
    private val navigationStatus=AaNavigationStatus()
    private val mediaStatus=AaMediaStatus()
    private val sensorLock=Any()
    private var parked=parkedInitially
    private var night=nightInitially
    private val requestedSensors=mutableSetOf<Sensors.SensorType>()
    private val sender=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(64),{ r -> Thread(r,"aa-input") },ThreadPoolExecutor.AbortPolicy())
    private var worker:Thread?=null
    private val heartbeat=Executors.newSingleThreadScheduledExecutor { Thread(it,"aa-heartbeat") }
    private var stage="conexão local"
    private val micLifecycle=Executors.newSingleThreadExecutor { Thread(it,"aa-mic-lifecycle") }
    private val micSender=Executors.newSingleThreadExecutor { Thread(it,"aa-mic-send") }
    private val capture by lazy { captureFactory?.invoke() ?: AaMicrophone(context,{microphoneEnabled && !closed},listener::diagnostic) }
    private val mic=MicSessionController(
        lifecycle={ job -> try { micLifecycle.execute(job) } catch(_:RejectedExecutionException) {} },
        sending={ job -> sendMic(job) },
        startCapture={ callback -> if(microphoneEnabled && !closed) capture.start(callback) else -1 },
        stopCapture={ capture.stop() },
        response={ id,ok -> sendMic {
            send(7,0x8006,Media.MicrophoneResponse.newBuilder().setSessionId(id)
                .setStatus(if(ok) Common.MessageStatus.STATUS_SUCCESS_VALUE else Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE).build())
        } },
        data={ bytes,time -> sendBytes(7,0,ByteBuffer.allocate(8+bytes.size).putLong(time).put(bytes).array()) },
        clockMs={SystemClock.elapsedRealtime()},timestampUs={SystemClock.elapsedRealtimeNanos()/1000},
        report={listener.diagnostic("Microfone: ${it.frames} pacotes; ${it.acks} confirmações; nível=${it.peak}; descartados=${it.discarded} bytes")}
    )
    private fun sendMic(job:()->Unit) {
        try { micSender.execute { if(!closed) try { job() } catch(e:Exception) {
            listener.diagnostic("Envio do microfone: ${e.javaClass.simpleName}"); close()
        } } } catch(_:RejectedExecutionException) {}
    }
    fun start() {
        check(worker==null)
        worker=Thread({
            var error:String?=null
            var retryable=false
            try {
                listener.status("Conectando ao Android Auto deste celular…")
                val s=Socket(); socket=s; s.tcpNoDelay=true; s.soTimeout=15_000
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"),5277),5000)
                input=BufferedInputStream(s.getInputStream(),65536); output=s.getOutputStream()
                stage="negociação de versão"
                // Rich navigation state/destination messages require protocol >= 1.6.
                plain(1,byteArrayOf(0,1,0,6))
                val response=AaWire.read(input)
                require(response.channel==0 && response.payload.size>=8 && u16(response.payload,0)==2) { "Resposta de versão inesperada" }
                require(u16(response.payload,6)!=65535) { "Versão Android Auto incompatível" }
                listener.diagnostic("AA versão ${u16(response.payload,2)}.${u16(response.payload,4)}; status ${u16(response.payload,6)}")
                stage="autenticação TLS"
                listener.status("Autenticando receptor Android Auto…")
                handshake()
                plain(4,byteArrayOf(8,0)); ready=true
                // With native phone music there may be no media traffic at all while
                // the map is static. Check the peer instead of timing out a healthy idle link.
                heartbeat.scheduleWithFixedDelay({
                    if(!closed) try {
                        send(0,11,Control.PingRequest.newBuilder().setTimestamp(SystemClock.elapsedRealtimeNanos()).build())
                    } catch(_:Exception) {
                        if(!closed) { listener.diagnostic("Verificação da conexão falhou"); runCatching { socket?.close() } }
                    }
                },5,5,TimeUnit.SECONDS)
                stage="aguardando pedido de recursos"
                listener.status("Android Auto autenticado · aguardando vídeo")
                val assembler=AaWire.Assembler()
                while(!closed) {
                    val frame=AaWire.read(input)
                    val bytes=if(frame.flags and 8!=0) unwrap(frame.payload) else frame.payload
                    val message=assembler.accept(frame,bytes) ?: continue
                    dispatch(frame.channel,frame.flags,u16(message,0),message.copyOfRange(2,message.size))
                }
            } catch(e:Exception) {
                retryable=!closed && (e is EOFException || e is SocketTimeoutException || e is SocketException)
                if(!closed) error=when(e) {
                    is ConnectException -> "O servidor do Android Auto não está ativo. Abra as configurações do Android Auto e inicie o servidor da unidade principal."
                    is SocketTimeoutException -> "Android Auto não respondeu a tempo. Desbloqueie o celular, confira os avisos do Android Auto e reinicie o servidor."
                    is EOFException -> "Android Auto fechou a conexão na etapa: $stage (EOFException)."
                    else -> "Android Auto: ${e.javaClass.simpleName} · ${e.message?.take(180).orEmpty()}"
                }
            } finally { close(); listener.ended(error,retryable) }
        },"aa-receiver").also { it.start() }
    }
    private fun plain(type:Int,payload:ByteArray)=synchronized(writeLock) {
        AaWire.write(output,0,3,byteArrayOf((type shr 8).toByte(),type.toByte())+payload)
    }
    private fun handshake() {
        // This permissive trust manager is confined to the local AA developer-server protocol.
        val trust=object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?) {}
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?) {}
        }
        val ssl=SSLContext.getInstance("TLSv1.2")
        ssl.init(arrayOf(SingleKeyKeyManager(context)),arrayOf(trust),null)
        engine=ssl.createSSLEngine("localhost",5277).apply { useClientMode=true; enabledProtocols=arrayOf("TLSv1.2"); beginHandshake() }
        var pending=ByteArray(0)
        val deadline=SystemClock.elapsedRealtime()+25_000
        while(true) {
            check(!closed && SystemClock.elapsedRealtime()<deadline) { "Autenticação Android Auto excedeu o tempo" }
            when(engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out=ByteBuffer.allocate(65536)
                    val r=engine.wrap(ByteBuffer.allocate(0),out)
                    check(r.status==SSLEngineResult.Status.OK)
                    out.flip(); if(out.hasRemaining()) plain(3,ByteArray(out.remaining()).also { out.get(it) })
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if(pending.isEmpty()) pending=readTlsHandshake()
                    val src=ByteBuffer.wrap(pending); val dst=ByteBuffer.allocate(65536)
                    val result=engine.unwrap(src,dst)
                    pending=ByteArray(src.remaining()).also { src.get(it) }
                    when(result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> { pending+=readTlsHandshake(); require(pending.size<262144) }
                        SSLEngineResult.Status.OK -> Unit
                        else -> error("Falha TLS: ${result.status}")
                    }
                }
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                else -> error("Estado TLS inesperado")
            }
        }
    }
    private fun readTlsHandshake():ByteArray {
        while(true) {
            val f=AaWire.read(input); require(f.channel==0 && f.flags==3 && f.payload.size>=2)
            when(u16(f.payload,0)) { 2 -> continue; 3 -> return f.payload.copyOfRange(2,f.payload.size); else -> error("Resposta TLS inesperada") }
        }
    }
    private fun runTasks() { while(true) { val t=engine.delegatedTask ?: break; t.run() } }
    private fun unwrap(bytes:ByteArray):ByteArray=synchronized(tlsLock) {
        val src=ByteBuffer.wrap(bytes); val out=ByteArrayOutputStream()
        while(src.hasRemaining()) {
            val dst=ByteBuffer.allocate(65536); val r=engine.unwrap(src,dst)
            require(r.status==SSLEngineResult.Status.OK && r.bytesConsumed()>0) { "Registro TLS incompleto: ${r.status}" }
            runTasks(); dst.flip(); out.write(ByteArray(dst.remaining()).also { dst.get(it) })
        }; out.toByteArray()
    }
    private fun send(channel:Int,type:Int,proto:MessageLite)=sendBytes(channel,type,proto.toByteArray())
    private fun sendBytes(channel:Int,type:Int,data:ByteArray)=synchronized(writeLock) {
        check(!closed)
        val bytes=byteArrayOf((type shr 8).toByte(),type.toByte())+data
        var offset=0
        while(offset<bytes.size) {
            val n=minOf(16000,bytes.size-offset)
            val src=ByteBuffer.wrap(bytes,offset,n); val dst=ByteBuffer.allocate(65536)
            synchronized(tlsLock) {
                val r=engine.wrap(src,dst); require(r.status==SSLEngineResult.Status.OK && !src.hasRemaining()); runTasks()
            }
            dst.flip(); val ciphertext=ByteArray(dst.remaining()).also { dst.get(it) }
            val flags=8 or (if(offset==0) 1 else 0) or (if(offset+n==bytes.size) 2 else 0) or
                (if(channel!=0 && type in 2..26) 4 else 0)
            AaWire.write(output,channel,flags,ciphertext,bytes.size); offset+=n
        }
    }
    private fun dispatch(channel:Int,flags:Int,type:Int,data:ByteArray) {
        if(type!=0 && type!=1 && type!=11 && type!=12 && type!=0x8004)
            listener.diagnostic("AA recebeu canal=$channel tipo=0x${type.toString(16)} tamanho=${data.size}")
        if(channel==0 || flags and 4!=0) {
            when(type) {
                5 -> {
                    stage="envio dos recursos da motocicleta"
                    send(0,6,AaServices.discovery(microphoneEnabled,phoneAudio))
                    listener.diagnostic("AA recursos enviados: motocicleta, vídeo 800×480, toque; fala=${if(phoneAudio) "direta no celular" else "pela ponte"}; microfone=${if(microphoneEnabled) "pela ponte" else "gerenciado pelo Android Auto"}")
                    stage="recursos enviados; aguardando abertura dos canais"
                }
                7 -> {
                    stage="abertura do canal $channel"
                    send(channel,8,Control.ChannelOpenResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build())
                }
                11 -> send(0,12,Control.PingResponse.newBuilder().setTimestamp(Control.PingRequest.parseFrom(data).timestamp).build())
                13 -> send(0,14,Control.NavFocusNotification.newBuilder().setFocusType(Control.NavFocusType.NAV_FOCUS_PROJECTED).build())
                18 -> {
                    val release=Control.AudioFocusRequestNotification.parseFrom(data).request==Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE
                    send(0,19,Control.AudioFocusNotification.newBuilder().setFocusState(if(release) Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS else Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN).setUnsolicited(false).build())
                }
                15 -> {
                    val reason=Control.ByeByeRequest.parseFrom(data).reason.name
                    send(0,16,Control.ByeByeResponse.getDefaultInstance())
                    throw IOException("Encerramento solicitado pelo Android Auto: $reason")
                }
                255,65535 -> throw IOException("Android Auto rejeitou uma mensagem de protocolo: 0x${type.toString(16)}")
            }; return
        }
        if(channel==1 && type==0x8001) {
            val requested=Sensors.SensorRequest.parseFrom(data).type
            synchronized(sensorLock) {
                val event=AaSensors.event(requested,parked,night)
                send(1,0x8002,Sensors.SensorResponse.newBuilder().setStatus(if(event!=null)
                    Common.MessageStatus.STATUS_SUCCESS else Common.MessageStatus.STATUS_INVALID_SENSOR).build())
                if(event!=null) {
                    requestedSensors.add(requested)
                    send(1,0x8003,event)
                    listener.diagnostic("AA sensor $requested; moto parada=$parked; noite=$night")
                }
            }
            return
        }
        if(channel==3 && type==0x8002) {
            listener.diagnostic("AA teclas solicitadas: ${Input.KeyBindingRequest.parseFrom(data).keycodesList.joinToString()}")
            inputReady=true
            send(3,0x8003,Input.BindingResponse.newBuilder().setStatus(Common.MessageStatus.STATUS_SUCCESS).build()); return
        }
        if(channel==7) {
            when(type) {
                0x8005 -> {
                    val request=Media.MicrophoneRequest.parseFrom(data)
                    listener.diagnostic("AA microfone: ${if(request.open) "abrir" else "fechar"}")
                    if(!request.open) mic.close(reply=true)
                    else if(!microphoneEnabled) mic.reject()
                    else mic.open(if(request.hasMaxUnacked()) request.maxUnacked.coerceAtMost(8) else 1)
                }
                0x8004 -> {
                    val ack=Media.Ack.parseFrom(data)
                    mic.acknowledge(ack.sessionId,if(ack.receiveTimestampNsCount>0) ack.receiveTimestampNsCount else if(ack.hasAck()) ack.ack else 1)
                }
            }
            return
        }
        if(channel==11) {
            GuidedDiagnostics.packet("AA NAV tipo=0x${type.toString(16)}",data)
            runCatching { navigationStatus.receive(type,data) }.onSuccess { value ->
                if(value!=null) listener.navigation(value)
            }.onFailure { listener.diagnostic("AA ignorou orientação inválida") }
            return
        }
        if(channel==10) {
            runCatching { mediaStatus.receive(type,data) }.onSuccess { track ->
                if(track!=null) listener.nowPlaying(track)
            }.onFailure { listener.diagnostic("AA ignorou metadados de mídia inválidos") }
            return
        }
        if(channel !in listOf(2,4,5,6)) return
        when(type) {
            0x8000 -> {
                if(channel==2) stage="configuração do canal de vídeo"
                send(channel,0x8003,Media.Config.newBuilder().setStatus(Media.Config.ConfigStatus.STATUS_READY).setMaxUnacked(4).addConfigurationIndices(0).build())
                if(channel==2) focus(true)
            }
            0x8001 -> { sessions[channel]=Media.Start.parseFrom(data).sessionId; if(channel==2) { stage="recepção de vídeo"; listener.status("Android Auto iniciou o vídeo") } }
            0x8002 -> if(channel!=2) listener.audioStop(channel) else listener.diagnostic("AA interrompeu o vídeo; conexão de controle permanece aberta")
            0x8007 -> {
                val request=Media.VideoFocusRequestNotification.parseFrom(data)
                val mode=AaVideoFocus.response(request)
                listener.diagnostic("AA foco de vídeo: ${request.mode.name}; motivo=${request.reason.name}; resposta=${mode.name}")
                send(2,0x8008,Media.VideoFocusNotification.newBuilder().setMode(mode).setUnsolicited(false).build())
            }
            0,1 -> {
                val start=if(type==0) 8 else 0
                require(data.size>=start)
                val payload=data.copyOfRange(start,data.size)
                if(channel==2) listener.video(payload,if(type==0) ByteBuffer.wrap(data,0,8).long else 0,type==1)
                else if(type==0) {
                    val id=sessions[channel] ?: 0
                    listener.audio(channel,payload) {
                        if(!closed) try { send(channel,0x8004,Media.Ack.newBuilder().setSessionId(id).setAck(1).build()) }
                        catch(_:Exception) { close() }
                    }
                }
                if(type==0 && channel==2) send(channel,0x8004,Media.Ack.newBuilder().setSessionId(sessions[channel] ?: 0).setAck(1).build())
            }
        }
    }
    fun setParked(value:Boolean) {
        try { sender.execute {
            if(!closed) synchronized(sensorLock) {
                parked=value
                requestedSensors.filter { it!=Sensors.SensorType.NIGHT }.forEach { sensor ->
                    try { send(1,0x8003,AaSensors.event(sensor,parked)!!) }
                    catch(_:Exception) { close(); return@execute }
                }
                listener.diagnostic("AA declaração manual: moto parada=$parked")
            }
        } } catch(_:RejectedExecutionException) {}
    }
    fun setNight(value:Boolean) {
        try { sender.execute {
            if(!closed) synchronized(sensorLock) {
                if(night==value) return@execute
                night=value
                if(Sensors.SensorType.NIGHT in requestedSensors) {
                    try { send(1,0x8003,AaSensors.event(Sensors.SensorType.NIGHT,parked,night)!!) }
                    catch(_:Exception) { close(); return@execute }
                }
                listener.diagnostic("AA mapa: "+if(night) "noite" else "dia")
            }
        } } catch(_:RejectedExecutionException) {}
    }
    private fun focus(on:Boolean)=send(2,0x8008,Media.VideoFocusNotification.newBuilder()
        .setMode(if(on) Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED else Media.VideoFocusMode.VIDEO_FOCUS_NATIVE).setUnsolicited(true).build())
    fun touch(action:Int,x:Int,y:Int) {
        if(!ready || !inputReady || closed) return
        enqueueInput { runCatching { sendTouch(action,x,y) } }
    }
    // The 800x480 AA source retains its bar even when the bike output crops it.
    // Queue a whole tap atomically so no other key can interleave between DOWN and UP.
    fun tap(x:Int,y:Int):Boolean {
        if(!ready || !inputReady || closed) return false
        return enqueueInput {
            try {
                sendTouch(0,x,y)
                Thread.sleep(80)
                sendTouch(1,x,y)
            } catch(e:Exception) {
                if(!closed) { listener.diagnostic("Falha ao tocar no Android Auto: ${e.javaClass.simpleName}"); close() }
            }
        }
    }
    private fun sendTouch(action:Int,x:Int,y:Int) {
            val touch=Input.TouchEvent.newBuilder().setAction(Input.TouchEvent.PointerAction.forNumber(action) ?: Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL)
                .setActionIndex(0).addPointerData(Input.TouchEvent.Pointer.newBuilder().setX(x.coerceIn(0,799)).setY(y.coerceIn(0,479)).setPointerId(0))
            send(3,0x8001,Input.InputReport.newBuilder().setTimestamp(SystemClock.elapsedRealtimeNanos()).setDispChannelId(2).setTouchEvent(touch).build())
    }
    fun scrollApps():Boolean {
        if(!ready || !inputReady || closed) return false
        return enqueueInput {
            try {
                sendTouch(0,400,350)
                for(step in 1..8) { Thread.sleep(35); sendTouch(2,400,350-step*28) }
                sendTouch(1,400,126)
            } catch(_:Exception) { if(!closed) close() }
        }
    }
    fun key(code:Int):Boolean {
        if(!ready || !inputReady || closed) return false
        return enqueueInput {
            try {
                sendKey(code,true)
                // A hardware click has a duration. Keep DOWN/UP ordered on this executor,
                // without blocking reception/ACKs or the main thread.
                Thread.sleep(80)
                sendKey(code,false)
            } catch(e:Exception) {
                if(!closed) { listener.diagnostic("Falha ao enviar tecla $code: ${e.javaClass.simpleName}"); close() }
            }
        }
    }
    private fun sendKey(code:Int,down:Boolean) {
        val keys=Input.KeyEvent.newBuilder().addKeys(Input.Key.newBuilder().setKeycode(code).setDown(down).setMetastate(0).setLongpress(false))
        send(3,0x8001,Input.InputReport.newBuilder().setTimestamp(SystemClock.elapsedRealtimeNanos()).setKeyEvent(keys).build())
    }
    fun rotate(delta:Int):Boolean {
        if(!ready || !inputReady || closed || delta !in listOf(-1,1)) return false
        return enqueueInput { runCatching {
            val relative=Input.RelativeEvent.newBuilder().addData(Input.RelativeEvent_Rel.newBuilder().setKeycode(65536).setDelta(delta))
            send(3,0x8001,Input.InputReport.newBuilder().setTimestamp(SystemClock.elapsedRealtimeNanos())
                .setKeyEvent(Input.KeyEvent.getDefaultInstance()).setRelativeEvent(relative).build())
        } }
    }
    private fun enqueueInput(task:()->Unit):Boolean {
        try { sender.execute(task); return true } catch(_:RejectedExecutionException) {
            // A stuck connection must end rather than silently lose a touch-UP or block the UI.
            if(!closed) { listener.status("Conexão Android Auto congestionada. Reinicie a ponte."); close() }
            return false
        }
    }
    override fun close() {
        closed=true; ready=false; inputReady=false; heartbeat.shutdownNow(); runCatching { socket?.close() }; sender.shutdownNow()
        mic.close(shutdown=true); micLifecycle.shutdown(); micSender.shutdownNow()
    }
    private fun u16(b:ByteArray,i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
}
