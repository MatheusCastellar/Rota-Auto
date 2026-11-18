package com.example.opendash.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/** Only dash UDP sockets use the bike Wi-Fi. HTTP selects a validated Internet network. */
object Internet {
    private var app: Context? = null
    fun init(context: Context) { app = context.applicationContext }
    fun open(url: String): HttpURLConnection {
        require(URL(url).protocol == "https")
        val cm = app?.getSystemService(ConnectivityManager::class.java)
        val internet = cm?.allNetworks?.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
        return ((internet?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "RotaLivre/0.2 (Android; personal Tripper Dash companion)")
        }
    }
}
