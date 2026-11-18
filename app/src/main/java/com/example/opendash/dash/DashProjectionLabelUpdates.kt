package com.example.opendash.dash

import com.example.opendash.dash.protocol.DashCommands

/** Update the outside strip promptly without reopening the native navigation card. */
class DashProjectionLabelUpdates {
    private var previous:String?=null
    private var sentAt=0L
    fun next(label:String?,now:Long):ByteArray? {
        if(label.isNullOrBlank()) { previous=null; return null }
        if(label==previous && now-sentAt<5_000) return null
        previous=label; sentAt=now
        return DashCommands.projectionLabel(label)
    }
}
