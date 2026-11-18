package com.example.opendash.auto

import kotlin.math.abs
import kotlin.math.exp

/** PCM16 LE. Only the advertised SPEECH sink is amplified, regardless of who launched media. */
internal object VoiceGain {
    const val PREFERENCE="voice-gain"
    const val DEFAULT=150
    const val MIN=100
    const val MAX=250

    fun apply(channel:Int,pcm:ByteArray,percent:Int):ByteArray {
        val gain=percent.coerceIn(MIN,MAX)/100.0
        if(channel!=4 || gain==1.0) return pcm
        val output=pcm.copyOf()
        for(i in 0 until pcm.size-1 step 2) {
            val sample=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt()
            val level=abs(sample.toDouble())*gain
            // Unity slope at the knee; compress large peaks instead of PCM integer overflow.
            val limited=if(level<=24000.0) level else 24000.0+8767.0*(1.0-exp(-(level-24000.0)/8767.0))
            val value=(if(sample<0) -limited else limited).toInt().coerceIn(-32768,32767)
            output[i]=value.toByte(); output[i+1]=(value shr 8).toByte()
        }
        return output
    }
}
