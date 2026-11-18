package com.example.opendash.auto

import com.andrerinas.openheadunit.aap.protocol.proto.*
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.*
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Local simulated peer: real TCP/TLS/protobuf, not Google AA or real hardware validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@ConscryptMode(ConscryptMode.Mode.OFF) // Use host JSSE; Robolectric Conscrypt breaks JDK signed-provider verification on Windows.
class AaSessionTest {
    @Test fun microphoneOpenCreditsCloseAndReopenUseRealEncryptedWire() {
        val app=RuntimeEnvironment.getApplication()
        val captures=LinkedBlockingQueue<com.andrerinas.openheadunit.aap.MicSessionController.CaptureCallbacks>()
        val stopped=java.util.concurrent.atomic.AtomicInteger()
        val capture=object:AaCapture {
            override fun start(callback:com.andrerinas.openheadunit.aap.MicSessionController.CaptureCallbacks):Int {
                captures.add(callback); return 0
            }
            override fun stop() { stopped.incrementAndGet() }
        }
        val session=AaSession(app,object:AaSession.Listener {
            override fun status(message:String) {}
            override fun video(data:ByteArray,timeUs:Long,config:Boolean) {}
            override fun audio(channel:Int,data:ByteArray) {}
            override fun audioStop(channel:Int) {}
            override fun ended(error:String?) {}
        },true,{capture})
        val server=ServerSocket().apply { reuseAddress=true; soTimeout=10000; bind(InetSocketAddress("127.0.0.1",5277)) }
        try {
            session.start()
            server.accept().use { socket ->
                socket.soTimeout=10000
                val peer=Peer(socket,SingleKeyKeyManager(app)); peer.authenticate()
                peer.send(0,5,byteArrayOf())
                assertTrue(Control.ServiceDiscoveryResponse.parseFrom(peer.receive(0,6)).servicesList.any { it.id==7 })
                fun open():Media.MicrophoneResponse {
                    peer.send(7,0x8005,Media.MicrophoneRequest.newBuilder().setOpen(true).setMaxUnacked(1).build().toByteArray())
                    return Media.MicrophoneResponse.parseFrom(peer.receive(7,0x8006))
                }
                val first=open(); assertEquals(0,first.status)
                val callback=captures.poll(5,TimeUnit.SECONDS)!!
                val pcm=ByteArray(4096) { (it%251).toByte() }
                callback.data(pcm+pcm,pcm.size*2,300) // two chunks; only one can pass before ACK
                val packet=peer.receive(7,0)
                assertEquals(4104,packet.size); assertArrayEquals(pcm,packet.copyOfRange(8,packet.size))
                val firstTime=ByteBuffer.wrap(packet).long
                peer.send(7,0x8004,Media.Ack.newBuilder().setSessionId(first.sessionId).setAck(1).build().toByteArray())
                val second=peer.receive(7,0)
                assertEquals(128000L,ByteBuffer.wrap(second).long-firstTime)
                peer.send(7,0x8005,Media.MicrophoneRequest.newBuilder().setOpen(false).build().toByteArray())
                assertEquals(first.sessionId,Media.MicrophoneResponse.parseFrom(peer.receive(7,0x8006)).sessionId)
                assertFalse(callback.isCurrent()); assertTrue(stopped.get()>0)
                callback.data(pcm,pcm.size,300) // retired callbacks cannot leak into the next session
                val reopened=open(); assertTrue(reopened.sessionId>first.sessionId)
                assertEquals(0,reopened.status)
                peer.send(7,0x8005,Media.MicrophoneRequest.newBuilder().setOpen(false).build().toByteArray())
                assertEquals(reopened.sessionId,Media.MicrophoneResponse.parseFrom(peer.receive(7,0x8006)).sessionId)
            }
        } finally { session.close(); server.close() }
    }
    @Test fun negotiatesEncryptedServicesAndDeliversVideoWithCreditAndTouch() {
        val app=RuntimeEnvironment.getApplication()
        val done=CountDownLatch(1)
        val videos=LinkedBlockingQueue<Pair<ByteArray,Long>>()
        val tracks=LinkedBlockingQueue<AaNowPlaying>()
        val audioCredits=LinkedBlockingQueue<()->Unit>()
        val errors=LinkedBlockingQueue<String>()
        val session=AaSession(app,object:AaSession.Listener {
            override fun status(message:String) {}
            override fun video(data:ByteArray,timeUs:Long,config:Boolean) { if(!config) videos.add(data to timeUs) }
            override fun nowPlaying(track:AaNowPlaying) { tracks.add(track) }
            override fun audio(channel:Int,data:ByteArray) {}
            override fun audio(channel:Int,data:ByteArray,consumed:()->Unit) { audioCredits.add(consumed) }
            override fun audioStop(channel:Int) {}
            override fun ended(error:String?) { error?.let { errors.add(it) }; done.countDown() }
        })
        val server=ServerSocket().apply { reuseAddress=true; soTimeout=10000; bind(InetSocketAddress("127.0.0.1",5277)) }
        try {
            session.start()
            server.accept().use { socket ->
                socket.soTimeout=10000
                val peer=Peer(socket,SingleKeyKeyManager(app))
                peer.authenticate()
                peer.send(0,5,byteArrayOf())
                val discovery=Control.ServiceDiscoveryResponse.parseFrom(peer.receive(0,6))
                assertTrue(discovery.servicesList.any { it.id==2 })
                assertTrue(discovery.servicesList.single { it.id==10 }.hasMediaPlaybackService())
                peer.send(10,0x8003,byteArrayOf(10,4,83,111,110,103,18,6,83,105,110,103,101,114))
                assertEquals(AaNowPlaying("Song","Singer"),tracks.poll(5,TimeUnit.SECONDS))
                peer.send(10,0x8001,byteArrayOf(8,2))
                assertEquals(true,tracks.poll(5,TimeUnit.SECONDS)!!.playing)
                val sensors=discovery.servicesList.single { it.id==1 }.sensorSourceService.sensorsList.map { it.type }
                assertEquals(AaSensors.supported,sensors)
                assertFalse(sensors.contains(Sensors.SensorType.LOCATION))
                assertFalse(sensors.contains(Sensors.SensorType.CAR_SPEED))
                for(sensor in sensors) {
                    peer.send(1,0x8001,Sensors.SensorRequest.newBuilder().setType(sensor).setMinUpdatePeriod(0).build().toByteArray())
                    assertEquals(Common.MessageStatus.STATUS_SUCCESS,Sensors.SensorResponse.parseFrom(peer.receive(1,0x8002)).status)
                    assertEquals(AaSensors.event(sensor,false),Sensors.SensorBatch.parseFrom(peer.receive(1,0x8003)))
                }
                for(night in listOf(true,false)) {
                    session.setNight(night)
                    val event=Sensors.SensorBatch.parseFrom(peer.receive(1,0x8003))
                    assertEquals(night,event.getNightMode(0).isNightMode)
                    assertEquals(0,event.locationDataCount+event.speedDataCount+event.parkingbrakeDataCount)
                }
                for(parked in listOf(true,false)) {
                    session.setParked(parked)
                    val brake=Sensors.SensorBatch.parseFrom(peer.receive(1,0x8003))
                    val driving=Sensors.SensorBatch.parseFrom(peer.receive(1,0x8003))
                    assertEquals(parked,brake.getParkingbrakeData(0).isEngaged)
                    assertEquals(if(parked) 0 else 27,driving.getDrivingStatus(0).status)
                    assertEquals(0,driving.getDrivingStatus(0).status and 4) // Voice remains available.
                    assertEquals(0,brake.locationDataCount+driving.locationDataCount+brake.speedDataCount+driving.speedDataCount)
                }
                peer.send(1,0x8001,Sensors.SensorRequest.newBuilder().setType(Sensors.SensorType.LOCATION).setMinUpdatePeriod(0).build().toByteArray())
                assertEquals(Common.MessageStatus.STATUS_INVALID_SENSOR,Sensors.SensorResponse.parseFrom(peer.receive(1,0x8002)).status)
                peer.send(2,7,Control.ChannelOpenRequest.newBuilder().setPriority(0).setServiceId(2).build().toByteArray(),true)
                assertEquals(Common.MessageStatus.STATUS_SUCCESS,Control.ChannelOpenResponse.parseFrom(peer.receive(2,8)).status)
                peer.send(2,0x8000,byteArrayOf())
                assertEquals(4,Media.Config.parseFrom(peer.receive(2,0x8003)).maxUnacked)
                assertTrue(Media.VideoFocusNotification.parseFrom(peer.receive(2,0x8008)).unsolicited)
                // A screen-off request must not turn off the separate motorcycle display.
                peer.send(2,0x8007,Media.VideoFocusRequestNotification.newBuilder()
                    .setMode(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE)
                    .setReason(Media.VideoFocusRequestNotification.VideoFocusReason.PHONE_SCREEN_OFF).build().toByteArray())
                val screenOffFocus=Media.VideoFocusNotification.parseFrom(peer.receive(2,0x8008))
                assertEquals(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED,screenOffFocus.mode)
                assertFalse(screenOffFocus.unsolicited)
                peer.send(2,0x8007,Media.VideoFocusRequestNotification.newBuilder()
                    .setMode(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT)
                    .setReason(Media.VideoFocusRequestNotification.VideoFocusReason.LAUNCH_NATIVE).build().toByteArray())
                val transientFocus=Media.VideoFocusNotification.parseFrom(peer.receive(2,0x8008))
                assertEquals(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT,transientFocus.mode)
                assertFalse(transientFocus.unsolicited)
                peer.send(2,0x8007,Media.VideoFocusRequestNotification.newBuilder()
                    .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED).build().toByteArray())
                assertEquals(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED,
                    Media.VideoFocusNotification.parseFrom(peer.receive(2,0x8008)).mode)
                peer.send(2,0x8001,Media.Start.newBuilder().setSessionId(47).setConfigurationIndex(0).build().toByteArray())
                val fixture=byteArrayOf(0,0,0,1,0x65,10,20,30)
                val packet=ByteBuffer.allocate(8+fixture.size).putLong(1234567L).put(fixture).array()
                peer.sendFragmented(2,0,packet)
                val delivered=videos.poll(10,TimeUnit.SECONDS)
                assertNotNull("Video callback not reached; errors=$errors",delivered)
                assertArrayEquals(fixture,delivered!!.first); assertEquals(1234567L,delivered.second)
                val ack=Media.Ack.parseFrom(peer.receive(2,0x8004))
                assertEquals(47,ack.sessionId); assertEquals(1,ack.ack)
                peer.send(0,11,Control.PingRequest.newBuilder().setTimestamp(9988).build().toByteArray())
                assertEquals(9988L,Control.PingResponse.parseFrom(peer.receive(0,12)).timestamp)
                peer.send(4,0x8000,byteArrayOf())
                assertEquals(4,Media.Config.parseFrom(peer.receive(4,0x8003)).maxUnacked)
                peer.send(4,0x8001,Media.Start.newBuilder().setSessionId(91).setConfigurationIndex(0).build().toByteArray())
                peer.send(4,0,ByteBuffer.allocate(12).putLong(1000L).putInt(0).array())
                val audioConsumed=audioCredits.poll(5,TimeUnit.SECONDS)!!
                // No early audio ACK: control traffic remains responsive while playback waits.
                peer.send(0,11,Control.PingRequest.newBuilder().setTimestamp(9989).build().toByteArray())
                assertEquals(9989L,Control.PingResponse.parseFrom(peer.receive(0,12)).timestamp)
                audioConsumed()
                assertEquals(91,Media.Ack.parseFrom(peer.receive(4,0x8004)).sessionId)
                assertFalse("Do not report a click as sent before input binding",session.key(66))
                assertFalse(session.tap(760,440))
                peer.send(3,0x8002,Input.KeyBindingRequest.newBuilder().addKeycodes(23).addKeycodes(66).addKeycodes(187).addKeycodes(65536).build().toByteArray())
                peer.receive(3,0x8003)
                session.touch(0,400,240)
                val touch=Input.InputReport.parseFrom(peer.receive(3,0x8001)).touchEvent
                assertEquals(400,touch.getPointerData(0).x); assertEquals(240,touch.getPointerData(0).y)
                assertEquals(Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN,touch.action)
                val clickStarted=System.nanoTime()
                assertEquals(66,TripperControls.Action.SELECT.key)
                assertTrue(session.key(TripperControls.Action.SELECT.key))
                val pressed=Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0)
                val released=Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0)
                assertEquals(66,pressed.keycode); assertEquals(66,released.keycode)
                assertTrue(pressed.down); assertFalse(released.down)
                assertTrue(pressed.hasMetastate()); assertEquals(0,pressed.metastate)
                assertTrue(pressed.hasLongpress()); assertFalse(pressed.longpress)
                assertTrue("Click must have a duration",System.nanoTime()-clickStarted>=TimeUnit.MILLISECONDS.toNanos(75))
                assertTrue(session.key(23)) // explicitly selected compatibility mode, never both for one press
                assertEquals(23,Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0).keycode)
                assertFalse(Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0).down)
                assertEquals(true,BikeCommand.AA_APPS.sendToAndroidAuto(session::key,session::rotate))
                val appsDown=Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0)
                val appsUp=Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0)
                assertEquals(187,appsDown.keycode); assertTrue(appsDown.down)
                assertEquals(187,appsUp.keycode); assertFalse(appsUp.down)
                val tapStarted=System.nanoTime()
                assertEquals(true,BikeCommand.AA_DASHBOARD_RIGHT.sendToAndroidAuto(session::key,session::rotate,session::tap))
                // A queued Back must arrive after the full tap, never between DOWN and UP.
                assertTrue(session.key(4))
                val tapDown=Input.InputReport.parseFrom(peer.receive(3,0x8001)).touchEvent
                val tapUp=Input.InputReport.parseFrom(peer.receive(3,0x8001)).touchEvent
                assertEquals(Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN,tapDown.action)
                assertEquals(Input.TouchEvent.PointerAction.TOUCH_ACTION_UP,tapUp.action)
                for(t in listOf(tapDown,tapUp)) {
                    assertEquals(760,t.getPointerData(0).x); assertEquals(440,t.getPointerData(0).y)
                }
                assertTrue(System.nanoTime()-tapStarted>=TimeUnit.MILLISECONDS.toNanos(75))
                assertEquals(4,Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0).keycode)
                assertFalse(Input.InputReport.parseFrom(peer.receive(3,0x8001)).keyEvent.getKeys(0).down)
                session.rotate(-1)
                val previous=Input.InputReport.parseFrom(peer.receive(3,0x8001)).relativeEvent.getData(0)
                assertEquals(65536,previous.keycode); assertEquals(-1,previous.delta)
                session.rotate(1)
                assertEquals(1,Input.InputReport.parseFrom(peer.receive(3,0x8001)).relativeEvent.getData(0).delta)
                // More than the 15-second read timeout without video/music: heartbeat
                // responses keep the connection alive, as a static locked display requires.
                repeat(4) {
                    val ping=Control.PingRequest.parseFrom(peer.receive(0,11))
                    peer.send(0,12,Control.PingResponse.newBuilder().setTimestamp(ping.timestamp).build().toByteArray())
                }
                assertEquals(1L,done.count)
                session.close()
                assertFalse(session.key(66))
                assertFalse(session.tap(760,440))
                assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(errors.toString(),errors.isEmpty())
            }
        } finally { session.close(); server.close() }
    }

    private class Peer(socket:Socket,key:SingleKeyKeyManager) {
        private val input=socket.getInputStream()
        private val output=socket.getOutputStream()
        private val tls=SSLContext.getInstance("TLSv1.2").apply {
            init(arrayOf(key),arrayOf(object:X509TrustManager {
                override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
                override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?) {}
                override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?) {}
            }),null)
        }.createSSLEngine().apply { useClientMode=false; needClientAuth=true; enabledProtocols=arrayOf("TLSv1.2") }
        private fun type(bytes:ByteArray)=((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        private fun message(type:Int,bytes:ByteArray)=byteArrayOf((type shr 8).toByte(),type.toByte())+bytes
        private fun plain(type:Int,bytes:ByteArray)=AaWire.write(output,0,3,message(type,bytes))
        fun authenticate() {
            assertEquals(1,type(AaWire.read(input).payload))
            plain(2,byteArrayOf(0,1,0,2,0,0))
            tls.beginHandshake()
            var pending=ByteBuffer.allocate(0)
            handshake@ while(true) {
                when(tls.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> while(true) { val t=tls.delegatedTask ?: break; t.run() }
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val dest=ByteBuffer.allocate(65536)
                        val result=tls.wrap(ByteBuffer.allocate(0),dest)
                        assertEquals(SSLEngineResult.Status.OK,result.status)
                        dest.flip(); if(dest.hasRemaining()) plain(3,ByteArray(dest.remaining()).also { dest.get(it) })
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if(!pending.hasRemaining()) {
                            val frame=AaWire.read(input); assertEquals(3,type(frame.payload))
                            pending=ByteBuffer.wrap(frame.payload.copyOfRange(2,frame.payload.size))
                        }
                        val result=tls.unwrap(pending,ByteBuffer.allocate(65536))
                        assertEquals(SSLEngineResult.Status.OK,result.status)
                    }
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,SSLEngineResult.HandshakeStatus.FINISHED -> break@handshake
                    else -> error("Unexpected server TLS state")
                }
            }
            val auth=AaWire.read(input); assertEquals(4,type(auth.payload))
            assertArrayEquals(byteArrayOf(8,0),auth.payload.copyOfRange(2,auth.payload.size))
        }
        private fun encrypt(plain:ByteArray):ByteArray {
            val dest=ByteBuffer.allocate(65536); val src=ByteBuffer.wrap(plain)
            assertEquals(SSLEngineResult.Status.OK,tls.wrap(src,dest).status); assertFalse(src.hasRemaining())
            dest.flip(); return ByteArray(dest.remaining()).also { dest.get(it) }
        }
        fun send(channel:Int,type:Int,bytes:ByteArray,control:Boolean=false) {
            AaWire.write(output,channel,11 or if(control) 4 else 0,encrypt(message(type,bytes)))
        }
        fun sendFragmented(channel:Int,type:Int,bytes:ByteArray) {
            val all=message(type,bytes)
            AaWire.write(output,channel,9,encrypt(all.copyOfRange(0,5)),all.size)
            AaWire.write(output,channel,10,encrypt(all.copyOfRange(5,all.size)))
        }
        fun receive(channel:Int,type:Int):ByteArray {
            val f=AaWire.read(input); assertEquals(channel,f.channel); assertTrue(f.flags and 8!=0)
            val dst=ByteBuffer.allocate(65536)
            assertEquals(SSLEngineResult.Status.OK,tls.unwrap(ByteBuffer.wrap(f.payload),dst).status)
            dst.flip(); val data=ByteArray(dst.remaining()).also { dst.get(it) }
            assertEquals(type,type(data)); return data.copyOfRange(2,data.size)
        }
    }
}
