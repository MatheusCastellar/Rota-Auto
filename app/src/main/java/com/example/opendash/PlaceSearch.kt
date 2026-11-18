package com.example.opendash

import android.content.Context
import android.os.SystemClock
import com.example.opendash.dash.map.Mercator
import com.example.opendash.dash.nav.GeoPoint
import com.example.opendash.net.Internet
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

data class Place(val name:String,val address:String,val lat:Double,val lng:Double,val meters:Double?=null)

/** Explicit searches only, serialized and cached. No account or SDK key. */
class PlaceSearch(context:Context) {
    private val cache=File(context.cacheDir,"places").apply { mkdirs() }
    private val prefs=context.getSharedPreferences("rota",Context.MODE_PRIVATE)
    companion object {
        private val mutex=Mutex()
        private var lastRequest=0L
        val categories=linkedMapOf("Postos de combustível" to "amenity:fuel", "Restaurantes" to "amenity:restaurant",
            "Cafés" to "amenity:cafe", "Hospedagem" to "tourism:hotel", "Farmácias" to "amenity:pharmacy", "Hospitais" to "amenity:hospital")
        fun parse(json:String,origin:GeoPoint?=null):List<Place> {
            val features=JSONObject(json).getJSONArray("features")
            return (0 until features.length()).mapNotNull { i -> runCatching {
                val f=features.getJSONObject(i); val xy=f.getJSONObject("geometry").getJSONArray("coordinates")
                val lat=xy.getDouble(1); val lng=xy.getDouble(0)
                require(lat in -85.0..85.0 && lng in -180.0..180.0)
                val p=f.getJSONObject("properties")
                val street=listOf(p.optString("street"),p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(", ")
                val address=listOf(street,p.optString("district"),p.optString("city"),p.optString("state"),p.optString("country"))
                    .filter { it.isNotBlank() }.distinct().joinToString(" · ")
                Place(p.optString("name").ifBlank { street.ifBlank { p.optString("city").ifBlank { "Local no mapa" } } },address,lat,lng,
                    origin?.let { Mercator.haversineKm(it.lat,it.lng,lat,lng)*1000 })
            }.getOrNull() }.distinctBy { Triple(it.name,it.lat,it.lng) }
        }
    }
    suspend fun search(query:String):List<Place> {
        require(query.trim().length in 2..200) { "Digite pelo menos duas letras e inclua a cidade." }
        return parse(fetch("api/?q=${enc(query.trim())}&limit=10"))
    }
    suspend fun nearby(category:String,origin:GeoPoint):List<Place> {
        val tag=categories[category] ?: error("Categoria inválida")
        val json=fetch("reverse?lat=${origin.lat}&lon=${origin.lng}&radius=10&limit=20&osm_tag=${enc(tag)}")
        return parse(json,origin).filter { (it.meters ?: Double.MAX_VALUE)<=10_000 }.sortedBy { it.meters }
    }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private suspend fun fetch(path:String):String=withContext(Dispatchers.IO) { mutex.withLock {
        val base=prefs.getString("places_url","https://photon.komoot.io")!!.trimEnd('/')
        val url="$base/$path"
        val key=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file=File(cache,"$key.json")
        if(file.exists() && System.currentTimeMillis()-file.lastModified()<24*60*60*1000) return@withLock file.readText()
        delay((1100-(SystemClock.elapsedRealtime()-lastRequest)).coerceAtLeast(0))
        lastRequest=SystemClock.elapsedRealtime()
        val conn=Internet.open(url)
        try {
            conn.setRequestProperty("Accept-Language","pt-BR,pt;q=0.9")
            when(conn.responseCode) {
                200 -> {
                    val bytes=conn.inputStream.use { input ->
                        val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(out.size()<=2_000_000) {
                            val count=input.read(buffer); if(count<0) break
                            out.write(buffer,0,count)
                        }; out.toByteArray()
                    }
                    require(bytes.size<=2_000_000) { "Resposta muito grande. Refine a busca." }
                    val text=bytes.toString(Charsets.UTF_8); JSONObject(text).getJSONArray("features")
                    if((cache.listFiles()?.size ?: 0)>100) cache.listFiles()?.minByOrNull { it.lastModified() }?.delete()
                    file.writeText(text); text
                }
                429 -> error("Busca ocupada. Aguarde um pouco antes de tentar novamente.")
                else -> error("O serviço de busca não respondeu. Tente novamente em instantes.")
            }
        } finally { conn.disconnect() }
    } }
}
