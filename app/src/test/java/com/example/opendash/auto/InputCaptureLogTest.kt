package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test

class InputCaptureLogTest {
    @Test fun recordsUnknownAndRepeatedRawInputsWithTimingAndStopsExactly() {
        val log=InputCaptureLog()
        log.record(10,byteArrayOf(9),1); assertEquals(0,log.count)
        log.start(100,"test")
        log.record(150,byteArrayOf(0,0xff.toByte()),2)
        log.note(150,"DESCONHECIDO")
        log.record(170,byteArrayOf(0,0xff.toByte()),2)
        log.note(170,"filtrado=true")
        log.stop(200)
        val stopped=log.snapshot()
        log.record(210,byteArrayOf(9),3); log.note(211,"ignored"); log.stop(212)
        assertEquals(stopped,log.snapshot()); assertEquals(2,log.count)
        assertTrue(stopped.contains("hex=00 FF")); assertTrue(stopped.contains("intervalo=20 ms"))
        assertTrue(stopped.contains("perfil=2")); assertTrue(stopped.contains("filtrado=true"))
        assertTrue(stopped.contains("+100 ms | STOP")); assertFalse(log.active)
    }
    @Test fun boundedCaptureCountsDroppedLinesAndNewStartClearsOldInputs() {
        val log=InputCaptureLog(4); log.start(0,"first")
        repeat(10) { log.record(it.toLong(),byteArrayOf(it.toByte()),1) }
        assertEquals(10,log.count); assertEquals(7,log.discarded)
        assertFalse(log.snapshot().contains("hex=00")); assertTrue(log.snapshot().contains("hex=09"))
        log.stop(20); log.start(30,"second")
        assertEquals(0,log.count); assertEquals(0,log.discarded)
        assertFalse(log.snapshot().contains("first")); assertFalse(log.snapshot().contains("código="))
        log.record(40,byteArrayOf(9),3)
        assertTrue(log.snapshot().contains("intervalo=— ms"))
    }
    @Test fun emptyAndMaximumDatagramsAreCapturedInFullWithSourceAndContext() {
        val log=InputCaptureLog(); log.start(0,"test")
        log.record(10,byteArrayOf(),1); assertEquals(1,log.count)
        log.record(20,ByteArray(65535) { 0xff.toByte() },1,"192.168.1.1:2000","símbolo=90")
        assertEquals(2,log.count); assertTrue(log.snapshot().length<200000)
        assertFalse(log.snapshot().contains("…")); assertTrue(log.snapshot().contains("192.168.1.1:2000"))
        assertTrue(log.snapshot().contains("símbolo=90")); assertEquals(65535,Regex("FF").findAll(log.snapshot()).count())
    }
    @Test fun byteBudgetEvictsWholeRecordsWithoutTruncatingRetainedPackets() {
        val log=InputCaptureLog(characterLimit=400); log.start(0,"test")
        repeat(100) { n -> log.record(n.toLong(),ByteArray(32) { n.toByte() },1) }
        assertEquals(100,log.count); assertTrue(log.discarded>0)
        assertTrue(log.snapshot().length<1000)
        assertEquals(32,Regex("63").findAll(log.snapshot().substringAfterLast("hex=")).count())
    }
}
