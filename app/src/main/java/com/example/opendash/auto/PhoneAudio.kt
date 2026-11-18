package com.example.opendash.auto

import android.content.Context

/** Session settings; changing these takes effect at the next projection start. */
internal object PhoneAudio {
    const val OUTPUT="phone-audio-output"
    const val INPUT="phone-audio-input"
    fun output(context:Context)=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).getBoolean(OUTPUT,true)
    fun input(context:Context)=context.getSharedPreferences("auto-controls-options",Context.MODE_PRIVATE).getBoolean(INPUT,false)
}
