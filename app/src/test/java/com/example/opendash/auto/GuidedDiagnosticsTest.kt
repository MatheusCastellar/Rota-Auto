package com.example.opendash.auto

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GuidedDiagnosticsTest {
    @Test fun stagesNeedExplicitObservationAndExportKeepsRawPacketsAfterFinish() {
        val context=RuntimeEnvironment.getApplication()
        GuidedDiagnostics.finish()
        GuidedDiagnostics.start(context)
        try {
            assertTrue(BikeInputCapture.blocksActions)
            GuidedDiagnostics.beginStep()
            val initial=GuidedDiagnostics.index
            GuidedDiagnostics.button(byteArrayOf(0x18))
            GuidedDiagnostics.packet("RX test",byteArrayOf(0x09,0x06,0x55))
            assertEquals(initial,GuidedDiagnostics.index) // No inference from a button.
            GuidedDiagnostics.answer("too early")
            assertEquals(initial,GuidedDiagnostics.index)
            GuidedDiagnostics.answer("PULADA",skip=true)
            assertEquals(initial+1,GuidedDiagnostics.index)
            GuidedDiagnostics.repeat(); assertFalse(GuidedDiagnostics.measuring)
            GuidedDiagnostics.finish("TESTE TERMINADO")
            assertFalse(BikeInputCapture.blocksActions); assertNull(BikeInputCapture.state.value.glyph)
            val output=ByteArrayOutputStream(); GuidedDiagnostics.exportTo(output)
            val saved=output.toString("UTF-8")
            assertTrue(saved.contains("090655")); assertTrue(saved.contains("BOTÃO 18"))
            assertTrue(saved.contains("PULADA")); assertTrue(saved.contains("TESTE TERMINADO"))
            assertTrue(saved.contains("registros recusados=0"))
        } finally { GuidedDiagnostics.finish() }
    }
    @Test fun diagnosticScreenCanReopenWithoutStartingOrDiscardingRecording() {
        val context=RuntimeEnvironment.getApplication()
        GuidedDiagnostics.start(context)
        val activity=Robolectric.buildActivity(DiagnosticActivity::class.java).setup()
        try { assertTrue(GuidedDiagnostics.active) }
        finally { activity.pause().stop().destroy(); GuidedDiagnostics.finish() }
    }
}
