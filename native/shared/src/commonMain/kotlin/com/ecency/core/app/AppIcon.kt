package com.ecency.core.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Every icon the app shows, named by what it means, not by how it looks. Each platform draws its own native set:
 * Android maps an entry to a Material Symbols (Outlined) vector drawable (ui/AppIconMap.kt), iOS to an SF Symbol
 * (Screens/AppIconMap.swift). Icons are outlined at rest; a platform draws the filled variant for an active,
 * selected or toggled state. The list with both platforms' names is native/scripts/icons/icons.tsv; tests on both
 * platforms check every entry is mapped.
 *
 * [isDirectional] icons point along the reading direction and mirror in right to left layouts.
 */
enum class AppIcon(val isDirectional: Boolean = false) {
    HOME, WAVES, CHAT, COMMENT, WALLET, NOTIFICATIONS, NOTIFICATIONS_OFF,
    BACK(true), ARROW_FORWARD(true), CHEVRON_LEFT(true), CHEVRON_RIGHT(true), CHEVRON_DOUBLE_RIGHT(true),
    EXPAND, COLLAPSE, ARROW_UP, ARROW_DOWN, DROPDOWN, MORE, CLOSE, CLEAR, ADD, REMOVE,
    CHECK, CHECK_CIRCLE, TASK, CHECKBOX, SEARCH, QR_SCAN, BOOST,
    PERSON, ACCOUNT, PERSON_ADD, GROUP, MEMBERS, GROUP_ADD,
    BOOKMARK, DRAFTS, EXPLORE, SHARE, HELP, SETTINGS, POWER, LOGOUT(true), GIFT, TRANSLATE, REFRESH,
    LOCK, VERIFIED_USER, MODERATOR, GLOBE, WARNING, ERROR, INFO,
    PLAY, PAUSE, PLAY_CIRCLE, HEADPHONES, CHART_BAR, CHART_LINE, EDIT, COMPOSE, SWAP, SORT, DISCARD,
    HEART, REBLOG, REPEAT, PIN, SEND(true), BACKSPACE(true), KEY, STAR, SNOWFLAKE, BOLT, MEGAPHONE, ROCKET,
    CHECKIN, VOTE_UP, VOTE_DOWN, TUNE, FILTER, HISTORY, PREVIEW, AI, AI_ASSIST, IMAGE, LINK, DOWNLOAD, CLIPBOARD,
    VERIFIED, CALENDAR, LOCATION, EMAIL, MUTED, SOUND_ON, SORT_AZ, MARK_ALL_READ,
    CAMERA, FOLDER, VIDEO, VIDEO_ATTACHED, MIC, CROSS_POST, REPLY(true), FULLSCREEN, OFFLINE, CLOUD_OFF,
    FACE_ID, TOUCH_ID, TICKET, MONEY, AUTORENEW, REORDER, ESCROW, DELEGATION, HOURGLASS_FULL, HOURGLASS_TOP,
    CREATED, REFUND, TROPHY, TARGET, SHRINK,
    ;

    companion object {
        /** RN questsCard `ICONS[entry.icon] || 'star-outline'`: the icon for an SDK quest hint. */
        fun quest(hint: String): AppIcon = when (hint) {
            "check-circle" -> CHECKIN
            "pencil" -> EDIT
            "comment" -> COMMENT
            "chevron-up-circle" -> VOTE_UP
            "repeat" -> REBLOG
            else -> STAR
        }
    }
}

/**
 * The Waves mark: RN's wavy dash (after Twemoji, CC-BY 4.0), the one custom glyph, drawn by both platforms from
 * this one path. A stroke of [STROKE] (the weight of a 400 Material Symbol at this size) with round caps on a
 * [GRID] unit square: from [START], one cubic per entry of [CURVES] as (control 1 x, y, control 2 x, y, end x, y).
 */
object WavesMark {
    const val GRID = 36f
    const val STROKE = 3f
    val START = floatArrayOf(1f, 13f)
    val CURVES: List<FloatArray> = listOf(
        floatArrayOf(5f, 13f, 5f, 22f, 9f, 22f),
        floatArrayOf(13f, 22f, 14f, 13f, 18f, 13f),
        floatArrayOf(22f, 13f, 23f, 22f, 27f, 22f),
        floatArrayOf(31f, 22f, 31f, 13f, 35f, 13f),
    )
}

/**
 * The Ecency logo mark: the logo glyph without its round blue background, as RN's EcencySourceBadge and the web
 * badge draw it. [PATH] and the square view box from ([VIEW_X], [VIEW_Y]) of side [VIEW_SIZE] are RN's, unchanged,
 * so all three clients draw the same shape. Both platforms fill [CONTOURS] (nonzero, the SVG default): [PATH] read once
 * here into closed contours of absolute cubics, each a start (x, y) then six numbers per cubic as in [WavesMark].
 */
