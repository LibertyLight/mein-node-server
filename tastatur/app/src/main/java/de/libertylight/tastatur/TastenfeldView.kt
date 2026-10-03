package de.libertylight.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import kotlin.math.abs

enum class Umschalt { AUS, EINMAL, FEST }

/**
 * Zeichnet die Tasten selbst und wertet Beruehrungen aus.
 *
 * Alle Masse sind Anteile der Breite und stammen aus dem Referenz-Screenshot:
 * Tastenbreite 92 von 108 px je Spalte, Lücke 16 px, Zeilenabstand 183 px, Schrift 86 px
 * (alles bei 1080 px Bildschirmbreite). So sieht es auf jedem Handy gleich aus.
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
        /** [anschlag] ist null, wenn das Zeichen nicht direkt getippt wurde (Langdruck-Auswahl). */
        fun zeichen(text: String, anschlag: Anschlag?)
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
    var enterArt = EnterArt.ZEILE
        set(v) { field = v; invalidate() }
    /** Tastenhoehe, 1 = wie im Referenz-Screenshot. */
    var hoehenFaktor = 1f
        set(v) { field = v; requestLayout() }
    /** Groesse der Beschriftung, unabhaengig von der Tastenhoehe (1 = Vorgabe). */
    var schriftFaktor = 1f
        set(v) { field = v; invalidate() }
    /** Buchstaben immer gross beschriften (geschrieben wird trotzdem klein, wie bei Samsung). */
    var grossBeschriftung = false
        set(v) { field = v; invalidate() }
    var vibration = true

    private val dichte = resources.displayMetrics.density

    // Masse, aus der Breite berechnet
    private var spalte = 0f
    private var zeilenHoehe = 0f
    private var luecke = 0f
    private var lueckeHoch = 0f
    private var tasteHoehe = 0f

    private val plaetze = ArrayList<Platz>()
    private val farbe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val schrift = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strich = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val schriftBuchstabe = Typeface.create("sans-serif", Typeface.NORMAL)
    private val schriftBeschriftung = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val zeiger = Handler(Looper.getMainLooper())

    // Zustand der aktuellen Beruehrung
    private var aktiv: Platz? = null
    private var zeigerId = -1
    private var startX = 0f
    private var startY = 0f
    private var leerVersatz = 0
    private var langGedrueckt = false
    private var auswahl: List<String> = emptyList()
    private var auswahlIndex = 0
    private var auswahlFenster: PopupWindow? = null
    private var auswahlAnsicht: AuswahlAnsicht? = null
    private var loeschTempo = 0

    private val langDruck = Runnable { langerDruck() }
    private val loeschWiederholung = object : Runnable {
        override fun run() {
            zuhoerer.aktion(Art.LOESCHEN)
            loeschTempo++
            zeiger.postDelayed(this, if (loeschTempo > 12) 35L else 70L)
        }
    }

    /** Mitte jeder Zeichentaste in Tastenbreiten -- Grundlage fuer die Autokorrektur. */
    fun tastenkarte(): Map<Char, Pair<Float, Float>> =
        Raster.mitten(reihen, if (spalte > 0f) zeilenHoehe / spalte else Raster.ZEILEN_VERHAELTNIS)

    private fun zeilenHoeheFuer(breite: Float): Float {
        val breiteDp = breite / dichte
        val basis = if (breiteDp >= 560f) 50f * dichte // Querformat, Tablet: sonst fuellt die Tastatur den Bildschirm
        else (Raster.ZEILEN_VERHAELTNIS * breite / 10f).coerceIn(52f * dichte, 76f * dichte)
        return basis * hoehenFaktor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val breite = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(breite, (reihen.size * zeilenHoeheFuer(breite.toFloat())).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = berechne()

    private fun berechne() {
        plaetze.clear()
        if (width == 0) return
        val w = width.toFloat()
        spalte = w / 10f
        zeilenHoehe = zeilenHoeheFuer(w)
        luecke = (0.0148f * w).coerceIn(3f * dichte, 7.5f * dichte)
        lueckeHoch = luecke * 1.12f
        tasteHoehe = zeilenHoehe - lueckeHoch

        reihen.forEachIndexed { r, reihe ->
            val einheit = w / reihe.sumOf { it.breite.toDouble() }.toFloat()
            var x = 0f
            val oben = r * zeilenHoehe
            for (taste in reihe) {
                val breite = taste.breite * einheit
                if (taste.art != Art.ABSTAND) {
                    plaetze += Platz(taste, RectF(x + luecke / 2, oben + lueckeHoch / 2, x + breite - luecke / 2, oben + zeilenHoehe - lueckeHoch / 2))
                }
                x += breite
            }
        }
    }

    // ---------- Zeichnen ----------

    private fun beschriftung(taste: Taste): String = when {
        taste.art == Art.ZEICHEN && taste.text.length == 1 &&
            (umschalt != Umschalt.AUS || grossBeschriftung) -> TextLogik.gross(taste.text)
        else -> taste.text
    }

    private fun istSymbol(art: Art) = art == Art.SHIFT || art == Art.LOESCHEN || art == Art.ENTER || art == Art.EMOJI

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(thema.hintergrund)
        val aktionEnter = enterArt != EnterArt.ZEILE
        for (platz in plaetze) {
            val t = platz.taste
            val gedrueckt = platz === aktiv && !langGedrueckt
            val akzentTaste = t.art == Art.ENTER && aktionEnter

            // hauchduenner Schatten unter der Taste
            farbe.color = thema.schatten
            canvas.drawRoundRect(platz.rect.left, platz.rect.top + 1.2f * dichte, platz.rect.right, platz.rect.bottom + 1.2f * dichte, luecke, luecke, farbe)

            farbe.color = when {
                gedrueckt -> thema.gedrueckt
                akzentTaste -> thema.akzent
                t.art == Art.ZEICHEN || t.art == Art.LEER -> thema.taste
                else -> thema.sonder
            }
            canvas.drawRoundRect(platz.rect, luecke, luecke, farbe)

            val textFarbe = when {
                akzentTaste -> thema.akzentText
                t.art == Art.LEER -> thema.textLeise
                else -> thema.text
            }
            if (istSymbol(t.art)) {
                zeichneSymbol(canvas, t.art, platz.rect, if (akzentTaste) thema.akzentText else textFarbe)
            } else {
                zeichneText(canvas, t, platz.rect, textFarbe)
            }
            if (t.hinweis.isNotEmpty()) {
                schrift.color = thema.textLeise
                schrift.textSize = 0.17f * tasteHoehe * schriftFaktor.coerceAtMost(1.25f)
                schrift.typeface = schriftBuchstabe
                canvas.drawText(t.hinweis, platz.rect.right - 0.13f * platz.rect.width(), platz.rect.top + 0.24f * tasteHoehe, schrift)
            }
        }
    }

    private fun zeichneText(canvas: Canvas, t: Taste, rect: RectF, textFarbe: Int) {
        schrift.color = textFarbe
        val text = beschriftung(t)
        when {
            t.art == Art.LEER -> {
                schrift.typeface = schriftBuchstabe
                schrift.textSize = 0.2f * tasteHoehe * schriftFaktor.coerceAtMost(1.2f)
            }
            t.art == Art.ZEICHEN && text.length == 1 -> {
                schrift.typeface = schriftBuchstabe
                // Referenz: Schrift 86 px bei 165 px Tastenhoehe
                schrift.textSize = 0.52f * tasteHoehe * schriftFaktor
            }
            else -> {
                schrift.typeface = schriftBeschriftung
                schrift.textSize = 0.27f * tasteHoehe * schriftFaktor.coerceAtMost(1.3f)
            }
        }
        // Nie breiter als die Taste
        val maxBreite = rect.width() - 6 * dichte
        val breite = schrift.measureText(text)
        if (breite > maxBreite) schrift.textSize *= maxBreite / breite
        canvas.drawText(text, rect.centerX(), rect.centerY() - (schrift.descent() + schrift.ascent()) / 2, schrift)
    }

    // Symbole auf einem 24x24-Raster, wie bei Icon-Schriften
    private fun pfad(block: Path.() -> Unit) = Path().apply(block)

    private val pfadShift = pfad {
        moveTo(12f, 4.5f); lineTo(4.5f, 12.5f); lineTo(9f, 12.5f); lineTo(9f, 18.5f)
        lineTo(15f, 18.5f); lineTo(15f, 12.5f); lineTo(19.5f, 12.5f); close()
    }
    private val pfadLoeschen = pfad {
        moveTo(9f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 18.5f); lineTo(9f, 18.5f); lineTo(3f, 12f); close()
        moveTo(12.6f, 9.4f); lineTo(17.4f, 14.6f)
        moveTo(17.4f, 9.4f); lineTo(12.6f, 14.6f)
    }
    private val pfadEnter = mapOf(
        EnterArt.ZEILE to pfad {
            moveTo(19.5f, 6f); lineTo(19.5f, 12.5f); lineTo(5.5f, 12.5f)
            moveTo(9.5f, 8.3f); lineTo(5.3f, 12.5f); lineTo(9.5f, 16.7f)
        },
        EnterArt.SENDEN to pfad {
            moveTo(4f, 4.5f); lineTo(20.5f, 12f); lineTo(4f, 19.5f); lineTo(6.5f, 12f); close()
            moveTo(6.5f, 12f); lineTo(14f, 12f)
        },
        EnterArt.SUCHEN to pfad {
            addCircle(10.5f, 10.5f, 5.8f, Path.Direction.CW)
            moveTo(14.8f, 14.8f); lineTo(20f, 20f)
        },
        EnterArt.LOS to pfad {
            moveTo(4.5f, 12f); lineTo(19.5f, 12f)
            moveTo(13.5f, 6f); lineTo(19.5f, 12f); lineTo(13.5f, 18f)
        },
        EnterArt.WEITER to pfad {
            moveTo(3.5f, 12f); lineTo(16f, 12f)
            moveTo(11f, 7f); lineTo(16f, 12f); lineTo(11f, 17f)
            moveTo(20f, 6.5f); lineTo(20f, 17.5f)
        },
        EnterArt.FERTIG to pfad {
            moveTo(4.5f, 12.5f); lineTo(9.8f, 17.5f); lineTo(19.5f, 7f)
        },
    )
    private val pfadEmoji = pfad {
        addCircle(12f, 12f, 8.6f, Path.Direction.CW)
        moveTo(8.2f, 13.6f); quadTo(12f, 18f, 15.8f, 13.6f)
    }
    private val matrix = Matrix()

    private fun zeichneSymbol(canvas: Canvas, art: Art, rect: RectF, farbeSymbol: Int) {
        val groesse = 0.46f * tasteHoehe
        val skala = groesse / 24f

        val pfad = when (art) {
            Art.SHIFT -> pfadShift
            Art.LOESCHEN -> pfadLoeschen
            Art.EMOJI -> pfadEmoji
            else -> pfadEnter.getValue(enterArt)
        }
        val gefuellt = art == Art.SHIFT && umschalt != Umschalt.AUS
        val farbeAktiv = if (gefuellt) thema.akzent else farbeSymbol
        strich.color = farbeAktiv
        farbe.color = farbeAktiv

        canvas.save()
        matrix.setScale(skala, skala)
        matrix.postTranslate(rect.centerX() - groesse / 2, rect.centerY() - groesse / 2)
        canvas.concat(matrix)
        // Das Zeichenraster ist 24 Einheiten gross; Strichstaerke dort in Rasterwerten
        strich.strokeWidth = 1.9f
        strich.style = if (gefuellt) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(pfad, strich)
        strich.style = Paint.Style.STROKE
        if (art == Art.SHIFT && umschalt == Umschalt.FEST) canvas.drawLine(9f, 21.5f, 15f, 21.5f, strich)
        if (art == Art.EMOJI) {
            farbe.style = Paint.Style.FILL
            canvas.drawCircle(9f, 9.8f, 1.15f, farbe)
            canvas.drawCircle(15f, 9.8f, 1.15f, farbe)
        }
        canvas.restore()
    }

    /**
     * Die Auswahl beim langen Drücken: ein eigenes Fenster über dem Finger. In die View selbst gezeichnet
     * würde sie bei der obersten Reihe genau die Taste verdecken, die gerade gedrückt wird.
     */
    private inner class AuswahlAnsicht(context: Context, private val zeichen: List<String>, private val zelle: Float) : View(context) {
        var index = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        private val farbe = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = schriftBuchstabe }

        init {
            background = GradientDrawable().apply { setColor(thema.sonder); cornerRadius = luecke }
            elevation = 8 * dichte
        }

        override fun onDraw(canvas: Canvas) {
            zeichen.forEachIndexed { i, z ->
                val links = i * zelle
                if (i == index) {
                    farbe.color = thema.akzent
                    canvas.drawRoundRect(RectF(links + 3 * dichte, 3 * dichte, links + zelle - 3 * dichte, height - 3 * dichte), luecke, luecke, farbe)
                }
                text.color = if (i == index) thema.akzentText else thema.text
                text.textSize = 0.42f * tasteHoehe * schriftFaktor
                val anzeige = if (umschalt != Umschalt.AUS) TextLogik.gross(z) else z
                canvas.drawText(anzeige, links + zelle / 2, height / 2f - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }

    private fun zeigeAuswahl(platz: Platz) {
        val zelle = maxOf(platz.rect.width(), 40 * dichte)
        val breite = zelle * auswahl.size
        val ansicht = AuswahlAnsicht(context, auswahl, zelle).also { it.index = auswahlIndex }
        val fenster = PopupWindow(ansicht, breite.toInt(), tasteHoehe.toInt(), false).apply {
            isTouchable = false
            isClippingEnabled = false
            setBackgroundDrawable(null)
        }
        val ort = IntArray(2)
        getLocationOnScreen(ort)
        val links = (platz.rect.centerX() - zelle / 2).coerceIn(0f, maxOf(0f, width - breite))
        val oben = platz.rect.top - tasteHoehe - lueckeHoch
        try {
            fenster.showAtLocation(this, Gravity.NO_GRAVITY, ort[0] + links.toInt(), ort[1] + oben.toInt())
            auswahlFenster = fenster
            auswahlAnsicht = ansicht
        } catch (_: Exception) {
            // Fenster nicht moeglich (z. B. Tastatur gerade geschlossen): ohne Auswahl weitermachen
            auswahl = emptyList()
        }
        // Breite der Zellen fuer die Zuordnung der Fingerposition merken
        auswahlLinks = ort[0] + links
        auswahlZelle = zelle
    }

    private var auswahlLinks = 0f
    private var auswahlZelle = 1f

    private fun schliesseAuswahl() {
        try { auswahlFenster?.dismiss() } catch (_: Exception) {}
        auswahlFenster = null
        auswahlAnsicht = null
    }

    // ---------- Beruehrung ----------

    /** Die Taste unter dem Finger; in den Luecken zaehlt die naechste. */
    private fun platzBei(x: Float, y: Float): Platz? {
        var beste: Platz? = null
        var kuerzeste = Float.MAX_VALUE
        for (p in plaetze) {
            val dx = maxOf(p.rect.left - x, 0f, x - p.rect.right)
            val dy = maxOf(p.rect.top - y, 0f, y - p.rect.bottom)
            val d = dx * dx + dy * dy
            if (d < kuerzeste) { kuerzeste = d; beste = p }
            if (d == 0f) break
        }
        return beste
    }

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
        startY = y
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
            val ort = IntArray(2)
            getLocationOnScreen(ort)
            auswahlIndex = (((ort[0] + x) - auswahlLinks) / auswahlZelle).toInt().coerceIn(0, auswahl.size - 1)
            auswahlAnsicht?.index = auswahlIndex
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
        zeigeAuswahl(platz)
        rueckmeldung()
        invalidate()
    }

    private fun beende(abbrechen: Boolean) {
        val platz = aktiv
        zeiger.removeCallbacks(langDruck)
        zeiger.removeCallbacks(loeschWiederholung)
        schliesseAuswahl()
        aktiv = null
        zeigerId = -1
        if (platz != null && !abbrechen) {
            val t = platz.taste
            when {
                langGedrueckt && auswahl.isNotEmpty() -> zuhoerer.zeichen(auswahl[auswahlIndex], null)
                langGedrueckt -> {}
                t.art == Art.LEER && leerVersatz != 0 -> {}
                t.art == Art.ZEICHEN -> {
                    val anschlag = if (t.text.length == 1 && spalte > 0f) Anschlag(t.text[0], startX / spalte, startY / spalte) else null
                    zuhoerer.zeichen(t.text, anschlag)
                }
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
        schliesseAuswahl()
        zeiger.removeCallbacksAndMessages(null)
    }
}
