package com.example.opendash.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.opendash.net.Internet
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Rota Livre: visible tiles only, explicit identity, seven-day cache, bounded concurrency. */
class TileProvider(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.cacheDir, "osm-visible").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(80)
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val slots = Semaphore(2)
    private val prefs = context.getSharedPreferences("rota", Context.MODE_PRIVATE)
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (z !in 1..19 || y !in 0 until n) return null
        val xx = ((x % n) + n) % n
        val key = "${z}_${xx}_$y"
        memory.get(key)?.let { return it }
        if (System.currentTimeMillis() < (retryAfter[key] ?: 0)) return null
        if (loading.size >= 24 || !loading.add(key)) return null
        scope.launch(Dispatchers.IO) {
            try { slots.withPermit {
                val file = File(dir, "$key.png")
                val cached = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                cached?.let { memory.put(key, it) }
                val age = System.currentTimeMillis() - file.lastModified()
                if (cached != null && age < 7L * 24 * 60 * 60 * 1000) return@withPermit
                val base = prefs.getString("tiles", "https://tile.openstreetmap.org/{z}/{x}/{y}.png")!!
                val conn = Internet.open(base.replace("{z}", "$z").replace("{x}", "$xx").replace("{y}", "$y"))
                try {
                    if (file.exists()) conn.ifModifiedSince = file.lastModified()
                    when (conn.responseCode) {
                        304 -> file.setLastModified(System.currentTimeMillis())
                        200 -> {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                                file.writeBytes(bytes); memory.put(key, it)
                            }
                        }
                        else -> retryAfter[key] = System.currentTimeMillis() + 60_000
                    }
                } finally { conn.disconnect() }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { retryAfter[key] = System.currentTimeMillis() + 30_000 }
            finally { loading.remove(key) }
        }
        return null
    }
}