object EcencyLogoMark {
    const val VIEW_X = 40.6f
    const val VIEW_Y = 28.7f
    const val VIEW_SIZE = 80.4f
    const val PATH =
        "M88.27,105.71c-9,.08-30.35.27-35.13-.29-3.88-.46-11-3-11.11-12.81C42,87,41.66,64,42.46,59,44.13,48.4,47," +
            "41.77,59.05,36.33c10.26-4.44,32.17-.78,34.54,16.93.45,3.37,1.25,3.74,2.49,4,19.61,4.13,24,26.26,14.6," +
            "38.32C104.73,103.26,98.31,104.76,88.27,105.71ZM84.71,59.25c.68-11.52-11-19.82-22.82-13.66-8.42,4.39-9.15," +
            "10.76-9.68,18-.67,9.2-.25,15.91-.09,25.13.07,4.13,1.27,6.64,5.7,7,1.14.1,17,0,25.22.06,10.74.06,24.06-4.89," +
            "21.93-18a12.68,12.68,0,0,0-10.8-10.22,2.12,2.12,0,0,0-2.21,1C85,83,69.66,82.31,63.41,74.46c-5.61-7.06-2.7-18.73," +
            "4.68-21.2,2.78-.94,5.11-.11,6.25,1.86,1.84,3.18.11,6.06-2.49,7.65s-2.45,3.92-1.36,5.46c2.56,3.59,7.6,2.88,10.79-.28" +
            "C83.87,65.4,84.52,62.47,84.71,59.25Z"

    // A soft, recessed gray so the mark reads as a faint hint, not a status badge. Same values as RN and the web
    // badge (Tailwind gray-300 / gray-700): light on a light ground, dark on a dark ground, both a step quieter than
    // $iconColor. Kept as literals because there is no matching theme token, mirroring the web classes.
    const val COLOR_LIGHT = 0xFFD1D5DB
    const val COLOR_DARK = 0xFF374151

    val CONTOURS: List<FloatArray> by lazy { SvgPath.cubics(PATH) }
}

/** SVG path data to closed contours of absolute cubics: the M L C S A Z commands (either case), lines and arcs as cubics. */
internal object SvgPath {
    private val token = Regex("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun cubics(d: String): List<FloatArray> {
        val t = token.findAll(d).map { it.value }.toList()
        val out = mutableListOf<FloatArray>()
        var cur = mutableListOf<Float>()
        var i = 0
        var cmd = ' '
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        var c2x = 0f; var c2y = 0f // the last cubic's second control, for S
        fun num(): Float = t[i++].toFloat()
        fun cubic(a: Float, b: Float, c: Float, e: Float, f: Float, g: Float) {
            cur.addAll(listOf(a, b, c, e, f, g)); c2x = c; c2y = e; x = f; y = g
        }
        fun close() { if (cur.size > 2) out += cur.toFloatArray(); cur = mutableListOf() }
        while (i < t.size) {
            if (t[i][0].isLetter()) cmd = t[i++][0]
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    close(); x = num() + ox; y = num() + oy; sx = x; sy = y; cur.addAll(listOf(x, y)); c2x = x; c2y = y
                    cmd = if (rel) 'l' else 'L' // further pairs are lines
                }
                'L' -> { val ex = num() + ox; val ey = num() + oy; cubic(x, y, ex, ey, ex, ey) }
                'C' -> cubic(num() + ox, num() + oy, num() + ox, num() + oy, num() + ox, num() + oy)
                'S' -> cubic(2 * x - c2x, 2 * y - c2y, num() + ox, num() + oy, num() + ox, num() + oy)
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    arc(x, y, rx, ry, rot, large, sweep, num() + ox, num() + oy, ::cubic)
                }
                'Z' -> { x = sx; y = sy; close() }
                else -> error("unsupported path command $cmd")
            }
        }
        close()
        return out
    }

    /** SVG 1.1 F.6.5 endpoint to center arc, then one cubic per quarter turn or less. */
    private fun arc(
        x1: Float, y1: Float, rxIn: Float, ryIn: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float,
        cubic: (Float, Float, Float, Float, Float, Float) -> Unit,
    ) {
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0 || (x1 == x2 && y1 == y2)) { cubic(x1, y1, x2, y2, x2, y2); return }
        val phi = rotDeg * PI / 180; val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val xp = cp * dx + sp * dy; val yp = -sp * dx + cp * dy
        val lambda = xp * xp / (rx * rx) + yp * yp / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
        val den = rx * rx * yp * yp + ry * ry * xp * xp
        val k = (if (large == sweep) -1 else 1) * sqrt(max(0.0, num / den))
        val cxp = k * rx * yp / ry; val cyp = -k * ry * xp / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0; val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val t1 = angle(1.0, 0.0, (xp - cxp) / rx, (yp - cyp) / ry)
        var dt = angle((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI else if (sweep && dt < 0) dt += 2 * PI
        val n = ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / n; val h = 4.0 / 3 * tan(step / 4)
        fun pt(a: Double) = doubleArrayOf(cx + rx * cos(a) * cp - ry * sin(a) * sp, cy + rx * cos(a) * sp + ry * sin(a) * cp)
        fun d(a: Double) = doubleArrayOf(-rx * sin(a) * cp - ry * cos(a) * sp, -rx * sin(a) * sp + ry * cos(a) * cp)
        var a = t1
        repeat(n) { j ->
            val b = a + step
            val p0 = pt(a); val d0 = d(a); val d1 = d(b)
            val p1 = if (j == n - 1) doubleArrayOf(x2.toDouble(), y2.toDouble()) else pt(b)
            cubic(
                (p0[0] + h * d0[0]).toFloat(), (p0[1] + h * d0[1]).toFloat(),
                (p1[0] - h * d1[0]).toFloat(), (p1[1] - h * d1[1]).toFloat(), p1[0].toFloat(), p1[1].toFloat(),
            )
            a = b
        }
    }
}
