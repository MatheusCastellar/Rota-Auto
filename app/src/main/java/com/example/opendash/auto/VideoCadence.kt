package com.example.opendash.auto

/** Keep the last image alive at 2 fps; use the selected rate for new decoded images. */
class VideoCadence(private var intervalMs:Long) {
    private var dirty=true
    private var lastSent:Long?=null
    fun setInterval(value:Long) { require(value>0); intervalMs=value; changed() }
    fun changed() { dirty=true }
    fun take(now:Long):Boolean {
        val previous=lastSent
        if(previous!=null && now-previous<(if(dirty) intervalMs else 500L)) return false
        lastSent=now; dirty=false
        return true
    }
}
