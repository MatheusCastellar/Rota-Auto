package com.example.opendash.auto

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import com.andrerinas.openheadunit.aap.MicSessionController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** On-demand capture. No recordings are persisted; the AA peer controls Open/Close. */
internal interface AaCapture {
    fun start(callback:MicSessionController.CaptureCallbacks):Int
    fun stop()
}
internal class AaMicrophone(private val context:Context,private val allowed:()->Boolean,
    private val report:(String)->Unit):AaCapture {
    private val manager=context.getSystemService(AudioManager::class.java)
    @Volatile private var recorder:AudioRecord?=null
    @Volatile private var running=false
    private var worker:Thread?=null
    private var ownedRoute=false
    private var legacySco=false
    private var registered=false
    private val devices=object:AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed:Array<out AudioDeviceInfo>) {
            if(running && removed.any { it.type in intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADSET) }) {
                phoneRoute()
                recorder?.let { r -> runCatching { r.setPreferredDevice(manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC }) } }
                report("Intercomunicador desconectado · microfone do celular")
            }
        }
    }
    override fun start(callback:MicSessionController.CaptureCallbacks):Int {
        if(!allowed() || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            report("Microfone indisponível · permita o acesso e reinicie a ponte com o aplicativo aberto")
            return -1
        }
        if(manager.mode==AudioManager.MODE_IN_CALL || manager.mode==AudioManager.MODE_IN_COMMUNICATION) {
            report("Microfone ocupado por uma chamada"); return -1
        }
        try {
            // AA and this receiver share the phone. Entering call mode interrupts AA's
            // assistant even for a Bluetooth mic. Select its route without changing mode.
            val useHeadset=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).getBoolean("intercom-mic",true)
            val bluetoothAllowed=Build.VERSION.SDK_INT<31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
            var bluetooth=false
            if(Build.VERSION.SDK_INT>=31) {
                val available=manager.availableCommunicationDevices
                val external=if(useHeadset) available.firstOrNull { bluetoothAllowed && it.type in intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET) }
                    ?: available.firstOrNull { it.type==AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type==AudioDeviceInfo.TYPE_USB_HEADSET } else null
                if(external!=null) {
                    ownedRoute=true
                    report("Selecionando microfone externo · saída=${external.type}; modo=${manager.mode}")
                    val latch=CountDownLatch(1)
                    val listener=AudioManager.OnCommunicationDeviceChangedListener { if(it?.id==external.id) latch.countDown() }
                    manager.addOnCommunicationDeviceChangedListener(context.mainExecutor,listener)
                    try {
                        if(manager.setCommunicationDevice(external)) {
                            if(manager.communicationDevice?.id!=external.id) latch.await(2,TimeUnit.SECONDS)
                            bluetooth=manager.communicationDevice?.id==external.id
                        }
                    } finally { manager.removeOnCommunicationDeviceChangedListener(listener) }
                }
                if(!bluetooth) phoneRoute()
            } else {
                @Suppress("DEPRECATION")
                if(useHeadset && manager.isBluetoothA2dpOn && manager.isBluetoothScoAvailableOffCall) {
                    ownedRoute=true
                    val latch=CountDownLatch(1)
                    val receiver=object:BroadcastReceiver() {
                        override fun onReceive(c:Context,i:Intent) {
                            if(i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,-1)==AudioManager.SCO_AUDIO_STATE_CONNECTED) latch.countDown()
                        }
                    }
                    context.registerReceiver(receiver,IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                    try { legacySco=true; manager.startBluetoothSco(); bluetooth=latch.await(2,TimeUnit.SECONDS); manager.isBluetoothScoOn=bluetooth }
                    finally { context.unregisterReceiver(receiver) }
                }
                if(!bluetooth) phoneRoute()
            }
            if(!callback.isCurrent()) { stop(); return -1 }
            val format=AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(min>0)
            val record=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format).setBufferSizeInBytes(maxOf(min*2,8192)).build()
            recorder=record; check(record.state==AudioRecord.STATE_INITIALIZED)
            val input=manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                if(bluetooth) it.type in intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET)
                else it.type==AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
            val preferred=record.setPreferredDevice(input)
            report("Microfone preparado · entrada solicitada=${input?.type ?: 0}; aceita=$preferred; modo=${manager.mode}")
            record.startRecording(); check(record.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            running=true
            manager.registerAudioDeviceCallback(devices,Handler(Looper.getMainLooper())); registered=true
            worker=Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val bytes=ByteArray(4096)
                var lastRoute=-1
                try {
                    while(running && callback.isCurrent()) {
                        val n=record.read(bytes,0,bytes.size,AudioRecord.READ_NON_BLOCKING)
                        if(n<0) error("AudioRecord $n")
                        if(n==0) { Thread.sleep(5); continue }
                        val route=record.routedDevice?.type ?: 0
                        if(route!=lastRoute) {
                            lastRoute=route
                            report("Microfone captando · entrada real=$route; modo=${manager.mode}")
                        }
                        var peak=0
                        for(i in 0 until n-1 step 2) peak=maxOf(peak,abs(((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()))
                        callback.data(bytes,n,peak)
                    }
                } catch(_:InterruptedException) { /* normal stop */ }
                catch(e:Exception) { if(running) { report("Falha no microfone: ${e.javaClass.simpleName}"); callback.failed() } }
            },"aa-microphone").also { it.start() }
            return 0
        } catch(e:Exception) { report("Microfone: ${e.javaClass.simpleName}"); stop(); return -1 }
    }
    @Suppress("DEPRECATION")
    private fun phoneRoute() {
        restoreRoute()
    }
    @Suppress("DEPRECATION")
    override fun stop() {
        running=false
        if(registered) { runCatching { manager.unregisterAudioDeviceCallback(devices) }; registered=false }
        val record=recorder; recorder=null
        runCatching { record?.stop() }; worker?.interrupt(); runCatching { worker?.join(1000) }; worker=null
        runCatching { record?.release() }
        restoreRoute()
    }
    @Suppress("DEPRECATION")
    private fun restoreRoute() {
        if(ownedRoute) {
            ownedRoute=false
            runCatching {
                if(Build.VERSION.SDK_INT>=31) {
                    // Release only this process's request. communicationDevice is an
                    // observed system route, not a prior request owned by our process.
                    manager.clearCommunicationDevice()
                } else {
                    if(legacySco) { manager.stopBluetoothSco(); manager.isBluetoothScoOn=false; legacySco=false }
                }
            }
            report("Microfone fechado · áudio normal restaurado")
        }
    }
}
