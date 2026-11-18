package com.example.opendash.auto

import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.assertEquals
import org.junit.Test

class AaVideoFocusTest {
    @Test fun phoneScreenOffKeepsTheIndependentMotorcycleDisplayProjected() {
        for(mode in Media.VideoFocusMode.values()) {
            val request=Media.VideoFocusRequestNotification.newBuilder().setMode(mode)
                .setReason(Media.VideoFocusRequestNotification.VideoFocusReason.PHONE_SCREEN_OFF).build()
            assertEquals(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED,AaVideoFocus.response(request))
        }
    }
    @Test fun explicitNativeSelectionAndTransientAndNoInputModesArePreserved() {
        for(mode in Media.VideoFocusMode.values()) {
            for(reason in Media.VideoFocusRequestNotification.VideoFocusReason.values().filter {
                it!=Media.VideoFocusRequestNotification.VideoFocusReason.PHONE_SCREEN_OFF
            }) {
                val request=Media.VideoFocusRequestNotification.newBuilder().setMode(mode).setReason(reason).build()
                assertEquals(mode,AaVideoFocus.response(request))
            }
        }
    }
}
