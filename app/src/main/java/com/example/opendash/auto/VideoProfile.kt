package com.example.opendash.auto

data class VideoProfile(val fps:Int,val bitrate:Int,val automatic:Boolean=false) {
    val intervalMs:Long get()=1000L/fps
    companion object {
        const val BITRATE="video-kbps"
        const val MANUAL="video-manual-bitrate"
        fun manual(p:android.content.SharedPreferences)=p.getBoolean(MANUAL,p.contains(BITRATE))
        fun selectFps(p:android.content.SharedPreferences,fps:Int) {
            val standard=forFps(fps)
            // Preserve the switch before writing BITRATE (legacy backups infer manual from that key).
            p.edit().putBoolean(MANUAL,manual(p)).putInt("video-fps",standard.fps)
                .putInt(BITRATE,standard.bitrate/1000).apply()
        }
        fun restore(p:android.content.SharedPreferences) { p.edit().putInt("video-fps",4).putBoolean(MANUAL,false).remove(BITRATE).apply() }
        fun fromPreferences(p:android.content.SharedPreferences):VideoProfile {
            val standard=forFps(p.getInt("video-fps",4))
            return if(manual(p)) standard.copy(bitrate=p.getInt(BITRATE,standard.bitrate/1000).coerceIn(100,2000)*1000)
                else standard.copy(automatic=true)
        }
        fun forFps(value:Int)=when(value) {
            4 -> VideoProfile(4,200_000)
            8 -> VideoProfile(8,400_000)
            else -> VideoProfile(12,600_000)
        }
    }
}
