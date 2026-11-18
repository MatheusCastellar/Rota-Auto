package com.example.opendash.auto

/** Monotonic time; only traditional controls opt in. */
internal class ProfileIdleReturn {
    companion object { const val KEY="profile-idle-return"; const val DELAY=15_000L }
    var deadline:Long?=null
        private set
    fun reset(now:Long,enabled:Boolean,menu:Boolean,profile:Int) {
        deadline=if(enabled && !menu && profile!=1) now+DELAY else null
    }
    fun expired(now:Long):Boolean=deadline?.let { now>=it }==true
}
