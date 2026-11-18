package com.example.opendash.dash

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import com.example.opendash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WifiConnStatus { IDLE, REQUESTING, CONNECTED, ERROR }

data class WifiState(
    val status: WifiConnStatus = WifiConnStatus.IDLE,
    val ssid: String = "",
    val error: String? = null,
)

/**
 * Programmatically connects to the Tripper Dash WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * Only the panel sockets bind to this network; the phone keeps its internet route.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 *
 * Android 11 / ColorOS fixes (vs Android 13/14):
 *   - WifiNetworkSpecifier opens Wi-Fi SETTINGS instead of an in-app popup on API 30.
 *   - transportInfo in onCapabilitiesChanged is null or "<unknown ssid>" for manually-
 *     joined networks, so we never fall back to the prefix string as the SSID.
 *   - A polling fallback reads the active SSID via the deprecated WifiManager.connectionInfo
 *     path (the only reliable API on API 30 with location permission).
 *   - requestNetwork() is called with an explicit Handler to prevent ColorOS from dropping
 *     the callback binder when the Settings UI closes.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 40_000
        // Android returns this sentinel from WifiInfo.getSsid() when it can't read the SSID.
        private const val WifiManagerUnknownSsid = "<unknown ssid>"
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    /**
     * The dash WiFi network, exposed so the dash UDP sockets can be bound to it
     * INDIVIDUALLY (Network.bindSocket). We deliberately do NOT bindProcessToNetwork:
     * that routed the WHOLE app through the dash's no-internet WiFi, so routing,
     * geocoding and shared-link resolution all failed while connected ("can't share").
     */
    @Volatile var network: Network? = null
        private set

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var healthJob:Job?=null
    private val recovery=ConnectionRecovery()
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""
    private var pendingPrefix = false
    private var resolvedSsid: String? = null
    private var generation = 0

    /**
     * When we connect by prefix (any RE_* dash), the exact SSID is only known once the
     * link is up. This callback reports it so the caller can persist it for direct
     * reconnects next time. Null/blank if it can't be resolved.
     */
    var onSsidResolved: ((String) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request a WiFi network. Shows a one-time system confirmation dialog.
     *
     * @param prefixMatch when true, [ssid] is treated as a PREFIX and Android offers any
     *   matching network (e.g. every `RE_*` dash) — this is what makes OpenDash work on
     *   any rider's Tripper without hardcoding their SSID. When false, exact-match.
     */
    fun connect(ssid: String, password: String = "", prefixMatch: Boolean = false) {
        recovery.reset()
        reconnectJob?.cancel(); reconnectJob=null
        wantConnected    = true
        pendingSsid      = ssid
        pendingPassword  = password
        pendingPrefix    = prefixMatch
        resolvedSsid     = null
        requestNetwork()
    }

    /**
     * Find a dash SSID from the latest WiFi scan results (any network whose name starts
     * with [prefix], e.g. "RE_"). We need the EXACT SSID string up front because the dash
     * validates it inside the encrypted auth handshake — and Android 13+ redacts the SSID
     * of the connected network, so we can't read it back after connecting.
     */
    @SuppressLint("MissingPermission")
    fun findDashSsid(prefix: String): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.scanResults
            .mapNotNull { it.SSID?.trim('"')?.takeIf { s -> s.isNotBlank() } }
            .firstOrNull { it.startsWith(prefix) }
            .also { DebugLog.i(TAG) { "Scan lookup for '$prefix*' → ${it ?: "not found"}" } }
    } catch (e: Exception) {
        DebugLog.w(TAG) { "Scan lookup failed: ${e.message}" }; null
    }

    fun disconnect() {
        DebugLog.i(TAG) { "Disconnect requested" }
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    /** A socket can report a dead route before Android delivers onLost. Main thread only. */
    fun recoverNetwork(failedNetwork:Network?) {
        if(!wantConnected || failedNetwork==null || failedNetwork!=network) return
        release()
        _state.value=WifiState(WifiConnStatus.REQUESTING,pendingSsid,"Conexão perdida · reconectando a moto…")
        scheduleReconnect()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun requestNetwork() {
        release()
        DebugLog.i(TAG) {
            "Requesting WiFi: '$pendingSsid' " +
                "(${if (pendingPrefix) "prefix" else "exact"}, password=${if (pendingPassword.isBlank()) "none" else "set"})"
        }
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        val specBuilder = WifiNetworkSpecifier.Builder()
        if (pendingPrefix) specBuilder.setSsidPattern(PatternMatcher(pendingSsid, PatternMatcher.PATTERN_PREFIX))
        else specBuilder.setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specBuilder.build())
                        .build()

        val token = generation
        val delegate = object : ConnectivityManager.NetworkCallback() {

            // FIX: Do NOT fall back to pendingSsid ("RE_") when the SSID is unreadable.
            // On Android 11, transportInfo is not populated in onAvailable — we stay in
            // REQUESTING and let onCapabilitiesChanged (or the polling fallback) resolve
            // the real SSID before we tell the session to connect.
            override fun onAvailable(network: Network) {
                if (token != generation || !wantConnected) return
                this@DashWifiManager.network = network
                reconnectJob?.cancel()
                recovery.reset()
                watchNetwork(network,token)
                val resolved = resolveSsid(network)
                DebugLog.i(TAG) {
                    "onAvailable network=$network resolved='$resolved' " +
                    "pending='$pendingSsid' prefix=$pendingPrefix"
                }
                when {
                    matches(resolved) -> {
                        // Android 13+: SSID already readable — transition to CONNECTED now.
                        if (resolved != pendingSsid) onSsidResolved?.invoke(resolved)
                        pendingSsid = resolved; pendingPrefix = false; resolvedSsid = resolved
                        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = resolved)
                    }
                    !pendingPrefix -> {
                        // Exact-match connect: the SSID is already known by definition.
                        _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = pendingSsid)
                    }
                    else -> {
                        // Prefix connect on Android 11: SSID not readable yet.
                        // Stay REQUESTING — onCapabilitiesChanged or the polling loop will
                        // supply the real SSID and then flip state to CONNECTED.
                        DebugLog.w(TAG) {
                            "onAvailable: SSID unreadable (Android 11 / transportInfo null) " +
                            "— waiting for onCapabilitiesChanged or poll fallback"
                        }
                        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)
                        scope.launch {
                            delay(12_000)
                            if(token==generation && wantConnected && _state.value.status==WifiConnStatus.REQUESTING) {
                                _state.value=WifiState(status=WifiConnStatus.ERROR,error="O Android não informou o nome da rede escolhida. Confira a permissão de localização precisa.")
                            }
                        }
                    }
                }
            }

            // FIX: Log when transportInfo is null so adb output is diagnostic,
            // rather than silently returning and leaving state stuck at REQUESTING.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (token != generation || !wantConnected) return
                val info = caps.transportInfo as? WifiInfo
                DebugLog.i(TAG) {
                    "onCapabilitiesChanged network=$network " +
                    "transportInfo=${info?.javaClass?.simpleName ?: "null"} " +
                    "rawSsid='${info?.ssid}'"
                }
                val ssid = info?.ssid.orEmpty().trim('"')
                if (!matches(ssid)) {
                    // transportInfo is null or redacted — common on Android 11 / ColorOS
                    // for manually-joined networks. The polling fallback handles this.
                    DebugLog.w(TAG) {
                        "onCapabilitiesChanged: SSID unresolvable " +
                        "(transportInfo=${info?.javaClass?.simpleName ?: "null"}) " +
                        "— Android 11 / ColorOS limitation, poll fallback running"
                    }
                    return
                }
                if (ssid == resolvedSsid) return
                resolvedSsid = ssid
                this@DashWifiManager.network = network
                DebugLog.i(TAG) { "Resolved dash SSID via capabilities: '$ssid'" }
                onSsidResolved?.invoke(ssid)
                pendingSsid = ssid
                pendingPrefix = false
                _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = ssid)
            }

            override fun onUnavailable() {
                if (token != generation) return
                val retry=recovery.unavailableRetry(pendingPrefix)
                if(wantConnected && retry!=null) {
                    release()
                    _state.value=WifiState(WifiConnStatus.REQUESTING,pendingSsid,"Procurando novamente a moto salva…")
                    scheduleReconnect(retry)
                    return
                }
                DebugLog.w(TAG) { "WiFi unavailable — SSID not found or user declined" }
                this@DashWifiManager.network = null
                _state.value = WifiState(
                    status = WifiConnStatus.ERROR,
                    ssid   = pendingSsid,
                    error  = "Não foi possível conectar. Ative o Wi-Fi da moto e escolha sua rede RE_.",
                )
                // Don't auto-retry onUnavailable; user must try again
                wantConnected = false
            }

            override fun onLost(network: Network) {
                if (token != generation || this@DashWifiManager.network != network) return
                DebugLog.w(TAG) { "WiFi link lost — reconnecting" }
                release()
                _state.value = WifiState(
                    status = WifiConnStatus.REQUESTING,
                    ssid   = pendingSsid,
                    error  = "Conexão perdida · reconectando a moto…",
                )
                if (wantConnected) scheduleReconnect()
            }
        }

        // API 31+ redacts SSID unless location data is explicitly requested in callbacks.
        val cb = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(n: Network) = delegate.onAvailable(n)
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) = delegate.onCapabilitiesChanged(n,c)
            override fun onUnavailable() = delegate.onUnavailable()
            override fun onLost(n: Network) = delegate.onLost(n)
        } else delegate
        networkCallback = cb
        try {
            // FIX: Supply an explicit Handler so callbacks are always delivered on the app's
            // main looper. Without this, ColorOS 11 / some API 30 OEM builds silently drop
            // onCapabilitiesChanged after the Settings UI closes (the binder gets GC'd).
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            cm.requestNetwork(request, cb, handler, CONNECT_TIMEOUT)

            // FIX: Android 11 polling fallback.
            // WifiNetworkSpecifier opens Wi-Fi Settings on API 30 (not an in-app popup).
            // After the user manually connects, ColorOS may not fire onCapabilitiesChanged
            // with a valid SSID. We poll WifiManager.connectionInfo (deprecated but the only
            // reliable path on API 30 with location permission) until we get the real SSID.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                scope.launch {
                    // Give the user time to see the Settings screen and tap the network.
                    delay(5_000)
                    repeat(5) { attempt ->
                        if(token != generation || !wantConnected || network == null) return@launch
                        // Stop polling if the callback path already resolved it.
                        if (_state.value.status == WifiConnStatus.CONNECTED
                            && resolvedSsid != null) return@repeat
                        val fallbackSsid = tryReadSsidFromActiveWifi()
                        DebugLog.i(TAG) { "Android 11 SSID poll #${attempt + 1}: '$fallbackSsid'" }
                        if (!fallbackSsid.isNullOrBlank()) {
                            resolvedSsid = fallbackSsid
                            onSsidResolved?.invoke(fallbackSsid)
                            _state.value = WifiState(
                                status = WifiConnStatus.CONNECTED,
                                ssid   = fallbackSsid,
                            )
                            return@launch
                        }
                        delay(2_000)
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, { "requestNetwork threw: ${e.message}" }, e)
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun scheduleReconnect(waitMs:Long=recovery.lostDelay()) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(waitMs)
            if (wantConnected) requestNetwork()
        }
    }

    private fun watchNetwork(selected:Network,token:Int) {
        healthJob?.cancel()
        healthJob=scope.launch {
            var misses=0
            while(wantConnected && token==generation && network==selected) {
                delay(3000)
                if(token!=generation || network!=selected) return@launch
                // A dash intentionally has no internet. Never test VALIDATED/INTERNET here.
                val present=cm.getNetworkCapabilities(selected)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
                misses=if(present) 0 else misses+1
                if(misses>=2) {
                    release()
                    _state.value=WifiState(WifiConnStatus.REQUESTING,pendingSsid,"Reconectando a moto salva…")
                    scheduleReconnect()
                    return@launch
                }
            }
        }
    }

    /** Read the connected network's SSID (strips the surrounding quotes Android adds). */
    private fun resolveSsid(network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return ""
        val info = caps.transportInfo as? WifiInfo ?: return ""
        return info.ssid.orEmpty().trim('"').let { if (it == WifiManagerUnknownSsid) "" else it }
    }

    /**
     * Android 11 fallback: read the active SSID from the deprecated WifiManager.connectionInfo
     * API. This is the only reliable path on API 30 / ColorOS when transportInfo is never
     * populated in the NetworkCallback (because ColorOS doesn't associate the manually-joined
     * network with our requestNetwork() call).
     *
     * Only called on API < 31. Suppressed deprecation is intentional and necessary.
     * Returns null when location permission is absent or no matching network is active.
     */
    @SuppressLint("MissingPermission")
    private fun tryReadSsidFromActiveWifi(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        @Suppress("DEPRECATION")
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        val ssid = info.ssid.orEmpty().trim('"')
        DebugLog.d(TAG) { "tryReadSsidFromActiveWifi: rawSsid='${info.ssid}' bssid='${info.bssid}'" }
        if (ssid.isBlank() || ssid == WifiManagerUnknownSsid) return null
        // Only accept SSIDs that match what we tried to connect to.
        if (pendingPrefix && !ssid.startsWith(pendingSsid)) return null
        if (!pendingPrefix && ssid != pendingSsid) return null
        ssid
    }.getOrNull()

    private fun release() {
        healthJob?.cancel(); healthJob=null
        generation++
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        network = null
    }
    private fun matches(ssid: String) = ssid.startsWith("RE_") && ssid.length > 3 &&
        (if(pendingPrefix) ssid.startsWith(pendingSsid) else ssid == pendingSsid)
}
