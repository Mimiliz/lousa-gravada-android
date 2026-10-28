package br.com.mimiexplica.lousa

import java.io.File

enum class Tool { CANETA, MARCA, RETA, BORRACHA, MOVER }

class Pt(val x: Float, val y: Float, val p: Float)

class Stroke(val tool: Tool, val color: Int, val w: Float, val pen: Boolean) {
    val pts = ArrayList<Pt>()
}

/** Imagem de fundo de uma página: arquivo de imagem (pdfIndex = -1) ou uma página de PDF. */
class ImgSource(val file: File, val pdfIndex: Int, val w: Int, val h: Int)

class Page {
    val strokes = ArrayList<Stroke>()
    val redo = ArrayList<Stroke>()
    var img: ImgSource? = null
    // posição (centro, em coordenadas da lousa) e escala da imagem; NaN = centralizada
    var cx = Float.NaN
    var cy = Float.NaN
    var k = 1f
}

enum class Fundo(val fill: Int, val ink: Int, val grid: Int = 0, val dots: Int = 0) {
    BRANCO(0xFFFBFBF8.toInt(), 0xFF14161A.toInt()),
    GRADE(0xFFFBFBF8.toInt(), 0xFF14161A.toInt(), grid = 0x332F7BF5),
    PONTOS(0xFFFBFBF8.toInt(), 0xFF14161A.toInt(), dots = 0x4714161A),
    QUADRO(0xFF21402F.toInt(), 0xFFFFFFFF.toInt()),
    PRETO(0xFF121317.toInt(), 0xFFFFFFFF.toInt())
}
