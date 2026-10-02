package de.libertylight.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

enum class Umschalt { AUS, EINMAL, FEST }

/**
 * Zeichnet die Tasten selbst und wertet Beruehrungen aus.
 *
 * - Zeichen werden beim Loslassen geschrieben, damit langes Druecken Alternativen zeigen kann.
 * - Tippt ein zweiter Finger, bevor der erste losgelassen hat, wird der erste sofort geschrieben
 *   (schnelles Tippen mit zwei Daumen).
 * - Wischen auf der Leertaste bewegt den Cursor.
 * - Die Loeschtaste wiederholt, solange sie gehalten wird, und wird dabei schneller.
 */
@SuppressLint("ViewConstructor")
class TastenfeldView(context: Context, private val zuhoerer: Zuhoerer) : View(context) {

    interface Zuhoerer {
        fun zeichen(text: String)
        fun aktion(art: Art)
        fun cursor(schritte: Int)
        fun leertasteLang()
        fun rueckmeldung()
    }

    private class Platz(val taste: Taste, val rect: RectF)

    var thema: Thema = Thema.HELL
        set(v) { field = v; invalidate() }
    var reihen: List<List<Taste>> = emptyList()
        set(v) { field = v; requestLayout(); berechne(); invalidate() }
    var umschalt = Umschalt.AUS
        set(v) { field = v; invalidate() }
    var enterText = "↵"
        set(v) { field = v; invalidate() }
    var hoehenFaktor = 1f
        set(v) { field = v; requestLayout() }
    var vibration = true

    private val dichte = resources.displayMetrics.density
    private val tastenHoehe get() = 54 * dichte * hoehenFaktor
    private val luecke = 3 * dichte
    private val radius = 8 * dichte

    private val plaetze = ArrayList<Platz>()
    private val farbe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val schrift = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val zeiger = Handler(Looper.getMainLooper())

    // Zustand der aktuellen Beruehrung
    private var aktiv: Platz? = null
    private var zeigerId = -1
    private var startX = 0f
    private var leerVersatz = 0
    private var langGedrueckt = false
    private var auswahl: List<String> = emptyList()
    private var auswahlRect = RectF()
    private var auswahlIndex = 0
    private var loeschTempo = 0

