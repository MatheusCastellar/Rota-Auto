package com.example.opendash.auto

/** Bounded recovery after an established video session; never repeatedly prompt/start an absent server. */
class ProjectionRetry {
    private var used=0
    private var videoSince:Long?=null
    private var recovering=false
    fun manualStart() { used=0; videoSince=null; recovering=false }
    fun video(now:Long) { if(videoSince==null) videoSince=now }
    fun nextDelay(now:Long,retryable:Boolean):Long? {
        val since=videoSince
        if(since!=null && now-since>=60_000) used=0
        videoSince=null
        if(!retryable || (since==null && !recovering) || used>=3) { recovering=false; return null }
        recovering=true
        return longArrayOf(3000,8000,15000)[used++]
    }
}
