package com.ecency.core.esie

/**
 * Esie, the Ecency mascot, as native vector data shared by both apps (README "Esie").
 *
 * The master is `native/tools/esie/art/esie.svg`; `native/tools/esie/esie_to_kotlin.py` turns it into
 * [EsieArtData]: one [EsieLayer] per drawable, bottom to top, in art coordinates (the master's viewBox).
 * The apps draw [layers] with the per-layer opacity and matrices [EsieRig.fill] writes into an [EsieFrameBuffer].
 */
object EsieArt {
    /** The master's viewBox (left, top, width, height). */
    val viewBox: EsieRect = EsieArtData.VIEW_BOX.let { EsieRect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }

    /** Every drawable, bottom to top. */
    val layers: List<EsieLayer> get() = EsieArtData.LAYERS

    /** Every named part (group or shape) with its bounds in art coordinates. */
    val parts: Set<String> get() = EsieArtData.BOUNDS.keys

    private val clipPaths: Map<String, EsiePath> by lazy { EsieArtData.CLIPS.mapValues { EsiePath.parse(it.value) } }

    /** Bounds of [part] in art coordinates; throws for an unknown part. */
    fun bounds(part: String): EsieRect =
        EsieArtData.BOUNDS[part]?.let { EsieRect(it[0], it[1], it[2], it[3]) } ?: throw IllegalArgumentException("no part $part")

    /** The clip path named [id] (an SVG clipPath of the master). */
    fun clip(id: String): EsiePath = clipPaths[id] ?: throw IllegalArgumentException("no clip $id")

    /** How far the eyelid group [part] is parked above its eye while open (negative: up). */
    fun lidTravel(part: String): Float = EsieArtData.LID_TRAVEL[part] ?: 0f
}

/** A rectangle in art coordinates. */
data class EsieRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The point at fractions ([fx], [fy]) of the box, as CSS `transform-origin` with `transform-box: fill-box`. */
    fun at(fx: Float, fy: Float): Pair<Float, Float> = Pair(left + fx * width, top + fy * height)

    fun union(o: EsieRect) = EsieRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    fun intersect(o: EsieRect) = EsieRect(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))

    fun inset(by: Float) = EsieRect(left - by, top - by, right + by, bottom + by)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * One drawable of the master.
 *
 * [chain] holds the ids of the part and its named ancestors, outermost first: a renderer applies the frame's
 * transform of each in order, and the clips whose [EsieClip.depth] equals the number of chain entries applied so
 * far. The layer shows only when no part of its chain is hidden.
 */
class EsieLayer(
    val chain: List<String>,
    /** Normalised absolute path data (M, L, C, Q, Z). */
    val d: String,
    val fill: EsiePaint?,
    val stroke: EsieStroke? = null,
    /** Opacity from the master (element and group opacity). */
    val opacity: Float = 1f,
    val clips: List<EsieClip> = emptyList(),
) {
    /** The id of the innermost named part this layer belongs to. */
    val part: String get() = chain.last()

    val path: EsiePath by lazy { EsiePath.parse(d) }
}

/** A clip path ([EsieArt.clip] id) applied after the transforms of the first [depth] parts of a layer's chain. */
data class EsieClip(val depth: Int, val id: String)

/** Fill or stroke paint. */
sealed interface EsiePaint

/** A solid colour, 0xAARRGGBB. */
data class EsieSolid(val argb: Long) : EsiePaint

/**
 * A linear or radial gradient in gradient units: [coords] are x1, y1, x2, y2 (linear) or cx, cy, r, 0 (radial),
 * [offsets] and [colors] (0xAARRGGBB) its stops, padded beyond the ends. [matrix] (a, b, c, d, e, f as SVG
 * `matrix()`) maps gradient units to art coordinates; for the master's objectBoundingBox gradients that is the
 * shape's bounding box, so a radial gradient on a wide shape is an ellipse.
 */
class EsieGradient(
    val radial: Boolean,
    val coords: FloatArray,
    val offsets: FloatArray,
    val colors: LongArray,
    val matrix: FloatArray,
    val alpha: Float = 1f,
) : EsiePaint

/** A round-capped stroke, [width] in art units. */
data class EsieStroke(val argb: Long, val width: Float)

/**
 * Path data as two arrays, cheap to walk from Swift too: [ops] holds one of [MOVE], [LINE], [CUBIC], [QUAD],
 * [CLOSE] per segment and [coords] the points of every segment in order (2, 2, 6, 4 and 0 numbers).
 */
class EsiePath(val ops: IntArray, val coords: FloatArray) {
    /** The control-point hull: never smaller than the drawn shape. */
    val hull: EsieRect by lazy {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in coords.indices step 2) {
            l = minOf(l, coords[i]); r = maxOf(r, coords[i])
            t = minOf(t, coords[i + 1]); b = maxOf(b, coords[i + 1])
        }
        EsieRect(l, t, r, b)
    }

    /** Back to SVG path data (one decimal), the same text [parse] reads. */
    fun toSvg(): String = buildString {
        var c = 0
        for (op in ops) {
            append(LETTERS[op])
            val n = SIZES[op]
            for (k in 0 until n step 2) {
                if (k > 0) append(' ')
                append(num(coords[c + k])).append(',').append(num(coords[c + k + 1]))
            }
            c += n
        }
    }

    companion object {
        const val MOVE = 0
        const val LINE = 1
        const val CUBIC = 2
        const val QUAD = 3
        const val CLOSE = 4
        private const val LETTERS = "MLCQZ"
        private val SIZES = intArrayOf(2, 2, 6, 4, 0)

        /** Reads absolute M, L, C, Q and Z commands, the converter's output; anything else is an error. */
        fun parse(d: String): EsiePath {
            val ops = ArrayList<Int>()
            val coords = ArrayList<Float>()
            var i = 0
            var op = -1
            var expect = 0
            while (i < d.length) {
                val ch = d[i]
                when {
                    ch == ' ' || ch == ',' || ch == '\n' -> i++
                    ch.isLetter() -> {
                        require(expect == 0) { "path segment ended early at $i" }
                        op = LETTERS.indexOf(ch)
                        require(op >= 0) { "unsupported path command '$ch'" }
                        ops += op
                        expect = SIZES[op]
                        i++
                    }
                    else -> {
                        require(op >= 0) { "path data starts without a command" }
                        if (expect == 0) {
                            // implicit repeat of the last command (M repeats as L)
                            val again = if (op == MOVE) LINE else op
                            require(again != CLOSE) { "numbers after Z" }
                            ops += again
                            op = again
                            expect = SIZES[again]
                        }
                        var j = i + 1
                        while (j < d.length && (d[j].isDigit() || d[j] == '.' || d[j] == 'e' || d[j] == 'E' ||
                                ((d[j] == '-' || d[j] == '+') && (d[j - 1] == 'e' || d[j - 1] == 'E')))) j++
                        coords += d.substring(i, j).toFloat()
                        expect--
                        i = j
                    }
                }
            }
            require(expect == 0) { "path data ends inside a segment" }
            return EsiePath(ops.toIntArray(), coords.toFloatArray())
        }

        private fun num(v: Float): String {
            val tenths = kotlin.math.round(v * 10).toLong()
            val sign = if (tenths < 0) "-" else ""
            val a = kotlin.math.abs(tenths)
            return "$sign${a / 10}.${a % 10}"
        }
    }
}
