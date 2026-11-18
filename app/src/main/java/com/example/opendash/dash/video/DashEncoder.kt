// Modified for Rota Livre: codec diagnostics, strict hardware selection, buffer offsets and fail-fast rendering.
package com.example.opendash.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.example.opendash.util.DebugLog

/**
 * MediaCodec H.264 encoder for the Tripper Dash stream:
 *   526 x 300, 2-4 fps, ~200 kbps, Baseline L4.1, 1-second IDR interval.
 *
 * [FPS] is the maximum encoder hint. The frame loop feeds 4 fps while moving and
 * throttles to 2 fps when stopped, matching the stable RE projection envelope.
 * The hardware encoder auto-timestamps each frame from the input surface, so the
 * variable feed rate is fine. Tiny resolution → still far under the OLED-projection
 * draw this whole project exists to avoid.
 *
 * Frames are drawn with Android Canvas via the input Surface's hardware
 * canvas — call [renderFrame] with a draw lambda, then [drain] to pull
 * encoded NAL data out.
 *
 * @param onEncodedData  called with (annexBBytes, isKeyFrame) for each output buffer.
 */
class DashEncoder(private val onEncodedData: (ByteArray, Boolean) -> Unit) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        const val FPS     = 4
        const val BITRATE = 200_000
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val TAG = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    /** Codec PTS is preserved through RTP; never replace it with the time a buffer was drained. */
    var onPresentationTimeUs: ((Long) -> Unit)? = null
    var encoderName: String = ""
        private set
    var rateModeLabel:String="padrão do celular"; private set
    private var inputSurface: Surface? = null
    fun surfaceForGl():Surface = inputSurface ?: error("Codificador não preparado")

    fun prepare(fps:Int=FPS,bitrate:Int=BITRATE) {
        require(fps in 1..30 && bitrate in 100_000..2_000_000)
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        // Prefer an explicit HARDWARE AVC encoder — a software fallback runs the
        // CPU hot, which is exactly what this whole project exists to avoid.
        val name = selectHardwareEncoder(format) ?: error("Nenhum codificador H.264 por hardware compatível")
        val capabilities=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name==name }
            .getCapabilitiesForType(MIME).encoderCapabilities
        // Both preset and custom targets use VBR. Neither is a hard cap or Wi-Fi adaptation.
        val mode=MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        rateModeLabel="padrão do celular"
        if(capabilities?.isBitrateModeSupported(mode)==true) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,mode)
            rateModeLabel="VBR"
        }
        val c=MediaCodec.createByCodecName(name)
        codec=c
        DebugLog.i(TAG) { "Encoder: ${c.name}" }
        encoderName = c.name
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
    }

    /** MediaCodec supports changing its target bitrate without replacing the input surface. */
    fun setBitrate(bitrate:Int) {
        require(bitrate in 100_000..2_000_000)
        checkNotNull(codec).setParameters(android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,bitrate)
        })
    }

    /** Find a hardware AVC encoder that supports this format; null → let the OS pick. */
    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // First try true hardware-accelerated encoders (API 29+ flag).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
                if (info.isHardwareAccelerated && !info.isSoftwareOnly &&
                    runCatching { info.getCapabilitiesForType(MIME).isFormatSupported(format) }.getOrDefault(false)) {
                    return info.name
                }
            }
        }
        // Fallback: the OS-preferred encoder for this format.
        return null
    }

    /** Draw one frame into the encoder via hardware canvas. */
    fun renderFrame(draw: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            throw IllegalStateException("Falha ao desenhar no codificador", e)
        }
        try {
            draw(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Pull all available encoded buffers; call after every [renderFrame]. */
    fun drain(timeoutUs:Long=DRAIN_TIMEOUT_US) {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    DebugLog.i(TAG) { "Encoder output: ${codec.outputFormat}" }
                    for(key in listOf("csd-0","csd-1")) codec.outputFormat.getByteBuffer(key)?.duplicate()?.let { data ->
                        val bytes=ByteArray(data.remaining()); data.get(bytes); onEncodedData(bytes,false)
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    // Pass EVERY buffer through, including CODEC_CONFIG (SPS/PPS) — the
                    // NAL processor needs the parameter sets to bundle them with each IDR,
                    // otherwise the dash can't initialise its decoder and times out.
                    if (info.size > 0) {
                        if((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) onPresentationTimeUs?.invoke(info.presentationTimeUs)
                        if((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) com.example.opendash.ConnectionDiagnostics.encoded.incrementAndGet()
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
