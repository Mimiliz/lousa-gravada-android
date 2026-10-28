package br.com.mimiexplica.lousa

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/** Desenho dos traços: o mesmo algoritmo da versão em HTML (curvas pelos pontos médios). */
object Ink {
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    fun widthOf(s: Stroke, p: Pt): Float =
        if (s.tool == Tool.BORRACHA) s.w * 5f else s.w * (if (s.pen) 0.35f + p.p * 1.3f else 1f)

    fun paintFor(s: Stroke, paint: Paint = Paint()): Paint {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = s.color
        if (s.tool == Tool.BORRACHA) paint.xfermode = clear
        if (s.tool == Tool.MARCA) { paint.alpha = 82; paint.strokeWidth = s.w * 3.4f }
        if (s.tool == Tool.RETA) paint.strokeWidth = s.w * 1.1f
        return paint
    }

    /** Pedaço do traço que termina no ponto i (i >= 1). Devolve [ax, ay, cx, cy, bx, by, largura]. */
    fun segGeom(s: Stroke, i: Int): FloatArray {
        val P = s.pts
        val c = P[i - 1]
        val ax: Float; val ay: Float
        if (i == 1) { ax = P[0].x; ay = P[0].y } else { ax = (P[i - 2].x + c.x) / 2; ay = (P[i - 2].y + c.y) / 2 }
        val bx = (c.x + P[i].x) / 2; val by = (c.y + P[i].y) / 2
        return floatArrayOf(ax, ay, c.x, c.y, bx, by, widthOf(s, c))
    }

    /** Final do traço (do último ponto médio até o último ponto), ou um ponto se o traço tem 1 ponto. */
    fun tailGeom(s: Stroke): FloatArray {
        val P = s.pts; val n = P.size
        if (n == 1) return floatArrayOf(P[0].x, P[0].y, P[0].x, P[0].y, P[0].x, P[0].y, widthOf(s, P[0]))
        val ax = (P[n - 2].x + P[n - 1].x) / 2; val ay = (P[n - 2].y + P[n - 1].y) / 2
        return floatArrayOf(ax, ay, ax, ay, P[n - 1].x, P[n - 1].y, widthOf(s, P[n - 1]))
    }

    private val tmp = Path()
    fun drawGeom(c: Canvas, g: FloatArray, paint: Paint) {
        paint.strokeWidth = g[6]
        if (g[0] == g[4] && g[1] == g[5]) {
            val st = paint.style
            paint.style = Paint.Style.FILL
            c.drawCircle(g[0], g[1], g[6] / 2, paint)
            paint.style = st
            return
        }
        tmp.rewind(); tmp.moveTo(g[0], g[1]); tmp.quadTo(g[2], g[3], g[4], g[5])
        c.drawPath(tmp, paint)
    }

    fun draw(c: Canvas, s: Stroke, paint: Paint = Paint()) {
        if (s.pts.isEmpty()) return
        paintFor(s, paint)
        when (s.tool) {
            Tool.CANETA, Tool.BORRACHA -> {
                for (i in 1 until s.pts.size) drawGeom(c, segGeom(s, i), paint)
                drawGeom(c, tailGeom(s), paint)
            }
            Tool.MARCA -> {
                val P = s.pts
                val path = Path()
                path.moveTo(P[0].x, P[0].y)
                for (i in 1 until P.size) {
                    path.quadTo(P[i - 1].x, P[i - 1].y, (P[i - 1].x + P[i].x) / 2, (P[i - 1].y + P[i].y) / 2)
                }
                val L = P[P.size - 1]
                path.lineTo(L.x + 0.01f, L.y)
                c.drawPath(path, paint)
            }
            Tool.RETA -> if (s.pts.size >= 2) c.drawLine(s.pts[0].x, s.pts[0].y, s.pts[1].x, s.pts[1].y, paint)
            Tool.MOVER -> {}
        }
    }
}