    private val langDruck = Runnable { langerDruck() }
    private val loeschWiederholung = object : Runnable {
        override fun run() {
            zuhoerer.aktion(Art.LOESCHEN)
            loeschTempo++
            zeiger.postDelayed(this, if (loeschTempo > 12) 35L else 70L)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val breite = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(breite, (reihen.size * tastenHoehe + luecke * 2).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = berechne()

    private fun berechne() {
        plaetze.clear()
        if (width == 0) return
        var y = luecke
        for (reihe in reihen) {
            val summe = reihe.sumOf { it.breite.toDouble() }.toFloat()
            val einheit = (width - luecke) / summe
            var x = luecke / 2
            for (taste in reihe) {
                val w = taste.breite * einheit
                plaetze += Platz(taste, RectF(x + luecke / 2, y + luecke / 2, x + w - luecke / 2, y + tastenHoehe - luecke / 2))
                x += w
            }
            y += tastenHoehe
        }
    }

    // ---------- Zeichnen ----------

    private fun beschriftung(taste: Taste): String = when (taste.art) {
        Art.ZEICHEN -> if (umschalt != Umschalt.AUS && taste.text.length == 1) TextLogik.gross(taste.text) else taste.text
        Art.SHIFT -> if (umschalt == Umschalt.FEST) "⇪" else "⇧"
        Art.ENTER -> enterText
        else -> taste.text
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(thema.hintergrund)
        for (platz in plaetze) {
            val t = platz.taste
            val gedrueckt = platz === aktiv && !langGedrueckt
            farbe.color = when {
                gedrueckt -> thema.gedrueckt
                t.art == Art.ENTER -> thema.akzent
                t.art == Art.SHIFT && umschalt != Umschalt.AUS -> thema.akzent
                t.art == Art.ZEICHEN || t.art == Art.LEER -> thema.taste
                else -> thema.sonder
            }
            canvas.drawRoundRect(platz.rect, radius, radius, farbe)

            val hervorgehoben = t.art == Art.ENTER || (t.art == Art.SHIFT && umschalt != Umschalt.AUS)
            schrift.color = if (hervorgehoben) thema.akzentText else if (t.art == Art.LEER) thema.textLeise else thema.text
            val text = beschriftung(t)
            schrift.typeface = if (t.art == Art.ZEICHEN) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            schrift.textSize = when {
                t.art == Art.LEER -> 13 * dichte
                text.length > 2 -> 15 * dichte
                else -> 21 * dichte * hoehenFaktor.coerceIn(0.85f, 1.15f)
            }
            canvas.drawText(text, platz.rect.centerX(), platz.rect.centerY() - (schrift.descent() + schrift.ascent()) / 2, schrift)

            if (t.hinweis.isNotEmpty()) {
                schrift.color = thema.textLeise
                schrift.textSize = 10 * dichte
                schrift.typeface = Typeface.DEFAULT
                canvas.drawText(t.hinweis, platz.rect.right - 7 * dichte, platz.rect.top + 12 * dichte, schrift)
            }
        }
        if (langGedrueckt && auswahl.isNotEmpty()) zeichneAuswahl(canvas)
    }

    private fun zeichneAuswahl(canvas: Canvas) {
        farbe.color = thema.sonder
        farbe.setShadowLayer(6 * dichte, 0f, 2 * dichte, 0x55000000)
        canvas.drawRoundRect(auswahlRect, radius, radius, farbe)
        farbe.clearShadowLayer()
        val zelle = auswahlRect.width() / auswahl.size
        auswahl.forEachIndexed { i, zeichen ->
            val links = auswahlRect.left + i * zelle
            if (i == auswahlIndex) {
                farbe.color = thema.akzent
                canvas.drawRoundRect(RectF(links + 2, auswahlRect.top + 2, links + zelle - 2, auswahlRect.bottom - 2), radius, radius, farbe)
            }
            schrift.color = if (i == auswahlIndex) thema.akzentText else thema.text
            schrift.textSize = 20 * dichte
            schrift.typeface = Typeface.DEFAULT
            val text = if (umschalt != Umschalt.AUS) TextLogik.gross(zeichen) else zeichen
            canvas.drawText(text, links + zelle / 2, auswahlRect.centerY() - (schrift.descent() + schrift.ascent()) / 2, schrift)
        }
    }

    // ---------- Beruehrung ----------

    private fun platzBei(x: Float, y: Float): Platz? =
        plaetze.firstOrNull { x >= it.rect.left - luecke && x <= it.rect.right + luecke && y >= it.rect.top - luecke && y <= it.rect.bottom + luecke }
            ?: plaetze.minByOrNull { abs(it.rect.centerX() - x) + abs(it.rect.centerY() - y) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginne(e.getPointerId(0), e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Zweiter Finger: den ersten sofort abschliessen, dann weiter mit dem neuen.
                if (aktiv != null && !langGedrueckt) beende(abbrechen = false)
                val i = e.actionIndex
                beginne(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(zeigerId)
                if (i >= 0) bewege(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == zeigerId) beende(abbrechen = false)
            MotionEvent.ACTION_UP -> beende(abbrechen = false)
            MotionEvent.ACTION_CANCEL -> beende(abbrechen = true)
        }
        return true
    }

    private fun beginne(id: Int, x: Float, y: Float) {
        val platz = platzBei(x, y) ?: return
        aktiv = platz
        zeigerId = id
        startX = x
        leerVersatz = 0
        langGedrueckt = false
        rueckmeldung()
        when (platz.taste.art) {
            Art.LOESCHEN -> {
                zuhoerer.aktion(Art.LOESCHEN)
                loeschTempo = 0
                zeiger.postDelayed(loeschWiederholung, 400)
            }
            Art.ZEICHEN, Art.LEER -> zeiger.postDelayed(langDruck, 380)
            else -> {}
        }
        invalidate()
    }

    private fun bewege(x: Float, y: Float) {
        val platz = aktiv ?: return
        if (langGedrueckt && auswahl.isNotEmpty()) {
            val zelle = auswahlRect.width() / auswahl.size
            auswahlIndex = ((x - auswahlRect.left) / zelle).toInt().coerceIn(0, auswahl.size - 1)
            invalidate()
            return
        }
        if (platz.taste.art == Art.LEER) {
            // Wischen auf der Leertaste: alle 14dp ein Zeichen weiter
            val schritte = ((x - startX) / (14 * dichte)).toInt()
            if (schritte != leerVersatz) {
                zeiger.removeCallbacks(langDruck)
                zuhoerer.cursor(schritte - leerVersatz)
                leerVersatz = schritte
            }
        }
    }

    private fun langerDruck() {
        val platz = aktiv ?: return
        if (platz.taste.art == Art.LEER) {
            if (leerVersatz == 0) {
                langGedrueckt = true
                zuhoerer.leertasteLang()
            }
            return
        }
        val alternativen = platz.taste.alternativen
        if (alternativen.isEmpty()) return
        langGedrueckt = true
        auswahl = alternativen
        auswahlIndex = 0
        val zelle = platz.rect.width().coerceAtLeast(34 * dichte)
        val breite = zelle * auswahl.size
        val links = (platz.rect.centerX() - zelle / 2).coerceIn(luecke, maxOf(luecke, width - breite - luecke))
        val oben = (platz.rect.top - tastenHoehe).coerceAtLeast(0f)
        auswahlRect = RectF(links, oben, links + breite, oben + tastenHoehe - luecke)
        rueckmeldung()
        invalidate()
    }

    private fun beende(abbrechen: Boolean) {
        val platz = aktiv
        zeiger.removeCallbacks(langDruck)
        zeiger.removeCallbacks(loeschWiederholung)
        aktiv = null
        zeigerId = -1
        if (platz != null && !abbrechen) {
            val t = platz.taste
            when {
                langGedrueckt && auswahl.isNotEmpty() -> zuhoerer.zeichen(auswahl[auswahlIndex])
                langGedrueckt -> {}
                t.art == Art.LEER && leerVersatz != 0 -> {}
                t.art == Art.ZEICHEN -> zuhoerer.zeichen(t.text)
                t.art == Art.LOESCHEN -> {}
                else -> zuhoerer.aktion(t.art)
            }
        }
        langGedrueckt = false
        auswahl = emptyList()
        invalidate()
    }

    private fun rueckmeldung() {
        if (vibration) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        zuhoerer.rueckmeldung()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zeiger.removeCallbacksAndMessages(null)
    }
}
