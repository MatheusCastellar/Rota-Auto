// Modified for Rota Livre: cancellation propagates instead of triggering a spurious reconnect.
package com.example.opendash.dash

import com.example.opendash.dash.protocol.DashCommands
import com.example.opendash.dash.protocol.K1GPacket
import com.example.opendash.util.DebugLog
import com.example.opendash.ConnectionDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * Tripper Dash session, sequenced to match better-dash (tripper_app_like_nav.py):
 *   1. Open sockets (RX :2002 bound first).
 *   2. Send initial burst on :2000 (includes q3c.e request-auth).
 *   3. RX loop ingests 07 00 / 07 03 → sends q3c.d → waits for 07 01 01.
 *   4. Nav entry: route-card ×4 → projectionFrame → z2 (once) → route-card.
 *   5. Start RTP + 4 Hz projection heartbeat + 1 Hz route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, 09 06 IDR-decoded acks,
 * and 09 00 button events.
 */
class DashSession internal constructor(private val scope: CoroutineScope,
    private val transportFactory:(android.net.Network?)->DashTransport = { DashSocket(it) }) {
    companion object {
        private const val TAG           = "DashSession"
        private const val AUTH_TIMEOUT  = 15_000L
        private const val BURST_PAUSE   = 20L
        private const val PROJ_HB_MS     = 250L   // 4 Hz
        private const val ROUTE_CARD_MS  = 1_000L // 1 Hz keep-alive
        private const val HOSTNAME       = "Celular"
    }

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    @Volatile private var socket: DashTransport? = null
    @Volatile private var sessionGeneration=0
    private var selectedNetwork:android.net.Network?=null
    var onTransportError:((android.net.Network?)->Unit)?=null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0

    var onButton: ((Byte) -> Unit)? = null
    var onButtonPacket: ((ByteArray) -> Unit)? = null
    var onRawPacket: ((ByteArray,String) -> Unit)? = null
    var onSentPacket: ((ByteArray) -> Unit)? = null
    var onCaptureNote: ((String) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    @Volatile var destinationName: String = "Android Auto"
    /** Native context stays active, but some firmware suppresses inputs with hidden glyphs. */
    @Volatile var cleanProjection: Boolean = false
        set(value) {
            if(field==value) return
            field=value
            onCaptureNote?.invoke("Ocultar bola: $value")
            refreshRouteCard()
        }

    /** Temporary developer override; never written to user preferences. */
    @Volatile var testManeuver:Int?=null
        set(value) {
            require(value==null || value in 0..255)
            if(field==value) return
            field=value
            onCaptureNote?.invoke("Símbolo solicitado: ${value ?: "normal"}; estado=${_state.value}")
            refreshRouteCard()
        }
    private fun refreshRouteCard() {
        val current=socket ?: return
        scope.launch(Dispatchers.IO) {
            if(current===socket && navChromeEnabled && _state.value in setOf(DashState.READY,DashState.STREAMING)) {
                send(current,liveRouteCard(true))
                onCaptureNote?.invoke("Cartão enviado: símbolo=${visibleManeuver()}; ocultar=$cleanProjection; teste=$testManeuver")
            }
        }
    }

    private var sessionJob: Job? = null
    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navInfoJob: Job? = null
    private var mediaInfoJob: Job? = null

    @Volatile private var mediaCard: DashMediaCard? = null
    @Volatile var musicPlaying:Boolean=false
    @Volatile var track=DashTrack()
    @Volatile var projectionLabel: String? = null
    @Volatile private var callerName: String? = null

    fun updateNowPlaying(title: String?, album: String, artist: String) {
        mediaCard = title?.takeIf { it.isNotBlank() || album.isNotBlank() || artist.isNotBlank() }?.let { DashMediaCard(it,album,artist) }
    }

    fun updatePanelText(label:String?) {
        projectionLabel=label?.takeIf { it.isNotBlank() }
        mediaCard=label?.takeIf { it.isNotBlank() }?.let(DashMediaCard::panelLabel)
    }

    fun updateCall(caller: String?) {
        callerName = caller?.takeIf { it.isNotBlank() }
    }

    // Live nav-info pushed to the dash bubble at ~1 Hz (set by NavEngine output).
    @Volatile private var navManeuver = DashCommands.NAV_MANEUVER_CONTINUE
    @Volatile private var navPrimaryDist = 0
    @Volatile private var navPrimaryUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navTotalDist = 0
    @Volatile private var navTotalUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navEta: String? = null
    @Volatile private var navActive = false
    @Volatile private var navChromeEnabled = false

    /** Push the latest turn-by-turn figures; sent to the dash at 1 Hz. */
    fun updateNavInfo(
        maneuver: Int, primaryDist: Int, primaryUnit: Int,
        totalDist: Int, totalUnit: Int, etaHHMM: String? = null,
    ) {
        navManeuver = maneuver
        navPrimaryDist = primaryDist
        navPrimaryUnit = primaryUnit
        navTotalDist = totalDist
        navTotalUnit = totalUnit
        navEta = etaHHMM
        navActive = true
        navChromeEnabled = true
    }

    /**
     * Route card with the LIVE nav figures patched in. The template's captured
     * values (7.9 km / glyph 0x3C / ETA 03:03) must never reach the dash once
     * real guidance is running — the card repeats at 1 Hz and would stomp the
     * activeNavPacket numbers every second.
     */
    private fun visibleManeuver()=testManeuver ?: when {
        cleanProjection -> DashCommands.NAV_MANEUVER_HIDDEN
        navActive && navManeuver in 0x00..0x59 -> navManeuver
        else -> DashCommands.NAV_MANEUVER_NEUTRAL
    }
    private fun liveRouteCard(projectionOn:Boolean):ByteArray = DashCommands.routeCard(
        projectionLabel ?: destinationName, projectionOn, maneuver=visibleManeuver(),
        primaryDist=if(navActive && !cleanProjection) navPrimaryDist else 0,
        primaryUnit=if(navActive && !cleanProjection) navPrimaryUnit else 0,
        totalDist=if(navActive) navTotalDist else 0,totalUnit=if(navActive) navTotalUnit else DashCommands.NAV_UNIT_METERS,
        etaHHMM=navEta ?: "    ")

    fun setProjectionGuidance(active:Boolean,glyph:Int,distance:Int,unit:Int,totalDistance:Int=0,totalUnit:Int=DashCommands.NAV_UNIT_METERS) {
        navManeuver=glyph; navPrimaryDist=distance; navPrimaryUnit=unit
        navTotalDist=if(active) totalDistance.coerceIn(0,65535) else 0
        navTotalUnit=if(active && totalUnit in setOf(0,DashCommands.NAV_UNIT_METERS,DashCommands.NAV_UNIT_KM_TENTHS)) totalUnit else DashCommands.NAV_UNIT_METERS
        navEta=null
        navActive=active
    }

    // ── Public API ────────────────────────────────────────────────────────

    @Synchronized fun connect(ssid: String, network: android.net.Network? = null) {
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        val token=++sessionGeneration
        selectedNetwork=network
        _state.value = DashState.CONNECTING
        ConnectionDiagnostics.event("Abrindo comunicação com o painel")
        DebugLog.i(TAG) { "connect() — ssid='$ssid' network=$network" }
        sessionJob = scope.launch(Dispatchers.IO) { runSession(ssid, network, token) }
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        _state.value = DashState.STREAMING
        // Projection heartbeat is always needed. Route-card/nav-info keepalives are
        // only for active navigation; idle wallpaper mode should stay chrome-free.
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchMediaInfo()
    }

    fun sendRtp(packet: ByteArray) {
        val current=socket ?: return
        if(_state.value!=DashState.STREAMING) return
        try { current.sendRtp(packet) }
        catch(e:java.io.IOException) { transportFailed(current,e) }
    }

    private fun send(current:DashTransport?,packet:ByteArray) {
        if(current==null || current!==socket) return
        try { current.send(packet); onSentPacket?.invoke(packet) }
        catch(e:java.io.IOException) { transportFailed(current,e) }
    }
    private fun transportFailed(current:DashTransport,error:java.io.IOException) {
        // Network failures belong to the bike, never to AA's codec callback.
        scope.launch {
            if(current!==socket) return@launch
            val failedNetwork=selectedNetwork
            fail("Transporte do painel: "+error.javaClass.simpleName,current)
            onTransportError?.invoke(failedNetwork)
        }
    }

    fun updateRouteCard(name: String) {
        destinationName = name.ifBlank { "Android Auto" }
        navActive = false   // new destination — old figures are stale until the next updateNavInfo
        navChromeEnabled = name.isNotBlank()
        if (navChromeEnabled && (_state.value == DashState.READY || _state.value == DashState.STREAMING)) {
            scope.launch(Dispatchers.IO) {
                send(socket,liveRouteCard(projectionOn = true))
            }
        } else if (_state.value == DashState.READY || _state.value == DashState.STREAMING) {
            scope.launch(Dispatchers.IO) {
                send(socket,DashCommands.projectionStop())
                delay(40)
                send(socket,DashCommands.projectionOff())
                delay(40)
                send(socket,DashCommands.projectionFrame())
                delay(40)
                send(socket,DashCommands.projectionOn())
            }
        }
    }

    @Synchronized fun disconnect() {
        sessionGeneration++
        // Cancel the session coroutine FIRST so it can't race past auth and flip state to
        // READY after we tear down (which would re-trigger streaming on a dead socket).
        sessionJob?.cancel(); sessionJob = null
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel()
        navInfoJob?.cancel(); mediaInfoJob?.cancel()
        navActive = false
        navChromeEnabled = false
        socket?.let {
            runCatching { it.send(DashCommands.musicActive(false)) }
            runCatching { it.send(DashCommands.projectionStop()) }
            runCatching { it.send(DashCommands.projectionOff()) }
            it.close()
        }
        socket = null
        _state.value = DashState.IDLE
        DebugLog.i(TAG) { "Disconnected" }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun runSession(ssid: String, network: android.net.Network?, token:Int) {
        try {
            val sock = try {
                transportFactory(network).also {
                    synchronized(this) {
                        if(token!=sessionGeneration) { it.close(); throw CancellationException() }
                        socket=it
                    }
                }
            } catch (e: java.net.BindException) {
                if(token==sessionGeneration) fail("Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            ConnectionDiagnostics.authenticated=false
            ConnectionDiagnostics.lastDecoded=0
            authConfirmed = false
            authRetries = 0

            // RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(sock)
            // 1 Hz status heartbeat throughout the session.
            launchStatusHeartbeat(sock)

            _state.value = DashState.AUTHENTICATING
            DebugLog.i(TAG) { "Sending initial burst…" }
            for (pkt in DashCommands.initialBurst(HOSTNAME)) {
                send(sock,pkt)
                delay(BURST_PAUSE)
            }

            DebugLog.i(TAG) { "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…" }
            val sessionAuth = auth ?: return
            var silentRetries = 0
            val authenticated = awaitDashAuthentication(
                timeoutMs = AUTH_TIMEOUT,
                now = { android.os.SystemClock.elapsedRealtime() },
                pause = { delay(it) },
                current = { token == sessionGeneration && sock === socket },
                confirmed = { authConfirmed },
                retry = {
                    if (!authConfirmed && token == sessionGeneration && sock === socket) {
                        send(sock, sessionAuth.retryPacket())
                        silentRetries++
                        ConnectionDiagnostics.event("Repetindo autenticação no painel · tentativa $silentRetries")
                    }
                },
            )
            if (token != sessionGeneration || sock !== socket) return
            if (!authenticated) {
                fail("Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.")
                return
            }
            DebugLog.i(TAG) { "Authenticated ✓" }

            // Keep native navigation active for outside-map inputs. Hide only its glyph.
            navChromeEnabled=true
            ConnectionDiagnostics.event(if(cleanProjection) "Navegação nativa ativa com símbolo oculto" else "Orientações do Android Auto no painel")
            enterNavMode(sock)
            synchronized(this) {
                if(token!=sessionGeneration || sock!==socket) return
                _state.value = DashState.READY
            }
            DebugLog.i(TAG) { "READY ✓" }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, { "Session error" }, e)
            if(token==sessionGeneration) {
                val failedNetwork=selectedNetwork
                fail(e.javaClass.simpleName+": "+e.message)
                if(e is java.io.IOException) scope.launch { onTransportError?.invoke(failedNetwork) }
            }
        }
    }

    /**
     * Nav entry in the exact phone order (nav_open_ok.pcap):
     *   route-card ×4 (establishes destination) → projectionFrame
     *   → z2 once → route-card confirmation.
     */
    private suspend fun enterNavMode(sock: DashTransport) {
        send(sock,DashCommands.navContext()); delay(40)
        send(sock,DashCommands.emptyLists()); delay(40)

        repeat(4) {
            send(sock,liveRouteCard(projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        send(sock,DashCommands.projectionFrame()); delay(60)
        send(sock,DashCommands.navPlaceholder()); delay(10)
        send(sock,DashCommands.navStart()); delay(40)                 // z2, ONCE
        send(sock,liveRouteCard(projectionOn = true))
        DebugLog.i(TAG) { "Nav mode kick sent" }
    }

    /**
     * Idle wallpaper mode: open projection without route-card/nav-start chrome.
     * Active navigation still uses [enterNavMode] unchanged.
     */
    private suspend fun enterIdleProjectionMode(sock: DashTransport) {
        send(sock,DashCommands.projectionFrame()); delay(60)
        send(sock,DashCommands.projectionOn()); delay(40)
        projectionLabel?.let { send(sock,DashCommands.projectionLabel(it)) }
        DebugLog.i(TAG) { "Idle projection kick sent" }
    }

    private fun launchReceiveLoop(sock: DashTransport) {
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = try {
                    sock.receive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Link dropped (EBADF/ENETUNREACH) — end the loop cleanly instead of
                    // crashing the app; DashWifiManager handles reconnect.
                    DebugLog.w(TAG) { "RX loop stopped — socket error: ${e.message}" }
                    if(e is java.io.IOException) transportFailed(sock,e)
                    else if(sock===socket) fail("Lost connection to dash",sock)
                    break
                } ?: continue
                if(sock===socket) dispatchIncoming(pkt, sock)
            }
        }
    }

    private fun dispatchIncoming(pkt: ByteArray, sock: DashTransport) {
        onRawPacket?.invoke(pkt,sock.receiveSource)
        val tlvs = K1GPacket.parseIncoming(pkt)
        // Dump the full raw packet for anything that ISN'T just the per-frame decode
        // acks (09 06 55 / 09 04 55) — those fire ~8×/s and would drown the log. This
        // captures joystick events, telemetry, and any unknown TLV in full hex so a
        // single `adb logcat -s DashSession` session is enough to reverse the protocol.
        val onlyAcks = tlvs.isNotEmpty() && tlvs.all {
            it.type == 0x09 && (it.sub == 0x06 || it.sub == 0x04) &&
                it.value.firstOrNull()?.toInt() == 0x55
        }
        if (!onlyAcks) DebugLog.i(TAG) { "RX RAW (${pkt.size}B): ${pkt.toHexFull()}" }
        for (tlv in tlvs) {
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        DebugLog.i(TAG) { "Got RSA pubkey — sending q3c.d" }
                        send(sock,ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true; ConnectionDiagnostics.authenticated=true; ConnectionDiagnostics.event("Autenticação confirmada pelo painel") }
                    AuthEvent.Rejected -> {
                        ConnectionDiagnostics.event("Painel recusou autenticação")
                        authRetries++
                        DebugLog.w(TAG) { "Auth rejected — retry #$authRetries" }
                        auth?.reset()
                        if (authRetries <= 5) send(sock,DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── 09 06 55: per-IDR frame-decoded notify → mandatory q3c.L2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x06 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                send(sock,DashCommands.frameDecodedIdr())
                ConnectionDiagnostics.onDecoded()
                continue
            }
            // ── 09 04 55: P-frame decoded → q3c.K2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x04 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                send(sock,DashCommands.frameDecodedP())
                ConnectionDiagnostics.onDecoded()
                continue
            }
            // ── 09 00: button / joystick event → echo ack + notify UI ──
            if (tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()  // 0900 0001 <code>
                DebugLog.i(TAG) { "JOYSTICK 09 00 → code 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}  full=${tlv.value.toHexFull()}" }
                send(sock,DashCommands.buttonAck(btn))
                scope.launch(Dispatchers.Main) { onButtonPacket?.invoke(tlv.value.copyOf()); onButton?.invoke(btn) }
                continue
            }
            // ── 0F: vehicle-secure telemetry (AES-256-CBC under the session key,
            //    IV = first 16 bytes). This is the dash's instrument-cluster data
            //    (likely trip/odo/fuel/speed/temp). The better-dash reference only
            //    logs these as ciphertext — we actually DECRYPT with our session key
            //    and log the plaintext for field-mapping (P1b). It arrives over our
            //    own session, so plain `adb logcat -s DashSession` captures it — no
            //    root, no monitor mode. ──
            if (tlv.type == 0x0F) {
                val key = auth?.sessionKey
                val plain = key?.let { aesDecryptCbc(tlv.value, it) }
                DebugLog.i(TAG) { "DASH TELEMETRY 0F sub=0x%02X enc(%dB)=%s  dec=%s".format(
                    tlv.sub, tlv.value.size, tlv.value.toHexFull(),
                    plain?.toHexFull() ?: "<key=${key != null}; decrypt failed>") }
                continue
            }
            // ── 0C xx: dash → app telemetry (trip/odo/fuel/temp — P1b) ──
            if (tlv.type == 0x0C) {
                DebugLog.i(TAG) { "DASH TELEMETRY 0C sub=0x%02X (%dB) val=%s"
                    .format(tlv.sub, tlv.value.size, tlv.value.toHexFull()) }
                continue
            }
            // Log every OTHER incoming event (e.g. joystick in nav view, or the dash's
            // 'exit navigation' selection) in FULL so its TLV can be identified + mapped.
            DebugLog.i(TAG) { "DASH EVENT type=0x%02X sub=0x%02X (%dB) val=%s"
                .format(tlv.type, tlv.sub, tlv.value.size, tlv.value.toHexFull()) }
        }
    }

    private fun launchStatusHeartbeat(sock: DashTransport) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                runCatching { send(sock,DashCommands.heartbeat()) }
                // Keep the dash clock correct — it only shows what the phone feeds it.
                if (n++ % 30 == 0) runCatching { send(sock,DashCommands.timeSync()) }
                delay(1_000)
            }
        }
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                send(socket,DashCommands.projectionFrame())
                delay(PROJ_HB_MS)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navChromeEnabled) send(socket,liveRouteCard(projectionOn = true))
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchNavInfo() {
        navInfoJob?.cancel()
        navInfoJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navChromeEnabled && (navActive || testManeuver!=null)) {
                    send(socket,
                        DashCommands.activeNavPacket(
                            maneuver = visibleManeuver(),
                            primaryDist = if(cleanProjection) 0 else navPrimaryDist,
                            primaryUnit = if(cleanProjection) 0 else navPrimaryUnit,
                            totalDist = navTotalDist,
                            totalUnit = navTotalUnit,
                        )
                    )
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchMediaInfo() {
        mediaInfoJob?.cancel()
        mediaInfoJob = scope.launch(Dispatchers.IO) {
            var previousCaller: String? = null
            var lastCallerAt=0L
            val mediaUpdates=DashMediaUpdates()
            val marquee=DashMusicMarquee()
            val projectionLabels=DashProjectionLabelUpdates()
            while (isActive && _state.value == DashState.STREAMING) {
                val caller = callerName
                val now=android.os.SystemClock.elapsedRealtime()
                when {
                    caller != null && (caller!=previousCaller || now-lastCallerAt>=ROUTE_CARD_MS) -> {
                        runCatching { send(socket,DashCommands.callNotify(caller)) }; lastCallerAt=now
                    }
                    caller==null && previousCaller != null -> runCatching { send(socket,DashCommands.callClear()) }
                }
                previousCaller = caller
                // The outside/map strip uses 05 01, not the inside/media metadata field.
                projectionLabels.next(projectionLabel,now)?.let { packet ->
                    runCatching { send(socket,packet) }
                }
                val song=track
                val card=mediaCard?.copy(artist=marquee.text(song,now))
                for(packet in mediaUpdates.next(card,now,song.playing ?: musicPlaying)) {
                    runCatching { send(socket,packet) }
                }
                delay(250) // React to a button promptly; unchanged cards produce no packets.
            }
        }
    }

    @Synchronized private fun fail(msg: String, expected:DashTransport?=socket) {
        if(expected!==socket || _state.value in listOf(DashState.IDLE,DashState.ERROR)) return
        sessionGeneration++
        val old=socket; socket=null
        ConnectionDiagnostics.event("Sessão do painel interrompida: $msg")
        DebugLog.e(TAG, { "ERROR — $msg" })
        sessionJob?.cancel(); rxJob?.cancel(); heartbeatJob?.cancel(); mediaInfoJob?.cancel()
        projHbJob?.cancel(); routeCardJob?.cancel(); navInfoJob?.cancel()
        old?.close()
        authConfirmed=false; ConnectionDiagnostics.authenticated=false
        _state.value = DashState.ERROR
        onError?.invoke(msg)
    }

    /** Full hex dump (no truncation) — used for protocol-capture logging. */
    private fun ByteArray.toHexFull(): String =
        joinToString(" ") { "%02X".format(it) }

    /** AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key. */
    private fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (ivAndCt.size <= 16) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
        )
        cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
    }.getOrNull()
}
