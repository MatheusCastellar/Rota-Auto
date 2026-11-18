package com.example.opendash.auto

internal object MapDayNight {
    const val KEY="map-day-night"
    val modes=listOf("AUTO","DAY","NIGHT")
    fun isNight(mode:String?,hour:Int):Boolean=when(mode) {
        "DAY" -> false
        "NIGHT" -> true
        else -> hour<6 || hour>=18
    }
    fun current(prefs:android.content.SharedPreferences):Boolean =
        isNight(prefs.getString(KEY,"AUTO"),java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
}
