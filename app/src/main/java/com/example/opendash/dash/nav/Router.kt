// Modified for Rota Livre: pt-BR, explicit Internet routing, and navigation correctness.
package com.example.opendash.dash.nav

import com.example.opendash.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a road route from the public OSRM demo server. Called at planning time
 * (destination shared) while the phone still has internet — the result is cached
 * so riding can proceed offline. Driving profile suits the Himalayan fine.
 */
object Router {
    private const val TAG = "Router"
    private const val BASE = "https://router.project-osrm.org/route/v1/driving"
    private const val UA = "RotaLivre/0.1 (Android; personal navigation)"

    suspend fun route(from: GeoPoint, to: GeoPoint): Route? = withContext(Dispatchers.IO) {
        val url = "$BASE/${from.lng},${from.lat};${to.lng},${to.lat}" +
                "?overview=full&geometries=polyline&steps=true&annotations=false"
        try {
            val conn = com.example.opendash.net.Internet.open(url).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = try { conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                finally { conn.disconnect() }
            parse(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.w(TAG) { "route() failed: ${e.message}" }
            null
        }
    }

    private fun parse(json: String): Route? {
        val root = JSONObject(json)
        if (root.optString("code") != "Ok") {
            DebugLog.w(TAG) { "OSRM code=${root.optString("code")}" }
            return null
        }
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r0 = routes.getJSONObject(0)

        val geometry = PolylineCodec.decode(r0.getString("geometry"))
        if (geometry.size < 2) return null

        // Cumulative distance at each vertex
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + GeoPoint.distMeters(geometry[i - 1], geometry[i])
        }

        // Maneuvers from steps
        val maneuvers = ArrayList<Maneuver>()
        val legs = r0.optJSONArray("legs")
        if (legs != null) {
            for (li in 0 until legs.length()) {
                val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
                for (si in 0 until steps.length()) {
                    val step = steps.getJSONObject(si)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val loc = man.optJSONArray("location") ?: continue
                    val p = GeoPoint(loc.getDouble(1), loc.getDouble(0))
                    val type = ManeuverType.fromOsrm(man.optString("type"), man.optString("modifier"))
                    val name = step.optString("name").ifBlank { "via" }
                    maneuvers.add(
                        Maneuver(
                            type = type,
                            instruction = buildInstruction(type, name),
                            location = p,
                            cumulativeMeters = nearestCumulative(p, geometry, cum),
                        )
                    )
                }
            }
        }

        return Route(
            geometry = geometry,
            maneuvers = maneuvers,
            totalMeters = cum.last(),
            totalSeconds = r0.optDouble("duration", 0.0),
            cumulative = cum,
        )
    }

    private fun buildInstruction(type: ManeuverType, road: String): String = when (type) {
        ManeuverType.DEPART       -> "Siga pela $road"
        ManeuverType.ARRIVE       -> "Chegada ao destino"
        ManeuverType.TURN_LEFT    -> "Vire à esquerda: $road"
        ManeuverType.TURN_RIGHT   -> "Vire à direita: $road"
        ManeuverType.SLIGHT_LEFT  -> "Mantenha à esquerda: $road"
        ManeuverType.SLIGHT_RIGHT -> "Mantenha à direita: $road"
        ManeuverType.SHARP_LEFT   -> "Curva fechada à esquerda"
        ManeuverType.SHARP_RIGHT  -> "Curva fechada à direita"
        ManeuverType.UTURN        -> "Faça o retorno"
        ManeuverType.ROUNDABOUT   -> "Na rotatória: $road"
        ManeuverType.CONTINUE     -> "Siga em frente: $road"
    }

    /** Cumulative distance of the geometry vertex nearest to a maneuver location. */
    private fun nearestCumulative(p: GeoPoint, geom: List<GeoPoint>, cum: DoubleArray): Double {
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in geom.indices) {
            val d = GeoPoint.distMeters(p, geom[i])
            if (d < bestD) { bestD = d; best = cum[i] }
        }
        return best
    }
}
