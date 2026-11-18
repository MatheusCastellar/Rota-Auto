package com.example.opendash.auto

import android.media.*
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Independent sinks: a blocked music route cannot hold up speech or video ACKs. */
internal class AaAudio(context:Context,private val report:(String)->Unit):AutoCloseable {
    private val manager=context.getSystemService(AudioManager::class.java)
    private val preferences=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE)
    private fun physicalOutput(speech:Boolean):AudioDeviceInfo? {
        val communication=if(Build.VERSION.SDK_INT>=31) manager.communicationDevice else null
        return AaPlaybackPolicy.output(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS),communication,speech)
    }
    private val sinks=ConcurrentHashMap<Int,Sink>()
    private data class Packet(val bytes:ByteArray,val consumed:()->Unit)
    @Synchronized fun offer(channel:Int,bytes:ByteArray,consumed:()->Unit) {
        if(channel !in 4..6 || bytes.isEmpty()) { consumed(); return }
        if(sinks[channel]?.accepting==false) sinks.remove(channel)?.stop()
        sinks.getOrPut(channel) { Sink(channel) }.offer(Packet(bytes,consumed))
    }
    @Synchronized fun stop(channel:Int) { sinks[channel]?.finish() }
    @Synchronized override fun close() { sinks.values.forEach { it.stop() }; sinks.clear() }
    private inner class Sink(val channel:Int) {
        // AA is offered four credits. Acknowledge after writing instead of allowing
        // a fast producer to flood this queue and erase the assistant's words.
        private val queue=ArrayBlockingQueue<Packet>(8)
        @Volatile private var running=true
        @Volatile private var finishing=false
        val accepting get()=running && !finishing
        @Volatile private var track:AudioTrack?=null
        private var notified=false
        private val worker=Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                val media=channel==6; val rate=if(media) 48000 else 16000
                val mask=if(media) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val t=AudioTrack.Builder().setAudioAttributes(AaPlaybackPolicy.attributes(channel))
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT),rate*(if(media) 4 else 2)/10))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track=t; check(t.state==AudioTrack.STATE_INITIALIZED)
                t.setPreferredDevice(physicalOutput(!media)); t.play()
                var framesWritten=0L
                var nextRouteCheck=0L
                while(running) {
                    val item=queue.poll(50,TimeUnit.MILLISECONDS)
                    if(item==null) { if(finishing) break else continue }
                    val packet=VoiceGain.apply(channel,item.bytes,preferences.getInt(VoiceGain.PREFERENCE,VoiceGain.DEFAULT))
                    var offset=0
                    if(SystemClock.elapsedRealtime()>=nextRouteCheck) {
                        nextRouteCheck=SystemClock.elapsedRealtime()+500
                        // Never route the receiver's own playback back into AA's remote submix.
                        val device=physicalOutput(!media)
                        if(t.preferredDevice?.id!=device?.id) t.setPreferredDevice(device)
                    }
                    while(running && offset<packet.size) {
                        val n=t.write(packet,offset,packet.size-offset,AudioTrack.WRITE_NON_BLOCKING)
                        check(n>=0) { "AudioTrack $n" }
                        if(n==0) Thread.sleep(2) else { offset+=n; framesWritten+=n/(if(media) 4 else 2) }
                    }
                    if(running) item.consumed()
                    if(!notified && running) {
                        notified=true
                        val gain=if(channel==4) preferences.getInt(VoiceGain.PREFERENCE,VoiceGain.DEFAULT).coerceIn(VoiceGain.MIN,VoiceGain.MAX) else 100
                        report("Áudio canal=$channel · saída=${t.routedDevice?.type ?: 0} · ganho=$gain% · volume mídia=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)}/${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
                    }
                }
                // STOP marks the end of the stream, not permission to truncate its tail.
                val deadline=SystemClock.elapsedRealtime()+500
                while(running && finishing && (t.playbackHeadPosition.toLong() and 0xffffffffL)<framesWritten && SystemClock.elapsedRealtime()<deadline) Thread.sleep(5)
            } catch(_:InterruptedException) { /* normal stop */ }
            catch(e:Exception) { if(running) report("Áudio canal $channel: ${e.javaClass.simpleName}") }
            finally { running=false; runCatching { track?.pause(); track?.flush(); track?.release() }; track=null }
        },"aa-audio-$channel").also { it.start() }
        fun offer(packet:Packet) {
            if(!running || packet.bytes.size>192000) { packet.consumed(); return }
            if(!queue.offer(packet)) {
                packet.consumed()
                report("Áudio canal $channel: emissor excedeu a janela de recepção")
            }
        }
        fun finish() { finishing=true }
        fun stop() { running=false; queue.clear(); worker.interrupt() }
    }
}
