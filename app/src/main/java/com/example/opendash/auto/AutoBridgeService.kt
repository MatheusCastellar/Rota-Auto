package com.example.opendash.auto

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import android.view.Surface
import com.example.opendash.*
import com.example.opendash.dash.*
import com.example.opendash.dash.video.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.*
import kotlin.math.abs

data class AutoState(val active:Boolean=false,val aa:String="Android Auto parado",val bike:String="Moto desconectada",
    val codec:String="",val received:Long=0,val sent:Long=0,val ack:Long=0,val error:String="",val bikeError:String="",
    val controls:String="Joystick aguardando comandos",val screen:String="") {
    val problems:String get()=listOf(error,bikeError).filter { it.isNotBlank() }.joinToString("\n")
}

class AutoBridgeService:Service() {
    companion object {
        fun testApplication(packageName:String?) { instance?.openOtherApplication(packageName) }
        const val START="auto.START"; const val BIKE="auto.BIKE"; const val STOP="auto.STOP"
        const val NEW_BIKE="auto.NEW_BIKE"
        private val mutable=MutableStateFlow(AutoState())
        val state=mutable.asStateFlow()
        @Volatile private var instance:AutoBridgeService?=null
        private var preview:Surface?=null; private var width=0; private var height=0
        private var simulatedPreview=false
        fun simulatePreview(enabled:Boolean) { simulatedPreview=enabled; instance?.bridge?.simulatePreview(enabled) }
        fun showBar() { instance?.executeControl(BikeCommand.SHOW_BAR) }
        @Synchronized fun preview(surface:Surface?,w:Int=0,h:Int=0) {
            preview=surface; width=w; height=h
            instance?.let { service -> service.bridge?.attach(if(service.getSystemService(PowerManager::class.java).isInteractive) surface else null,w,h) }
        }
        @Synchronized fun detachPreview(expected:Surface?,after:()->Unit={}) {
            val b=instance?.bridge
            if(preview===expected) { preview=null; if(b!=null) b.attach(null,0,0,after) else after() }
            else if(b!=null) b.afterPreviewWork(after) else after()
        }
        fun touch(action:Int,x:Int,y:Int) { instance?.let { if(action==0) it.cancelPlayerShortcut(); it.aa?.touch(action,x,y) } }
        fun key(code:Int) { instance?.let { it.cancelPlayerShortcut(); it.aa?.key(code) } }
        fun calibrate(action:TripperControls.Action?) { instance?.armCalibration(action) }
        fun resetControls() { instance?.let { it.armCalibration(null); it.controls.reset(); it.saveControls(); it.publishControls("Calibração padrão restaurada") } }
        fun testControl(action:TripperControls.Action) { instance?.sendControl(action) }
        fun command(context:Context,action:String) {
            val intent=Intent(context,AutoBridgeService::class.java).setAction(action)
            if(action==STOP) context.stopService(intent) else context.startForegroundService(intent)
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var aa:AaSession?=null
    private var bridge:AaVideoBridge?=null
    private lateinit var wifi:DashWifiManager
    private lateinit var dash:DashSession
    private var wake:PowerManager.WakeLock?=null
    private var wifiLock:WifiManager.WifiLock?=null
    private val audio by lazy { AaAudio(this) { ConnectionDiagnostics.event(it) } }
    private var microphoneForeground=false
    private var phoneMicrophone=false
    private var phoneOutput=false
    private var audioConfigured=false
    private var starting=false
    @Volatile private var latestNavigation=AaNavigation()
    private var wantBike=false
    private var projectionGeneration=0
    @Volatile private var videoReady=false
    private var panelStop:Job?=null
    private var panelRecoveryJob:Job?=null
    private var panelRecoveryAttempts=0
    private val widgetStatus by lazy { PhoneWidgetStatus(this) }
    @Volatile private var ending=false
    private val controls=TripperControls(mediaMode=true)
    private val gestures=BikeButtonGestures()
    private var buttonJob:Job?=null
    private var calibrationJob:Job?=null
    private var lastButtonAt:Long?=null
    private var lastVoiceAt:Long?=null
    private var labelJob:Job?=null
    private var playerShortcutJob:Job?=null
    private val playerShortcut=SpotifyPlayerShortcut()
    private val appToggle=MapSpotifyToggle()
    private val controlPreferences by lazy { getSharedPreferences("auto-controls-options",MODE_PRIVATE) }
    private val menuSession by lazy { MenuSession(idleMs=MenuControls.duration(controlPreferences)) }
    private var menuExpiry:Job?=null
    private val profileIdle=ProfileIdleReturn()
    private var profileIdleJob:Job?=null
    private val controlPreferenceListener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key in listOf(ProfileIdleReturn.KEY,BikeControlLayout.PROFILE,MenuControls.ENABLED,"enabled")) scheduleProfileReturn()
        if(key=="video-fps" || key==VideoProfile.BITRATE || key==VideoProfile.MANUAL) bridge?.setProfile(VideoProfile.fromPreferences(controlPreferences))
        if(key==TemporaryBar.SECONDS || key==TemporaryBar.TOGGLE) bridge?.setBarOptions(controlPreferences.getInt(TemporaryBar.SECONDS,5),controlPreferences.getBoolean(TemporaryBar.TOGGLE,false))
        if(key=="clean-projection") dash.cleanProjection=controlPreferences.getBoolean("clean-projection",true)
        if(key==AaSensors.PARKED) aa?.setParked(controlPreferences.getBoolean(AaSensors.PARKED,false))
        if(key==MapDayNight.KEY) aa?.setNight(MapDayNight.current(controlPreferences))
        if(!controlPreferences.getBoolean(MenuControls.ENABLED,false)) {
            if(key==ProfilePopup.ENABLED && !controlPreferences.getBoolean(ProfilePopup.ENABLED,false)) bridge?.showProfilePopup(null)
            if(key==ProfilePopup.DURATION) bridge?.setProfilePopupDuration(ProfilePopup.duration(controlPreferences))
        }
        if(key==MenuControls.ENABLED || (key=="enabled" && !controlPreferences.getBoolean("enabled",true))) {
            cancelButtonGesture(); closeMenu()
        } else if(key==MenuControls.SECONDS) {
            val wasOpen=menuSession.deadline!=null
            menuSession.setDuration(MenuControls.duration(controlPreferences),SystemClock.elapsedRealtime())
            if(wasOpen && menuSession.deadline==null) closeMenu() else { renderMenu(); scheduleMenuExpiry() }
        } else if(key==MenuControls.ACTIVE) {
            if(menuSession.profile!=MenuControls.active(controlPreferences)) { menuSession.profile=MenuControls.active(controlPreferences); menuSession.touch(SystemClock.elapsedRealtime()) }
            renderMenu(); scheduleMenuExpiry()
        } else if(key==MenuControls.OPENER || key==MenuControls.CYCLE || key?.startsWith("menu-bind.")==true || key?.startsWith("profile-name.menu.")==true || key==UiLanguage.KEY) renderMenu()
        if(key==PanelLayout.PREFERENCE) scope.launch {
            bridge?.setLayout(PanelLayout.resolve(controlPreferences.getString(PanelLayout.PREFERENCE,null),controlPreferences.getBoolean("hide-aa-bar",false)))
        }
        if(key in listOf(PanelLayout.DISTORTION,PanelLayout.POSITION_X,PanelLayout.POSITION_Y,PanelLayout.SIZE_X,PanelLayout.SIZE_Y,CurveStyle.PREFERENCE)) updatePanelGeometry()
        if(key==PanelLogo.PREFERENCE || key==PanelLogo.BACKGROUND) bridge?.setLogo(PanelLogo.resolve(controlPreferences.getString(PanelLogo.PREFERENCE,null)),resources,
            OverlayBackground.resolve(controlPreferences.getString(PanelLogo.BACKGROUND,null)))
        if(key?.startsWith("widget-")==true) updateWidget()
        if(key in listOf(PanelLogo.SIZE,PanelLogo.POSITION_X,PanelLogo.POSITION_Y)) updateLogoPlacement()
        if(key==BlurArea.PREFERENCE || key=="panel-blur") bridge?.setBlur(BlurArea.resolve(controlPreferences.getString(BlurArea.PREFERENCE,null),controlPreferences.getBoolean("panel-blur",true)))
        if(key==BikeControlLayout.MODE || key==BikeControlLayout.PROFILE || key=="enabled" || key?.startsWith("button.")==true) scope.launch {
            cancelButtonGesture()
            if(key==BikeControlLayout.PROFILE) {
                panelFeedback(ProfileNames.label(this@AutoBridgeService,false,BikeControlLayout.active(controlPreferences)))
                if(!controlPreferences.getBoolean(MenuControls.ENABLED,false) && controlPreferences.getBoolean(ProfilePopup.ENABLED,false)) bridge?.showProfilePopup(ProfilePopup.read(this@AutoBridgeService))
            } else updateControlLabel()
        }
    }
    private fun updatePanelGeometry() {
        bridge?.setDistortion(controlPreferences.getInt(PanelLayout.DISTORTION,PanelLayout.DEFAULT_DISTORTION),
            controlPreferences.getInt(PanelLayout.POSITION_Y,0),controlPreferences.getInt(PanelLayout.SIZE_X,100),controlPreferences.getInt(PanelLayout.SIZE_Y,100),
            CurveStyle.resolve(controlPreferences.getString(CurveStyle.PREFERENCE,null)),controlPreferences.getInt(PanelLayout.POSITION_X,0))
    }
    private fun updateLogoPlacement() { bridge?.setLogoPlacement(controlPreferences.getInt(PanelLogo.SIZE,100),controlPreferences.getInt(PanelLogo.POSITION_X,0),controlPreferences.getInt(PanelLogo.POSITION_Y,0)) }
    private fun updateWidget() {
        val style=WidgetStyle.read(controlPreferences)
        if(!style.enabled) widgetStatus.close()
        bridge?.setWidget(if(style.enabled) runCatching { widgetStatus.read() }.getOrNull() else null,style)
    }
    private fun schedulePanelRecovery() {
        if(ending || !wantBike || panelRecoveryJob?.isActive==true) return
        panelRecoveryJob=scope.launch {
            val wait=listOf(1500L,3000L,6000L,12000L,20000L)[(panelRecoveryAttempts++).coerceAtMost(4)]
            delay(wait)
            if(ending || !wantBike || wifi.state.value.status!=WifiConnStatus.CONNECTED) return@launch
            disconnectPanel(); panelStop?.join(); connectPanelIfReady()
        }
    }
    private val mediaControls by lazy { BikeMediaControls(this) { (phoneMicrophone || microphoneForeground) && aa?.key(84)==true } }
    private val playbackMonitor by lazy { PhonePlaybackMonitor(this) { playing ->
        dash.musicPlaying=playing
        ConnectionDiagnostics.event("Mídia no celular: ${if(playing) "tocando" else "pausada"}")
    } }
    private val recovery=ProjectionRetry()
    private var recoveryJob:Job?=null
    private var wantProjection=false
    private val debuggingObserver=object:android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange:Boolean) { recordPower("Configuração de depuração alterada") }
    }
    private val powerReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            recordPower(intent.action.orEmpty())
            if(intent.action==Intent.ACTION_SCREEN_OFF) bridge?.attach(null,0,0)
        }
    }
    override fun onCreate() {
        super.onCreate(); BikeInputCapture.initialize(this); instance=this; ConnectionDiagnostics.reset(); mutable.value=AutoState()
        wifi=DashWifiManager(this,scope); dash=DashSession(scope)
        BikeControlLayout.migrateSplitInputs(controlPreferences)
        playbackMonitor.start()
        // Fixed protocol codes; only the actions in each profile are configurable.
        controls.reset(); saveControls()
        controlPreferences.registerOnSharedPreferenceChangeListener(controlPreferenceListener)
        scope.launch {
            while(isActive) { aa?.setNight(MapDayNight.current(controlPreferences)); delay(30_000) }
        }
        updateControlLabel()
        scheduleProfileReturn()
        val powerFilter=IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if(Build.VERSION.SDK_INT>=33) registerReceiver(powerReceiver,powerFilter,RECEIVER_NOT_EXPORTED) else registerReceiver(powerReceiver,powerFilter)
        contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ADB_ENABLED),false,debuggingObserver)
        wifi.onSsidResolved={ DashConfig.get(this).ssid=it }
        dash.onTransportError={ failedNetwork -> scope.launch {
            if(!ending && wantBike) wifi.recoverNetwork(failedNetwork)
        } }
        dash.onError={ scope.launch {
            mutable.update { it.copy(bike="Recuperando comunicação com o painel",bikeError="") }
            schedulePanelRecovery()
        } }
        dash.onRawPacket={ payload,source -> BikeInputCapture.record(payload,BikeControlLayout.active(controlPreferences),source) }
        dash.onButtonPacket={ payload -> BikeInputCapture.button(payload) }
        dash.onCaptureNote={ BikeInputCapture.note(it) }
        dash.onSentPacket={ packet ->
            // Exclude authentication/key and hostname packets. Never record the RTP video.
            if(GuidedDiagnostics.active && packet.size>17) {
                val fields=com.example.opendash.dash.protocol.K1GPacket.parseIncoming(packet.copyOfRange(0,8)+packet.copyOfRange(17,packet.size))
                if(fields.isNotEmpty() && fields.all { it.type in 5..6 }) GuidedDiagnostics.packet("TX PAINEL",packet)
            }
        }
        scope.launch { BikeInputCapture.state.collect {
            if(it.active && it.blockActions) cancelButtonGesture()
            dash.testManeuver=if(it.active) it.glyph else null
        } }
        dash.onButton=button@ { code ->
            val raw=code.toInt() and 255
            val now=SystemClock.elapsedRealtime()
            val gap=lastButtonAt?.let { now-it }
            lastButtonAt=now
            val event=controls.receive(raw,now,100)
            BikeInputCapture.note("Leitura: "+(event.action?.name ?: "DESCONHECIDO")+"; filtrado="+event.ignored+"; controles="+controlPreferences.getBoolean("enabled",true))
            ConnectionDiagnostics.event("Joystick: 0x${raw.toString(16)}; intervalo=${gap ?: -1} ms; ${if(event.ignored) "repetição filtrada" else event.action?.label ?: "não mapeado"}")
            if(BikeInputCapture.blocksActions) { cancelButtonGesture(); BikeInputCapture.note("Ação bloqueada · captura de teste"); return@button }
            if(!event.ignored) {
                when {
                    event.conflict -> publishControls("Esse código pertence ao centro. Escolha outra tecla para Home.")
                    event.learned -> { calibrationJob?.cancel(); saveControls(); publishControls("Comando salvo: ${event.action!!.label}") }
                    event.action!=null -> { publishControls("Recebido: ${event.action.label}"); sendControl(event.action) }
                    else -> publishControls("Botão não mapeado neste painel")
                }
            }
        }
        scope.launch { wifi.state.collect { s ->
            BikeInputCapture.note("Wi-Fi: ${s.status}")
            if(!wantBike) return@collect
            ConnectionDiagnostics.event("Wi-Fi: ${s.status}")
            when(s.status) {
                WifiConnStatus.REQUESTING -> {
                    panelRecoveryJob?.cancel(); disconnectPanel()
                    mutable.update { it.copy(bike=s.error ?: if(s.ssid=="RE_") "Escolha sua rede RE_ na lista do Android" else "Reconectando a moto salva…",bikeError="") }
                }
                WifiConnStatus.CONNECTED -> {
                    mutable.update { it.copy(bike="Wi-Fi conectado · aguardando imagem do Android Auto",bikeError="") }
                    connectPanelIfReady()
                }
                WifiConnStatus.ERROR -> {
                    wantBike=false
                    panelRecoveryJob?.cancel()
                    val message=s.error ?: "Tente novamente com o Wi-Fi do painel ligado"
                    ConnectionDiagnostics.event("Wi-Fi: $message")
                    mutable.update { it.copy(bike="Não foi possível conectar ao Wi-Fi da moto",bikeError=message) }
                }
                else -> Unit
            }
        } }
        scope.launch { dash.state.collect { s ->
            BikeInputCapture.note("Sessão painel: $s")
            if(s==DashState.READY && videoReady) { panelRecoveryAttempts=0; dash.startStreaming(); mutable.update { it.copy(bike="Enviando vídeo · confira a imagem no painel",bikeError="") } }
            if(s==DashState.ERROR) mutable.update { it.copy(bike="Painel não confirmou a conexão") }
        } }
        scope.launch { while(isActive) {
            updateWidget()
            mutable.update { it.copy(screen=powerState()) }
            bridge?.let { b -> mutable.update { it.copy(received=b.received.get(),sent=b.transmitted.get(),ack=ConnectionDiagnostics.decoded.get()) } }
            // Private debug-only snapshot for USB testing while the phone is locked.
            // No exported receiver, network endpoint, coordinates or credentials.
            if(BuildConfig.DEBUG) {
                val state=mutable.value
                val report="${state.aa}\n${state.bike}\n${state.screen}\nAA recebidos: ${state.received}; saída: ${state.sent}\nVídeo local: fila=${bridge?.inputWaitMs ?: 0} ms; codificação=${bridge?.encodeMs ?: 0} ms (não inclui Wi-Fi/painel)\n${state.problems}\n\n${ConnectionDiagnostics.report()}"
                withContext(Dispatchers.IO) {
                    runCatching { java.io.File(cacheDir,"projection-state.txt").writeText(report) }
                }
            }
            delay(1000)
        } }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent==null || intent.action==STOP) { stopSelf(); return START_NOT_STICKY }
        try { foreground(); locks() } catch(_:Exception) {
            mutable.update { it.copy(error="Não foi possível iniciar o serviço. Abra o aplicativo e tente novamente.") }; stopSelf(); return START_NOT_STICKY
        }
        mutable.update { it.copy(active=true) }
        // Requesting the bike must not start AA or codecs: an AA failure used to cancel the Wi-Fi chooser.
        if(intent.action==START && aa==null && !starting) {
            wantProjection=true; recoveryJob?.cancel(); recovery.manualStart(); startAuto()
        }
        if(intent.action==BIKE || intent.action==NEW_BIKE) {
            panelRecoveryJob?.cancel(); panelRecoveryAttempts=0
            wantBike=true; val config=DashConfig.get(this)
            // A manual reconnect must also replace a stale CONNECTED session. Do not
            // discard the remembered bike or interrupt the independent AA transport.
            disconnectPanel()
            val chooseNew=intent.action==NEW_BIKE || config.ssid.isBlank()
            wifi.connect(if(chooseNew) "RE_" else config.ssid,DashConfig.DEFAULT_PASSWORD,chooseNew)
        }
        return START_NOT_STICKY
    }
    private fun startAuto(recovering:Boolean=false) {
        val token=++projectionGeneration
        starting=true; videoReady=false
        mutable.update { it.copy(active=true,aa="Preparando vídeo…",error="",received=0,sent=0,codec="") }
        recordPower("Início da projeção")
        scope.launch {
            try {
                val options=getSharedPreferences("auto-controls-options",MODE_PRIVATE)
                options.edit().remove("native-area").apply()
                val profile=VideoProfile.fromPreferences(options)
                ConnectionDiagnostics.event("Vídeo: até ${profile.fps} fps; imagem completa 800×480 ajustada para 526×300")
                val previous=bridge; bridge=null
                if(previous!=null) withContext(Dispatchers.IO) { previous.closeAndWait() }
                val rtp=RtpPacketizer { packet -> if(dash.state.value==DashState.STREAMING) dash.sendRtp(packet) }
                var videoPtsMs=0L
                val nals=NalProcessor { data,_ -> rtp.packetize(data,true,videoPtsMs) }
                val b=AaVideoBridge({ data,ptsUs ->
                    videoPtsMs=ptsUs/1000
                    nals.process(data)
                    if(!videoReady) scope.launch {
                        if(token==projectionGeneration && !ending && !videoReady && (bridge?.received?.get() ?: 0)>0) {
                            videoReady=true; recovery.video(SystemClock.elapsedRealtime()); connectPanelIfReady()
                        }
                    }
                },{ message ->
                    ConnectionDiagnostics.event(message); mutable.update { it.copy(codec=message) }
                },{ message -> scope.launch { if(token==projectionGeneration) stopProjection(message) } },profile,PanelLayout.resolve(options.getString(PanelLayout.PREFERENCE,null),options.getBoolean("hide-aa-bar",false)))
                b.simulatePreview(simulatedPreview)
                bridge=b
                withContext(Dispatchers.IO) { b.start() }
                if(ending || token!=projectionGeneration) { b.close(); return@launch }
                updatePanelGeometry()
                b.setLogo(PanelLogo.resolve(options.getString(PanelLogo.PREFERENCE,null)),resources,OverlayBackground.resolve(options.getString(PanelLogo.BACKGROUND,null)))
                b.setBarOptions(options.getInt(TemporaryBar.SECONDS,5),options.getBoolean(TemporaryBar.TOGGLE,false))
                updateWidget()
                updateLogoPlacement()
                b.setBlur(BlurArea.resolve(options.getString(BlurArea.PREFERENCE,null),options.getBoolean("panel-blur",true)))
                if(recovering) b.waiting(resumeOnFrame=true)
                synchronized(Companion) { b.attach(if(getSystemService(PowerManager::class.java).isInteractive) preview else null,width,height) }
                aa=AaSession(this@AutoBridgeService,object:AaSession.Listener {
                    override fun status(message:String) { GuidedDiagnostics.event("AA $message"); ConnectionDiagnostics.event(message); mutable.update { it.copy(aa=message) } }
                    override fun diagnostic(message:String) { GuidedDiagnostics.event("AA $message"); ConnectionDiagnostics.event(message) }
                    override fun navigation(value:AaNavigation) {
                        if(!ending && token==projectionGeneration) {
                            GuidedDiagnostics.nav(value)
                            latestNavigation=value
                            applyNavigation()
                            ConnectionDiagnostics.event("AA orientação: ativa="+value.active+"; símbolo="+value.glyph+"; distância disponível="+(value.distanceMeters!=null)+"; destino disponível="+(value.destinationMeters!=null))
                        }
                    }
                    override fun nowPlaying(track:AaNowPlaying) {
                        if(!ending && token==projectionGeneration) {
                            dash.track=DashTrack(track.title,track.artist,track.playing)
                            ConnectionDiagnostics.event("AA atualizou música · título=${track.title.isNotBlank()}; artista=${track.artist.isNotBlank()}; tocando=${track.playing}")
                        }
                    }
                    override fun video(data:ByteArray,timeUs:Long,config:Boolean)=b.submit(data,timeUs,config)
                    override fun audio(channel:Int,data:ByteArray) {
                        audio(channel,data) {}
                    }
                    override fun audio(channel:Int,data:ByteArray,consumed:()->Unit) {
                        if(!ending && token==projectionGeneration) audio.offer(channel,data,consumed)
                    }
                    override fun audioStop(channel:Int) { if(!ending && token==projectionGeneration) audio.stop(channel) }
                    override fun ended(error:String?) {
                        ended(error,false)
                    }
                    override fun ended(error:String?,retryable:Boolean) {
                        if(ending) return
                        scope.launch { if(token==projectionGeneration) {
                            val pause=recovery.nextDelay(SystemClock.elapsedRealtime(),retryable)
                            val retry=wantProjection && pause!=null
                            stopProjection(error.orEmpty(),keepPanel=retry)
                            if(retry) {
                                mutable.update { it.copy(aa="Conexão interrompida · nova tentativa em ${pause/1000}s") }
                                recoveryJob=scope.launch { delay(pause!!); if(wantProjection && !ending && aa==null && !starting) startAuto(recovering=true) }
                            }
                        } }
                    }
                },microphoneForeground,phoneAudio=phoneOutput,
                    parkedInitially=controlPreferences.getBoolean(AaSensors.PARKED,false),
                    nightInitially=MapDayNight.current(controlPreferences)).also { it.start() }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e
            } catch(e:Exception) {
                if(token==projectionGeneration) stopProjection("Preparação do vídeo: ${e.message}")
            } finally { if(token==projectionGeneration) starting=false }
        }
    }
    /** Main-thread cleanup of projection only. The Wi-Fi request and its errors remain independent. */
    internal fun stopProjection(message:String,keepPanel:Boolean=false) {
        closeMenu()
        cancelPlayerShortcut()
        dash.track=DashTrack()
        latestNavigation=AaNavigation(); applyNavigation()
        projectionGeneration++; starting=false; videoReady=false
        aa?.close(); aa=null
        audio.close()
        if(keepPanel) bridge?.waiting()
        else { bridge?.close(); bridge=null; panelStop=scope.launch(Dispatchers.IO) { dash.disconnect() } }
        ConnectionDiagnostics.event("Android Auto encerrado: ${message.ifBlank { "sessão finalizada" }}")
        recordPower("Estado ao encerrar projeção")
        mutable.update { it.copy(aa="Android Auto desconectado",error=message,
            bike=if(wifi.state.value.status==WifiConnStatus.CONNECTED) "Wi-Fi conectado · aguardando imagem do Android Auto" else it.bike) }
    }
    private fun publishControls(message:String) { mutable.update { it.copy(controls=message) } }
    private fun saveControls() {
        getSharedPreferences("auto-media-buttons",MODE_PRIVATE).edit().clear().apply {
            for(action in TripperControls.Action.entries) controls.codeFor(action)?.let { putInt(action.name,it) }
        }.apply()
    }
    private fun sendControl(action:TripperControls.Action) {
        if(BikeInputCapture.blocksActions) { cancelButtonGesture(); closeMenu(); return }
        cancelPlayerShortcut()
        if(!controlPreferences.getBoolean("enabled",true)) { cancelButtonGesture(); closeMenu(); publishControls("Joystick desativado"); return }
        if(controlPreferences.getBoolean(MenuControls.ENABLED,false)) {
            cancelButtonGesture()
            MenuControls.direction(action)?.let(::sendMenuControl)
            return
        }
        val intercom=BikeControlLayout.active(controlPreferences)
        scheduleProfileReturn()
        fun command(double:Boolean)=BikeControlLayout.binding(controlPreferences,intercom,action,double)
        val now=SystemClock.elapsedRealtime()
        val previousDeadline=gestures.deadline
        val commands=gestures.pressBindings(action,now,command(false),command(true))
        ConnectionDiagnostics.event("Gesto: ${action.label}; ${if(previousDeadline!=null && gestures.deadline==null && commands.isNotEmpty()) "sequência concluída" else "toque recebido"}; ação=${commands.joinToString { it.command.label }.ifEmpty { "aguardando" }}")
        buttonJob?.cancel(); buttonJob=null
        commands.forEach(::executeBinding)
        gestures.deadline?.let { deadline ->
            publishControls("Solte e pressione novamente · até 3 s")
            panelFeedback("Aguardando 2º toque")
            buttonJob=scope.launch {
                delay((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if(!ending && !BikeInputCapture.blocksActions && controlPreferences.getBoolean("enabled",true)) {
                    val single=gestures.flushBindings(SystemClock.elapsedRealtime())
                    ConnectionDiagnostics.event("Gesto: prazo encerrado sem segundo toque; ação=${single.joinToString { it.command.label }.ifEmpty { "nenhuma" }}")
                    single.forEach(::executeBinding)
                    if(single.isEmpty()) publishControls("Sem segundo toque · nenhuma ação")
                }
            }
        }
        if(commands.isEmpty() && gestures.deadline==null) panelFeedback(action.label)
    }
    private fun syncMenuProfile() {
        if(controlPreferences.getInt(MenuControls.ACTIVE,1)!=menuSession.profile)
            controlPreferences.edit().putInt(MenuControls.ACTIVE,menuSession.profile).apply()
    }
    private fun scheduleProfileReturn() {
        profileIdleJob?.cancel(); profileIdleJob=null
        profileIdle.reset(SystemClock.elapsedRealtime(),
            controlPreferences.getBoolean(ProfileIdleReturn.KEY,false) && controlPreferences.getBoolean("enabled",true),
            controlPreferences.getBoolean(MenuControls.ENABLED,false),BikeControlLayout.active(controlPreferences))
        val deadline=profileIdle.deadline ?: return
        profileIdleJob=scope.launch {
            delay((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if(!ending && profileIdle.expired(SystemClock.elapsedRealtime()) && !BikeInputCapture.blocksActions) {
                cancelButtonGesture()
                controlPreferences.edit().putInt(BikeControlLayout.PROFILE,1).apply()
            }
        }
    }
    private fun closeMenu() { menuSession.close(); menuExpiry?.cancel(); menuExpiry=null; bridge?.showProfilePopup(null); syncMenuProfile() }
    private fun renderMenu() {
        val remaining=(menuSession.deadline ?: return)-SystemClock.elapsedRealtime()
        if(remaining<=0 || !controlPreferences.getBoolean(MenuControls.ENABLED,false)) { closeMenu(); return }
        bridge?.showProfilePopup(MenuControls.popup(this,menuSession.profile).copy(durationMs=remaining))
    }
    private fun scheduleMenuExpiry() {
        menuExpiry?.cancel(); menuExpiry=null
        val deadline=menuSession.deadline ?: return
        menuExpiry=scope.launch {
            delay((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if(menuSession.expire(SystemClock.elapsedRealtime())) { bridge?.showProfilePopup(null); syncMenuProfile() }
        }
    }
    private fun sendMenuControl(direction:MenuDirection) {
        val now=SystemClock.elapsedRealtime()
        if(menuSession.expire(now)) { bridge?.showProfilePopup(null); syncMenuProfile() }
        val result=menuSession.press(direction,now,MenuControls.opener(controlPreferences),MenuControls.cycle(controlPreferences))
        when(result.kind) {
            MenuSession.Kind.OPEN -> { syncMenuProfile(); renderMenu(); publishControls("Menu aberto") }
            MenuSession.Kind.PROFILE -> {
                controlPreferences.edit().putInt(MenuControls.ACTIVE,menuSession.profile).apply()
                val name=ProfileNames.label(this,true,menuSession.profile)
                panelFeedback(name); publishControls(name)
            }
            MenuSession.Kind.ACTION -> {
                if(result.bindingProfile!=0) bridge?.keepProfilePopup(menuSession.idleMs)
                executeBinding(MenuControls.binding(controlPreferences,result.bindingProfile,direction))
            }
        }
        scheduleMenuExpiry()
    }
    private fun cancelButtonGesture() { buttonJob?.cancel(); buttonJob=null; gestures.cancel(); cancelPlayerShortcut() }
    private fun cancelPlayerShortcut() { playerShortcutJob?.cancel(); playerShortcutJob=null; playerShortcut.cancel() }
    private fun openSpotifyPlayer() {
        val session=aa ?: return
        val video=bridge ?: return
        val token=playerShortcut.arm(SystemClock.elapsedRealtime())
        playerShortcutJob=scope.launch {
            delay(1400)
            repeat(4) {
                if(ending || aa!==session || bridge!==video) return@launch
                val frame=withTimeoutOrNull(800) {
                    suspendCancellableCoroutine<AaSourceFrame?> { continuation ->
                        video.sourceFrame { value -> if(continuation.isActive) continuation.resumeWith(Result.success(value)) }
                    }
                }
                val point=playerShortcut.observe(token,frame,SystemClock.elapsedRealtime())
                if(point!=null) {
                    val sent=session.tap(point.x,point.y)
                    ConnectionDiagnostics.event("Spotify: botão tocando agora reconhecido; toque=$sent")
                    return@launch
                }
                delay(350)
            }
            ConnectionDiagnostics.event("Spotify: sem botão confirmado; tela mantida")
        }
    }
    private fun openWhatsapp() {
        val session=aa ?: return publishControls("Android Auto indisponível")
        val video=bridge ?: return
        val icon=WhatsappIcon.load(this) ?: return publishControls("WhatsApp não instalado ou ícone indisponível")
        playerShortcutJob=scope.launch {
            var previous:WhatsappIcon.Point?=null
            // Only after this explicit command. Never scroll or touch a conversation.
            repeat(6) { attempt ->
                if(ending || aa!==session || bridge!==video) return@launch
                if(attempt==2) { session.key(187); previous=null; delay(700) }
                val frame=withTimeoutOrNull(800) { suspendCancellableCoroutine<AaSourceFrame?> { continuation ->
                    video.sourceFrame { if(continuation.isActive) continuation.resumeWith(Result.success(it)) }
                } }
                val point=frame?.let(icon::find)
                if(point!=null && previous?.let { kotlin.math.abs(it.x-point.x)<=4 && kotlin.math.abs(it.y-point.y)<=4 }==true) {
                    session.tap(point.x,point.y)
                    publishControls("WhatsApp selecionado no Android Auto")
                    return@launch
                }
                previous=point; delay(350)
            }
            publishControls("WhatsApp não encontrado na tela do Android Auto")
        }
    }
    private fun openOtherApplication(packageName:String?) {
        cancelPlayerShortcut()
        if(!CarApplications.validPackage(packageName)) { publishControls("Escolha o aplicativo em Controles da moto."); return }
        val session=aa ?: return publishControls("Android Auto indisponível")
        val video=bridge ?: return
        playerShortcutJob=scope.launch {
            val app=withContext(Dispatchers.IO) { CarApplications.installed(this@AutoBridgeService).firstOrNull { it.packageName==packageName } }
            if(app==null) { publishControls("Aplicativo não instalado ou incompatível com Android Auto"); return@launch }
            val icon=withContext(Dispatchers.Default) { CarAppIcon.load(this@AutoBridgeService,app.packageName) }
            if(icon==null) { publishControls("Não foi possível reconhecer o ícone do aplicativo"); return@launch }
            if(!session.key(187)) { publishControls("Android Auto indisponível"); return@launch }
            publishControls("Abrindo "+app.label)
            delay(900)
            repeat(6) { page ->
                var previous:CarAppIcon.Point?=null
                repeat(3) {
                    if(ending || aa!==session || bridge!==video) return@launch
                    val frame=withTimeoutOrNull(800) { suspendCancellableCoroutine<AaSourceFrame?> { continuation ->
                        video.sourceFrame { if(continuation.isActive) continuation.resumeWith(Result.success(it)) }
                    } }
                    val point=frame?.let { withContext(Dispatchers.Default) { icon.find(it) } }
                    if(point!=null && previous?.let { abs(it.x-point.x)<=4 && abs(it.y-point.y)<=4 }==true) {
                        if(session.tap(point.x,point.y)) {
                            publishControls(app.label+" selecionado no Android Auto"); panelFeedback(app.label)
                            if(app.packageName=="com.spotify.music") openSpotifyPlayer()
                        }
                        return@launch
                    }
                    previous=point; delay(250)
                }
                if(page<5) { if(!session.scrollApps()) return@launch; delay(650) }
            }
            publishControls("Aplicativo não encontrado. Confira se ele está visível no menu do Android Auto.")
        }
    }
    private fun armCalibration(action:TripperControls.Action?) {
        cancelButtonGesture(); calibrationJob?.cancel(); calibrationJob=null
        controls.arm(action)
        publishControls(if(action==null) "Calibração cancelada" else "Pressione na moto: ${action.label}")
        if(action!=null) calibrationJob=scope.launch {
            delay(10_000)
            if(controls.learning==action) {
                controls.arm(null)
                val message=if(action==TripperControls.Action.HOME) "Home: nenhum código próprio aprendido. O painel pode usar esse botão internamente; escolha outra direção." else "Nenhum comando aprendido. Confira a conexão com a moto."
                publishControls(message); ConnectionDiagnostics.event(message)
            }
        }
    }
    private fun updateControlLabel() {
        labelJob?.cancel()
        labelJob=null
        panelText(latestNavigation.panelTitle)
    }
    private fun panelText(label:String) {
        // Update the outside projection label and the inside media strip together.
        dash.updatePanelText(label)
    }
    private fun panelFeedback(label:String) {
        labelJob?.cancel(); panelText(label)
        labelJob=scope.launch { delay(3000); updateControlLabel() }
    }
    private fun executeControl(command:BikeCommand) {
        cancelPlayerShortcut()
        if(command==BikeCommand.TOGGLE_GPS_BUBBLE) {
            val hidden=!controlPreferences.getBoolean("clean-projection",true)
            controlPreferences.edit().putBoolean("clean-projection",hidden).apply()
            dash.cleanProjection=hidden
            val label=if(hidden) "Bolinha oculta" else "Bolinha visível"
            publishControls(label)
            panelFeedback(label)
            return
        }
        if(command==BikeCommand.SWITCH_MAPS_SPOTIFY) { executeControl(appToggle.next()); return }
        if(command==BikeCommand.OPEN_WHATSAPP) { openWhatsapp(); return }
        if(command==BikeCommand.PROFILE_NEXT) {
            val next=BikeControlLayout.active(controlPreferences)%3+1
            controlPreferences.edit().putInt(BikeControlLayout.PROFILE,next).apply()
            publishControls(ProfileNames.label(this,false,next))
            return
        }
        if(command.requestsBar) bridge?.showBar()
        if(command==BikeCommand.SHOW_BAR) {
            publishControls(if(bridge!=null) "Barra exibida nos modos sem menu" else "Inicie o Android Auto para mostrar a barra")
            panelFeedback(command.panelLabel); return
        }
        if(command.requestsVoice) {
            val now=SystemClock.elapsedRealtime()
            if(lastVoiceAt?.let { now-it<1500 }==true) return
            lastVoiceAt=now
        }
        try {
            val sent=command.sendToAndroidAuto({ aa?.key(it)==true },{ aa?.rotate(it)==true },{x,y->aa?.tap(x,y)==true})
            if(sent!=null) {
                publishControls(if(sent) "${command.panelLabel} enviado ao Android Auto" else "Android Auto indisponível")
                if(sent) appToggle.opened(command)
                if(sent && command==BikeCommand.OPEN_SPOTIFY) openSpotifyPlayer()
            } else publishControls(mediaControls.execute(if(command.requestsVoice) BikeCommand.VOICE else command))
            panelFeedback(command.panelLabel)
        }
        catch(e:Exception) { ConnectionDiagnostics.event("Controle de mídia: ${e.javaClass.simpleName}"); publishControls("Não foi possível enviar o comando ao reprodutor do celular") }
    }
    private fun executeBinding(binding:BikeBinding) {
        if(binding.command==BikeCommand.OPEN_OTHER) openOtherApplication(binding.appPackage)
        else executeControl(binding.command)
    }
    private fun powerState():String {
        val pm=getSystemService(PowerManager::class.java)
        // AA's virtual display can keep the system interactive while the phone panel is OFF.
        val display=getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val screen=when(display?.state) {
            android.view.Display.STATE_ON -> "acesa"
            android.view.Display.STATE_OFF -> "apagada"
            android.view.Display.STATE_DOZE,android.view.Display.STATE_DOZE_SUSPEND -> "ambiente"
            else -> "desconhecida"
        }
        val debugging=android.provider.Settings.Global.getInt(contentResolver,android.provider.Settings.Global.ADB_ENABLED,0)==1
        return "Tela principal $screen; sistema interativo=${pm.isInteractive}; bloqueio=${getSystemService(KeyguardManager::class.java).isKeyguardLocked}; depuração USB=$debugging; economia=${pm.isPowerSaveMode}; suspensão=${pm.isDeviceIdleMode}; bateria liberada=${pm.isIgnoringBatteryOptimizations(packageName)}; CPU=${wake?.isHeld==true}; Wi-Fi lock=${wifiLock?.isHeld==true}"
    }
    private fun recordPower(event:String) {
        val text=powerState()
        GuidedDiagnostics.event("ENERGIA $event · $text")
        mutable.update { it.copy(screen=text) }; ConnectionDiagnostics.event("$event · $text")
    }
    private fun disconnectPanel() {
        val previous=panelStop
        panelStop=scope.launch(Dispatchers.IO) { previous?.join(); dash.disconnect() }
    }
    private fun connectPanelIfReady() {
        scope.launch {
            panelStop?.join()
            if(ending || !wantBike || !videoReady || wifi.state.value.status!=WifiConnStatus.CONNECTED) return@launch
            if(dash.state.value !in listOf(DashState.IDLE,DashState.ERROR)) return@launch
            dash.cleanProjection=getSharedPreferences("auto-controls-options",MODE_PRIVATE).getBoolean("clean-projection",true)
            updateControlLabel()
            mutable.update { it.copy(bike="Wi-Fi conectado · autenticando painel",bikeError="") }
            val selected=wifi.network ?: return@launch
            applyNavigation()
            dash.connect(wifi.state.value.ssid,selected)
        }
    }
    private fun applyNavigation() {
        val nav=latestNavigation
        val (distance,unit)=AaNavigationStatus.distance(nav.distanceMeters)
        val (remaining,remainingUnit)=AaNavigationStatus.destinationDistance(nav.destinationMeters,nav.active)
        dash.setProjectionGuidance(nav.active,nav.glyph,distance,unit,remaining,remainingUnit)
        if(labelJob?.isActive!=true) updateControlLabel()
    }
    private fun foreground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("auto","Android Auto no painel",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,AutoActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,0,Intent(this,AutoBridgeService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"auto").setSmallIcon(R.drawable.ic_route).setContentTitle("Rota Auto · ponte ativa")
            .setContentText("Conexão ativa · veja o estado no aplicativo").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null,"Parar",stop).build()).build()
        val plain=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if(!audioConfigured) {
            phoneMicrophone=PhoneAudio.input(this)
            phoneOutput=PhoneAudio.output(this)
            audioConfigured=true
        }
        val granted=!phoneMicrophone && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            startForeground(202,notification,plain or if(granted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            microphoneForeground=granted
        } catch(_:SecurityException) {
            startForeground(202,notification,plain); microphoneForeground=false
            ConnectionDiagnostics.event("Abra o aplicativo e reinicie a ponte para habilitar o microfone em segundo plano")
        }
    }
    private fun locks() {
        if(wake!=null) return
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"rotaauto:bridge").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock=applicationContext.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"rotaauto:wifi").apply { setReferenceCounted(false); acquire() }
        // The AA media source runs on this same phone and owns audio focus. Taking it here
        // steals focus from the source; sending PAUSE on loss then pauses our own upstream.
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy() {
        GuidedDiagnostics.finish("STOP · serviço encerrado")
        BikeInputCapture.stop("STOP · serviço encerrado")
        widgetStatus.close(); panelRecoveryJob?.cancel()
        playbackMonitor.close()
        closeMenu()
        cancelButtonGesture()
        calibrationJob?.cancel()
        controlPreferences.unregisterOnSharedPreferenceChangeListener(controlPreferenceListener)
        ending=true; wantProjection=false; recoveryJob?.cancel(); instance=null
        runCatching { unregisterReceiver(powerReceiver) }
        contentResolver.unregisterContentObserver(debuggingObserver)
        aa?.close(); bridge?.close(); wifi.disconnect()
        audio.close()
        // Finish socket/audio cleanup before cancelling this service's scope.
        scope.launch(Dispatchers.IO) {
            try {
                dash.disconnect()
            } finally { scope.cancel() }
        }
        if(wake?.isHeld==true) wake?.release(); if(wifiLock?.isHeld==true) wifiLock?.release()
        mutable.update { it.copy(active=false,aa="Android Auto parado",bike="Moto desconectada") }
        super.onDestroy()
    }
}
