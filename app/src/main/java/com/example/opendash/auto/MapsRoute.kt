package com.example.opendash.auto

import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/** Resolve Google map destinations, then request motorized two-wheeler directions only. */
internal object MapsRoute {
    const val PACKAGE="com.google.android.apps.maps"
    private val link=Regex("(?:https?://|geo:|google\\.navigation:)[^\\s<>]+",RegexOption.IGNORE_CASE)
    private val placeCoordinates=Regex("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)")
    private fun mapsLink(uri:Uri):Boolean {
        if(uri.scheme?.lowercase() !in setOf("http","https") || uri.userInfo!=null || uri.port !in listOf(-1,80,443)) return false
        val host=uri.host?.lowercase() ?: return false
        val path=uri.path.orEmpty()
        return host in setOf("maps.app.goo.gl","maps.google.com","maps.google.com.br") ||
            (host=="goo.gl" && path.startsWith("/maps/")) ||
            (host in setOf("google.com","www.google.com","google.com.br","www.google.com.br") && (path=="/maps" || path.startsWith("/maps/")))
    }
    private fun supported(value:String):Boolean {
        if(value.isBlank()) return false
        val uri=Uri.parse(value)
        return uri.scheme==null || mapsLink(uri) || (uri.scheme in setOf("geo","google.navigation") && intent(value)!=null)
    }
    fun sharedText(text:String):String? {
        val trimmed=text.trim()
        if(Uri.parse(trimmed).scheme!=null && supported(trimmed)) return trimmed
        val links=link.findAll(text).toList()
        if(links.isNotEmpty()) return links.map { it.value.trimEnd('.',',') }.firstOrNull(::supported)
        return trimmed.takeIf(::supported)
    }
    private fun coordinates(value:String):String? {
        val pair=value.split(',').take(2)
        return pair.takeIf { it.size==2 && it[0].toDoubleOrNull()?.let { v -> v in -90.0..90.0 }==true && it[1].toDoubleOrNull()?.let { v -> v in -180.0..180.0 }==true }?.joinToString(",")
    }
    private fun directions(destination:String,extras:Map<String,String> = emptyMap()):Intent {
        val builder=Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api","1").appendQueryParameter("destination",destination)
            .appendQueryParameter("travelmode","two-wheeler").appendQueryParameter("dir_action","navigate")
        for((name,value) in extras) builder.appendQueryParameter(name,value)
        return Intent(Intent.ACTION_VIEW,builder.build()).setPackage(PACKAGE)
    }
    /** Null means the link does not yet identify a destination, never an unrestricted Maps fallback. */
    fun intent(value:String):Intent? {
        val text=value.trim()
        if(text.isBlank()) return null
        val uri=Uri.parse(text)
        return when(uri.scheme?.lowercase()) {
            null -> directions(text)
            "geo" -> {
                val part=uri.encodedSchemeSpecificPart
                val query=if('?' in part) Uri.parse("https://local.invalid/?${part.substringAfter('?')}").getQueryParameter("q") else null
                directions(query?.takeIf { it.isNotBlank() } ?: coordinates(uri.schemeSpecificPart.substringBefore('?')) ?: return null)
            }
            "google.navigation" -> {
                val query=Uri.parse("https://local.invalid/?${uri.encodedSchemeSpecificPart}").getQueryParameter("q")
                directions(query?.takeIf { it.isNotBlank() } ?: return null)
            }
            "https","http" -> {
                if(!mapsLink(uri)) return null
                val destination=listOf("destination","daddr","q","query").firstNotNullOfOrNull { name -> uri.getQueryParameter(name)?.takeIf { it.isNotBlank() } }
                if(destination!=null) {
                    val extras=listOf("destination_place_id","origin","origin_place_id","waypoints","waypoint_place_ids","avoid")
                        .mapNotNull { name -> uri.getQueryParameter(name)?.let { name to it } }.toMap().toMutableMap()
                    uri.getQueryParameter("query_place_id")?.let { extras["destination_place_id"]=it }
                    if(destination.startsWith("place_id:")) return null
                    directions(destination,extras)
                } else {
                    // Coordinates embedded in place data identify the pin; @lat,long only frames the map.
                    val point=placeCoordinates.findAll(Uri.decode(text)).lastOrNull()?.let { coordinates("${it.groupValues[1]},${it.groupValues[2]}") }
                    if(point!=null) directions(point) else null
                }
            }
            else -> null
        }
    }
    /** No cookies, page body, credentials or history; only bounded Google redirects. Call off the UI thread. */
    fun resolve(value:String,redirect:(Uri)->String? = ::redirectLocation):Intent? {
        var current=value.trim()
        val visited=mutableSetOf<String>()
        repeat(6) {
            intent(current)?.let { return it }
            val uri=Uri.parse(current)
            if(!mapsLink(uri) || !visited.add(current)) return null
            val nested=uri.getQueryParameter("link")
            current=if(nested!=null && mapsLink(Uri.parse(nested))) nested else redirect(uri) ?: return null
        }
        return intent(current)
    }
    private fun redirectLocation(uri:Uri):String? {
        val https=uri.buildUpon().scheme("https").build().toString()
        val connection=URL(https).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=3500; connection.readTimeout=3500
            connection.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
            if(connection.responseCode in listOf(301,302,303,307,308)) {
                val location=connection.getHeaderField("Location") ?: return null
                URL(URL(https),location).toString().takeIf { mapsLink(Uri.parse(it)) }
            } else null
        } finally { connection.disconnect() }
    }
}