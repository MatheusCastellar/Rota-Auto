package com.example.opendash.auto

import com.andrerinas.openheadunit.aap.protocol.proto.Media

/** The motorcycle display remains active independently of the phone display.
 * This only handles an explicit focus request; it cannot prevent a peer from closing TCP.
 */
internal object AaVideoFocus {
    fun response(request:Media.VideoFocusRequestNotification):Media.VideoFocusMode =
        if(request.reason==Media.VideoFocusRequestNotification.VideoFocusReason.PHONE_SCREEN_OFF)
            Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED
        else request.mode
}
