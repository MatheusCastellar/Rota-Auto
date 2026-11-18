package com.example.opendash

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.location.Location
import android.net.wifi.WifiManager
import android.os.*
import com.example.opendash.dash.*
import com.example.opendash.dash.map.*
import com.example.opendash.dash.nav.*
import com.example.opendash.dash.video.*
import com.example.opendash.net.Internet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.Executors

data class RideUi(
    val active: Boolean = false, val stage: String = "Pronto para sair",
    val message: String = "Conecte sua moto para começar.", val frames: Long = 0,
    val gps: String = "Aguardando GPS", val instruction: String = "", val distance: String = "",
    val destination: String = "Mapa livre", val codec: String = "", val battery: Int = 0,
)

/** The service owns GPS, encoder, Wi-Fi and routing; no Activity/ViewModel owns the ride. */
class RideService : Service() {
    companion object {
        const val START = "rota.START"; const val STOP = "rota.STOP"
        const val DESTINATION = "rota.DESTINATION"; const val CLEAR = "rota.CLEAR"
        const val OPTIONS = "rota.OPTIONS"
        private val mutableUi = MutableStateFlow(RideUi())
        val ui = mutableUi.asStateFlow()
        fun command(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, RideService::class.java).setAction(action).apply(configure)
            if (action == STOP) context.stopService(intent)
            else context.startForegroundService(intent)
        }
        fun distance(m: Double) = if(m >= 1000) String.format(Locale.forLanguageTag("pt-BR"),"%.1f km",m/1000) else "${m.toInt()} m"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val videoDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r,"rota-video") }.asCoroutineDispatcher()
    private lateinit var wifi: DashWifiManager
    private lateinit var session: DashSession
    private lateinit var gps: LocationTracker
    private lateinit var renderer: MapRenderer
    private lateinit var voice: VoiceManager
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var stream: Job? = null
    private var routeJob: Job? = null
    private var retry: Job? = null
    private val connectionMutex = Mutex()
    private var requested = false
    @Volatile private var target: GeoPoint? = null
    private var targetName = "Mapa livre"
    @Volatile private var route: Route? = null
    @Volatile private var progress: NavEngine.Progress? = null
    private var offSince = 0L
    private var lastRouteAttempt = -30_000L
    @Volatile private var lastFix: Location? = null
    private var routeGeneration = 0
    private var sessionAttempts = 0
    @Volatile private var frame: MapRenderer.Frame? = null
    @Volatile private var economy = false
    @Volatile private var night = false
    @Volatile private var wallpaperEnabled = false
    @Volatile private var wallpaperFill = false
    @Volatile private var wallpaperRevision = 0L
    @Volatile private var zoom = 16
    private val prefs by lazy { getSharedPreferences("rota",MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate(); Internet.init(this)
        wifi=DashWifiManager(this,scope); session=DashSession(scope); gps=LocationTracker(this)
        renderer=MapRenderer(TileProvider(this,scope)); voice=VoiceManager.get(this)
        loadOptions()
        wifi.onSsidResolved = { ssid -> if(ssid.startsWith("RE_") && ssid.length>3) DashConfig.get(this).ssid=ssid }
        session.onButton = { code -> scope.launch {
            when(code.toInt() and 255) { 0x14 -> zoom=(zoom+1).coerceAtMost(19); 0x13 -> zoom=(zoom-1).coerceAtLeast(12) }
        } }
        session.onError = { scope.launch { reconnectSession() } }
        scope.launch { wifi.state.collect { state ->
            if (!requested) return@collect
            ConnectionDiagnostics.event("Wi-Fi: ${state.status}")
            when(state.status) {
                WifiConnStatus.CONNECTED -> connectionMutex.withLock {
                    if (session.state.value == DashState.IDLE || session.state.value == DashState.ERROR) {
                        mutableUi.update { it.copy(stage="Autenticando no painel",message="Wi-Fi conectado. Aguarde o painel.") }
                        session.connect(state.ssid,wifi.network)
                    }
                }
                WifiConnStatus.REQUESTING -> {
                    stopPipeline()
                    mutableUi.update { it.copy(stage="Conectando ao Wi-Fi",message="Aceite a conexão do Android. A rede da moto não tem internet.") }
                }
                WifiConnStatus.ERROR -> {
                    mutableUi.update { it.copy(stage="Conexão interrompida",message=state.error ?: "Ative o Wi-Fi da moto, permita localização precisa e tente novamente.") }
                    stopSelf()
                }
                else -> Unit
            }
        } }
        scope.launch { session.state.collect { state ->
            if (!requested) return@collect
            when(state) {
                DashState.READY -> { sessionAttempts=0; startVideo() }
                DashState.ERROR -> reconnectSession()
                else -> Unit
            }
        } }
        scope.launch { gps.location.collect { loc -> if(loc != null) onFix(loc) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if(intent == null) { stopSelf(); return START_NOT_STICKY }
        try {
            ensureForeground()
            if(intent.action == DESTINATION) {
                val lat=intent.getDoubleExtra("lat",Double.NaN); val lng=intent.getDoubleExtra("lng",Double.NaN)
                if(lat in -85.0..85.0 && lng in -180.0..180.0) {
                    routeGeneration++; routeJob?.cancel(); routeJob=null
                    target=GeoPoint(lat,lng); targetName=intent.getStringExtra("name")?.take(100) ?: "Destino"
                    route=null; progress=null; offSince=0; lastRouteAttempt=-30_000; voice.resetTrip()
                    mutableUi.update { it.copy(destination=targetName,instruction="Aguardando GPS para calcular a rota") }
                }
            }
            if(intent.action == CLEAR) {
                routeGeneration++; routeJob?.cancel(); routeJob=null; target=null; route=null; progress=null; voice.resetTrip()
                targetName="Mapa livre"; mutableUi.update { it.copy(destination=targetName,instruction="",distance="") }
            }
            loadOptions()
            if(!requested) {
                ConnectionDiagnostics.reset()
                requested=true; acquireLocks(); gps.start()
                mutableUi.update { it.copy(active=true,message="Preparando conexão") }
                val config=DashConfig.get(this)
                wifi.connect(config.ssid.ifBlank { "RE_" },DashConfig.DEFAULT_PASSWORD,config.ssid.isBlank())
            }
            lastFix?.let(::onFix)
        } catch (_: Exception) {
            mutableUi.update { it.copy(stage="Não foi possível iniciar",message="Confira as permissões de localização, o GPS e os dados da moto.") }
            stopSelf()
        }
        // Never restart only a notification after process death; require an explicit new ride.
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadOptions() {
        economy=prefs.getBoolean("economy",false); night=prefs.getBoolean("night",false)
        wallpaperEnabled=prefs.getBoolean("wallpaper_enabled",false)
        wallpaperFill=prefs.getBoolean("wallpaper_fill",false)
        wallpaperRevision=prefs.getLong("wallpaper_revision",0)
        voice.setMode(if(prefs.getBoolean("voice",true)) VoiceMode.FULL else VoiceMode.OFF)
    }
    private fun validFix(loc: Location): Boolean = loc.hasAccuracy() && loc.accuracy<=50 &&
        (SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos) in 0..10_000_000_000L

    private fun onFix(loc: Location) {
        lastFix=loc
        val good=validFix(loc)
        mutableUi.update { it.copy(gps=if(good) "GPS ±${loc.accuracy.toInt()} m" else "GPS fraco · aguardando posição") }
        if(!good) return
        val pos=GeoPoint(loc.latitude,loc.longitude)
        if(target != null && route == null) fetchRoute(pos)
        route?.let { r ->
            val p=NavEngine.progress(r,pos,loc.speed); progress=p
            mutableUi.update { it.copy(instruction=if(p.arrived) "Você chegou ao destino" else p.nextManeuver?.instruction ?: "Siga pela rota",distance=distance(p.remainingMeters)) }
            if(!p.offRoute) voice.maybeAnnounce(p.nextManeuver,p.distanceToManeuverM,p.remainingMeters)
            val now=SystemClock.elapsedRealtime()
            if(p.offRoute) {
                if(offSince==0L) offSince=now
                if(now-offSince>=5_000) fetchRoute(pos)
            } else offSince=0
        }
    }

    private fun fetchRoute(from: GeoPoint) {
        val to=target ?: return
        val now=SystemClock.elapsedRealtime()
        if(routeJob?.isActive==true || now-lastRouteAttempt<20_000) return
        lastRouteAttempt=now; val generation=routeGeneration
        mutableUi.update { it.copy(message="Calculando rota pela internet…") }
        routeJob=scope.launch {
            val result=Router.route(from,to)
            if(generation!=routeGeneration) return@launch
            if(result==null) {
                mutableUi.update { it.copy(message="Não foi possível calcular. Confira os dados móveis. Nova tentativa em 20 s.") }
            } else {
                route=result; voice.resetTrip(); offSince=0
                mutableUi.update { it.copy(message="Rota pronta. Confira a imagem no painel.") }
                lastFix?.let(::onFix)
            }
        }
    }

    private suspend fun reconnectSession() {
        if(!requested || retry?.isActive==true) return
        if(sessionAttempts>=3) {
            mutableUi.update { it.copy(stage="Painel não respondeu",message="Feche o app oficial, confira o Wi-Fi e tente conectar novamente.") }
            stopSelf(); return
        }
        retry=scope.launch {
            sessionAttempts++
            stopPipeline()
            mutableUi.update { it.copy(stage="Reconectando ao painel",message="Tentativa $sessionAttempts de 3") }
            delay(2000L*sessionAttempts)
            if(requested && wifi.state.value.status==WifiConnStatus.CONNECTED)
                session.connect(DashConfig.get(this@RideService).ssid,wifi.network)
        }
    }

    private suspend fun stopPipeline() {
        stream?.cancelAndJoin(); stream=null
        withContext(Dispatchers.IO) { session.disconnect() }
    }

    private fun startVideo() {
        if(stream?.isActive==true) return
        // Own text and arrows are rendered into video; don't send guessed maneuver codes.
        session.startStreaming()
        stream=scope.launch(videoDispatcher) {
            val packetizer=RtpPacketizer(session::sendRtp)
            val nals=NalProcessor { bytes,_ -> packetizer.packetize(bytes,true,SystemClock.elapsedRealtime()) }
            val encoder=DashEncoder { bytes, _ -> nals.process(bytes) }
            val bitmap=Bitmap.createBitmap(526,300,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)
            val wallpaperStore=WallpaperStore(this@RideService)
            val photoPaint=Paint(Paint.FILTER_BITMAP_FLAG)
            var photo: Bitmap?=null
            var loadedRevision=-1L
            var frames=0L
            try {
                encoder.prepare()
                ConnectionDiagnostics.event("Codificador iniciado: ${encoder.encoderName}")
                val start=SystemClock.elapsedRealtime()
                mutableUi.update { it.copy(stage="Enviando vídeo · aguardando painel",message="Confira se a imagem aparece no painel.",codec=encoder.encoderName) }
                while(isActive) {
                    if(loadedRevision!=wallpaperRevision) {
                        loadedRevision=wallpaperRevision
                        photo?.recycle(); photo=wallpaperStore.load()
                    }
                    val loc=lastFix
                    val good=loc!=null && validFix(loc)
                    val p=progress
                    val showingWallpaper=WallpaperLayout.shouldShow(target!=null,wallpaperEnabled,photo!=null)
                    if(showingWallpaper) {
                        WallpaperLayout.draw(canvas,photo!!,wallpaperFill,photoPaint)
                    } else if(loc!=null) {
                        val f=MapRenderer.Frame(loc.latitude,loc.longitude,if(loc.hasBearing()) loc.bearing else 0f,zoom,
                            route?.geometry ?: emptyList(),
                            if(!good) "Aguardando GPS" else if(p?.arrived==true) "Você chegou!" else if(p?.offRoute==true) "Recalculando rota" else p?.nextManeuver?.instruction ?: if(target!=null) "Calculando rota…" else "Mapa livre",
                            if(p!=null) "${distance(p.distanceToManeuverM)} · faltam ${distance(p.remainingMeters)}" else "Rota Livre · tela apagada",good,night)
                        frame=f; renderer.draw(canvas,f)
                    } else {
                        canvas.drawColor(Color.rgb(17,29,22))
                        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=24f; textAlign=Paint.Align.CENTER }
                        canvas.drawText("Aguardando GPS",263f,150f,paint)
                    }
                    encoder.renderFrame { it.drawBitmap(bitmap,0f,0f,null) }; encoder.drain(); frames++
                    if(frames%4==0L) {
                        val now=SystemClock.elapsedRealtime()
                        val acknowledged=ConnectionDiagnostics.lastDecoded>0 && now-ConnectionDiagnostics.lastDecoded<10_000
                        mutableUi.update { it.copy(frames=frames,
                            stage=if(acknowledged) "Painel respondeu ao vídeo" else if(now-start>15_000) "Vídeo sem confirmação do painel" else "Enviando vídeo · aguardando painel",
                            message=if(acknowledged) "Confira a imagem no painel antes de apagar a tela." else if(now-start>15_000) "Wi-Fi conectado, mas o vídeo ainda não foi confirmado. Abra Diagnóstico da conexão." else "Aguardando resposta de vídeo da moto.",
                            gps=if(good) "GPS ±${loc!!.accuracy.toInt()} m" else "GPS fraco · aguardando posição") }
                    }
                    val thermal=getSystemService(PowerManager::class.java).currentThermalStatus
                    delay(if(showingWallpaper || economy || !good || (loc?.speed ?: 0f)<1 || thermal>=PowerManager.THERMAL_STATUS_MODERATE) 500L else 250L)
                }
            } catch(e: CancellationException) { throw e }
            catch (e: Exception) {
                ConnectionDiagnostics.event("Falha no vídeo: ${e.javaClass.simpleName}")
                mutableUi.update { it.copy(stage="Falha na transmissão",message="O envio de vídeo falhou. Abra Diagnóstico da conexão.") }
                scope.launch { stopSelf() }
            } finally { encoder.release(); bitmap.recycle(); photo?.recycle() }
        }
    }

    private fun ensureForeground() {
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ride","Navegação no painel",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,RideService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"ride").setSmallIcon(R.drawable.ic_route)
            .setContentTitle("Rota Livre · viagem em andamento").setContentText("GPS e conexão ativos. A tela pode ficar apagada.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Parar",stop).build()).build()
        startForeground(101,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }
    private fun acquireLocks() {
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"rotalivre:ride").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        val wm=applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"rotalivre:ride").apply { setReferenceCounted(false); acquire() }
    }
    override fun onDestroy() {
        requested=false; wifi.disconnect(); gps.stop(); routeJob?.cancel(); retry?.cancel()
        val video=stream; stream=null
        scope.launch {
            video?.cancelAndJoin()
            withContext(Dispatchers.IO) { session.disconnect() }
            if(wake?.isHeld==true) wake?.release()
            if(wifiLock?.isHeld==true) wifiLock?.release()
            voice.shutdown(); videoDispatcher.close()
            mutableUi.update { it.copy(active=false,stage=if(it.stage.startsWith("Transmitindo")) "Viagem encerrada" else it.stage) }
            scope.cancel()
        }
        super.onDestroy()
    }
}
