package de.libertylight.tastatur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Zeichnet das Tastenfeld mit echter Android-Grafik (1080 px Breite, wie der Referenz-Screenshot)
 * und prueft die Masse gegen die Messwerte des Screenshots.
 *
 * Mit TASTATUR_BILDER=<Ordner> werden zusaetzlich PNGs zum Anschauen abgelegt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class DarstellungTest {

    private val keinHoerer = object : TastenfeldView.Zuhoerer {
        override fun zeichen(text: String, anschlag: Anschlag?) {}
        override fun aktion(art: Art) {}
        override fun cursor(schritte: Int) {}
        override fun leertasteLang() {}
        override fun rueckmeldung() {}
    }

    private fun zeichne(name: String, thema: Thema, optionen: Optionen, seite: Seite): Bitmap {
        val context = RuntimeEnvironment.getApplication()
        val v = TastenfeldView(context, keinHoerer).apply {
            this.thema = thema
            reihen = Belegung.reihen(seite, optionen)
        }
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        System.getenv("TASTATUR_BILDER")?.let { ordner ->
            File(ordner).mkdirs()
            File(ordner, "tastenfeld_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bmp
    }

    private fun hell(farbe: Int) = Math.round(0.299 * Color.red(farbe) + 0.587 * Color.green(farbe) + 0.114 * Color.blue(farbe)).toInt()

    /** Zusammenhaengende Abschnitte, die mindestens so hell sind wie [schwelle] und breiter als [mindest]. */
    private fun abschnitte(bmp: Bitmap, y: Int, schwelle: Int, mindest: Int = 30): List<Pair<Int, Int>> {
        val liste = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (x in 0 until bmp.width) {
            val an = hell(bmp.getPixel(x, y)) >= schwelle
            if (an && start < 0) start = x
            if (!an && start >= 0) { if (x - start >= mindest) liste += start to x - 1; start = -1 }
        }
        if (start >= 0 && bmp.width - start >= mindest) liste += start to bmp.width - 1
        return liste
    }

    private fun spalte(bmp: Bitmap, x: Int, schwelle: Int, mindest: Int = 40): List<Pair<Int, Int>> {
        val liste = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (y in 0 until bmp.height) {
            val an = hell(bmp.getPixel(x, y)) >= schwelle
            if (an && start < 0) start = y
            if (!an && start >= 0) { if (y - start >= mindest) liste += start to y - 1; start = -1 }
        }
        return liste
    }

    @Test fun rasterEntsprichtDemReferenzScreenshot() {
        val bmp = zeichne("hell", Thema.HELL, Optionen(), Seite.BUCHSTABEN)
        // Referenz: erste Taste beginnt 9 px unter der Oberkante der Tastatur (1545 - 1536)
        val reihe1 = (0 until 10).map { 8 + it * 108 to 99 + it * 108 }
        assertEquals("Reihe 1", reihe1, abschnitte(bmp, 24, 252))
        assertEquals("Reihe 2 (um eine halbe Taste eingerueckt)", (0 until 9).map { 62 + it * 108 to 153 + it * 108 }, abschnitte(bmp, 206, 252))
        assertEquals("Reihe 3, Buchstaben", (0 until 7).map { 170 + it * 108 to 261 + it * 108 }, abschnitte(bmp, 389, 252))

        // senkrecht: Tasten 163 px hoch, Zeilenabstand 183 px (Toleranz 2 px)
        val tasten = spalte(bmp, 560, 252)
        assertTrue("vier Reihen, gefunden: $tasten", tasten.size >= 3)
        assertEquals("Oberkante", 9.0, tasten[0].first.toDouble(), 2.0)
        for (i in 1 until 3) assertEquals("Zeilenabstand $i", 183.0, (tasten[i].first - tasten[i - 1].first).toDouble(), 2.0)
        assertEquals("Tastenhoehe", 163.0, (tasten[0].second - tasten[0].first + 1).toDouble(), 3.0)

        // Unterreihe: 123, Emoji, Komma | Leertaste 4,5 | Punkt | Enter 1,5
        val unten = abschnitte(bmp, 2108 - 1536, 248)
        assertEquals(listOf(8 to 99, 116 to 207, 224 to 315, 332 to 801, 818 to 909, 926 to 1071), unten)

        // Farben wie im Screenshot: Hintergrund 241, Sondertasten 250, Taste 255
        assertEquals(241, hell(bmp.getPixel(2, 2)))
        assertEquals(255, hell(bmp.getPixel(60, 24)))
        assertEquals(250, hell(bmp.getPixel(30, 389)))
    }

    @Test fun schriftgroesseEntsprichtDemReferenzScreenshot() {
        val bmp = zeichne("schrift", Thema.HELL, Optionen(), Seite.BUCHSTABEN)
        // 'w' in der zweiten Taste: Referenz 58 x 44 px, 'q' 37 x 62 px (mit Unterlaenge)
        fun glyph(x0: Int, x1: Int): IntArray {
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            for (y in 0 until 170) for (x in x0..x1) if (hell(bmp.getPixel(x, y)) < 140) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            return intArrayOf(maxX - minX + 1, maxY - minY + 1)
        }
        val w = glyph(116, 207)
        assertEquals("Breite w", 58.0, w[0].toDouble(), 4.0)
        assertEquals("Hoehe w", 44.0, w[1].toDouble(), 3.0)
        val q = glyph(8, 99)
        assertEquals("Hoehe q", 62.0, q[1].toDouble(), 3.0)
    }

    @Test fun weitereAnsichtenLassenSichZeichnen() {
        zeichne("dunkel", Thema.DUNKEL, Optionen(), Seite.BUCHSTABEN)
        zeichne("umlaute", Thema.HELL, Optionen(umlautTasten = true, zahlenreihe = true), Seite.BUCHSTABEN)
        zeichne("symbole", Thema.HELL, Optionen(), Seite.SYMBOLE)
        zeichne("symbole2", Thema.HELL, Optionen(), Seite.SYMBOLE2)
        zeichne("ziffern", Thema.HELL, Optionen(), Seite.ZIFFERN)
        zeichne("telefon", Thema.DUNKEL, Optionen(), Seite.TELEFON)
    }
}
