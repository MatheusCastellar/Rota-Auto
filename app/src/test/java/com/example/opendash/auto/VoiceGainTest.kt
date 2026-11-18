package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VoiceGainTest {
    private fun pcm(vararg samples:Int)=ByteArray(samples.size*2).also { b ->
        samples.forEachIndexed { i,s -> b[i*2]=s.toByte(); b[i*2+1]=(s shr 8).toByte() }
    }
    private fun values(b:ByteArray)=(0 until b.size-1 step 2).map {
        ((b[it].toInt() and 255) or (b[it+1].toInt() shl 8)).toShort().toInt()
    }
    @Test fun originalLevelIsBitExactAndOtherChannelsAreNeverAmplified() {
        val input=pcm(-32768,-5000,0,5000,32767)
        assertSame(input,VoiceGain.apply(4,input,100))
        for(channel in listOf(0,2,5,6,7)) assertSame(input,VoiceGain.apply(channel,input,250))
    }
    @Test fun quietSpeechGainsLevelWithoutChangingInputOrSilence() {
        val input=pcm(-2000,0,2000)
        val before=input.copyOf()
        assertEquals(listOf(-3000,0,3000),values(VoiceGain.apply(4,input,150)))
        assertArrayEquals(before,input)
        assertArrayEquals(ByteArray(320),VoiceGain.apply(4,ByteArray(320),250))
    }
    @Test fun fullScalePeaksStayBoundedMonotonicAndNeverWrapSign() {
        val ramp=(-32768..32767).toList().toIntArray()
        val out=values(VoiceGain.apply(4,pcm(*ramp),250))
        assertTrue(out.zipWithNext().all { (a,b) -> a<=b })
        assertTrue(out.all { abs(it)<=32767 })
        assertTrue(out.first()<0); assertTrue(out.last()>0)
        assertEquals(0,out[32768])
        assertTrue(out.last()>30000)
    }
    @Test fun packetBoundariesDoNotChangeTheWaveform() {
        val input=pcm(*IntArray(2000) { (it*111)%65536-32768 })
        val pieces=input.asList().chunked(128).flatMap { VoiceGain.apply(4,it.toByteArray(),175).asList() }.toByteArray()
        assertArrayEquals(VoiceGain.apply(4,input,175),pieces)
    }
    @Test fun outOfRangePreferencesAreClampedAndIncompleteTailIsUntouched() {
        val input=pcm(-32768,500,32767)+byteArrayOf(123)
        assertSame(input,VoiceGain.apply(4,input,-1))
        assertArrayEquals(VoiceGain.apply(4,input,250),VoiceGain.apply(4,input,Int.MAX_VALUE))
        assertEquals(123.toByte(),VoiceGain.apply(4,input,200).last())
        assertTrue(VoiceGain.apply(4,byteArrayOf(),200).isEmpty())
    }
}
