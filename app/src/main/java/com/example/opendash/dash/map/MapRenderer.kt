// Modified for Rota Livre: pt-BR, explicit Internet routing, and navigation correctness.
package com.example.opendash.dash.map

import android.graphics.*
import com.example.opendash.dash.nav.GeoPoint
import kotlin.math.floor

/** Rota Livre renderer: own guidance card, no unverified native maneuver glyphs. */
class MapRenderer(private val tiles: TileProvider) {
    data class Frame(
        val lat: Double, val lng: Double, val bearing: Float = 0f, val zoom: Int = 16,
        val route: List<GeoPoint> = emptyList(), val instruction: String = "Mapa livre",
        val detail: String = "", val gpsGood: Boolean = true, val night: Boolean = false,
    )
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val nightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -.65f,0f,0f,0f,180f, 0f,-.65f,0f,0f,180f, 0f,0f,-.65f,0f,180f, 0f,0f,0f,1f,0f
    )))
    fun draw(c: Canvas, f: Frame) {
        c.drawColor(if (f.night) Color.rgb(28,34,33) else Color.rgb(225,230,222))
        val cx = 263f; val cy = 168f
        val tx = Mercator.lngToTileX(f.lng, f.zoom)
        val ty = Mercator.latToTileY(f.lat.coerceIn(-85.0,85.0), f.zoom)
        c.save(); c.rotate(-f.bearing, cx, cy)
        tilePaint.colorFilter = if (f.night) nightFilter else null
        for (dx in -2..2) for (dy in -2..2) {
            val x = floor(tx).toInt() + dx; val y = floor(ty).toInt() + dy
            val left = cx + ((x-tx)*256).toFloat(); val top = cy + ((y-ty)*256).toFloat()
            if (left > 570 || left+256 < -44 || top > 460 || top+256 < -130) continue
            tiles.get(f.zoom,x,y)?.let { c.drawBitmap(it,left,top,tilePaint) }
        }
        if (f.route.isNotEmpty()) {
            path.reset()
            f.route.forEachIndexed { i, point ->
                val x = cx + ((Mercator.lngToTileX(point.lng,f.zoom)-tx)*256).toFloat()
                val y = cy + ((Mercator.latToTileY(point.lat,f.zoom)-ty)*256).toFloat()
                if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            p.style=Paint.Style.STROKE; p.strokeJoin=Paint.Join.ROUND; p.strokeCap=Paint.Cap.ROUND
            p.color=Color.WHITE; p.strokeWidth=11f; c.drawPath(path,p)
            p.color=Color.rgb(0,110,240); p.strokeWidth=7f; c.drawPath(path,p)
        }
        c.restore(); p.style=Paint.Style.FILL
        path.reset(); path.moveTo(cx,cy-19); path.lineTo(cx-13,cy+14); path.lineTo(cx,cy+7); path.lineTo(cx+13,cy+14); path.close()
        p.color=Color.WHITE; p.style=Paint.Style.STROKE; p.strokeWidth=5f; c.drawPath(path,p)
        p.style=Paint.Style.FILL; p.color=if(f.gpsGood) Color.rgb(0,98,230) else Color.GRAY; c.drawPath(path,p)
        p.color=Color.rgb(17,29,22); c.drawRoundRect(105f,20f,421f,91f,18f,18f,p)
        p.textAlign=Paint.Align.CENTER; p.typeface=Typeface.DEFAULT_BOLD; p.textSize=19f; p.color=Color.rgb(190,248,114)
        c.drawText(f.instruction.take(31),cx,49f,p)
        p.typeface=Typeface.DEFAULT; p.textSize=15f; p.color=Color.WHITE
        c.drawText((if(f.gpsGood) f.detail else "GPS fraco · aguardando posição").take(38),cx,74f,p)
        p.color=Color.argb(225,17,29,22); c.drawRoundRect(136f,263f,390f,288f,9f,9f,p)
        p.textSize=12f; p.color=Color.WHITE; c.drawText("© OpenStreetMap contributors",cx,280f,p)
    }
}
