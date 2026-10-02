package de.libertylight.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Autokorrektur mit dem echten Grundwortschatz der App. */
class AutokorrekturTest {

    private class Ablage : Woerterbuch.Speicher {
        var inhalt: String? = null
        override fun lies() = inhalt
        override fun schreib(inhalt: String) { this.inhalt = inhalt }
    }

    companion object {
        private val grund = File("src/main/res/raw/woerter_de.txt").readLines()
        private val wb = Woerterbuch(grund, Ablage())
    }

    @Test fun typischeTippfehler() {
        val faelle = mapOf(
            "ihc" to "ich", "nicth" to "nicht", "hbae" to "habe", "fur" to "für", "uber" to "über",
            "schon" to null, // bekanntes Wort bleibt
            "Hnud" to "Hund", "dsa" to "das", "wiet" to "weit", "morgne" to "morgen",
            "vileicht" to "vielleicht", "eigentlcih" to "eigentlich", "Schuel" to "Schule",
            "Wochenednde" to "Wochenende", "gestren" to "gestern", "hauae" to "Hause",
        )
        val fehler = faelle.mapNotNull { (getippt, erwartet) ->
            val ist = wb.korrektur(getippt)
            if (ist != erwartet) "$getippt → $ist (erwartet $erwartet)" else null
        }
        assertTrue(fehler.joinToString("\n"), fehler.isEmpty())
    }

    @Test fun bekannteUndSpezielleWoerterBleiben() {
        for (w in listOf("Hund", "und", "Itzehoe", "WhatsApp", "ABC", "B12", "ok", "Bundesregierung")) {
            assertNull(w, wb.korrektur(w))
        }
    }

    @Test fun gelerntesWortWirdNichtMehrKorrigiert() {
        val eigenes = Woerterbuch(grund, Ablage())
        eigenes.lerne("Kratt", "im")
        eigenes.lerne("Kratt", "im")
        assertNull(eigenes.korrektur("Kratt"))
    }

    @Test fun einmalGetippterTippfehlerWirdTrotzdemKorrigiert() {
        val eigenes = Woerterbuch(grund, Ablage())
        eigenes.lerne("nicth")
        assertEquals("nicht", eigenes.korrektur("nicth"))
        eigenes.lerne("nicth", "", 2) // nach ⌫ bestaetigt
        assertNull(eigenes.korrektur("nicth"))
    }

    @Test fun vorschlaegeZeigenDieKorrekturAnZweiterStelle() {
        val v = wb.vorschlaege("Shcule")
        assertEquals(listOf("Shcule", "Schule"), v.take(2))
    }

    @Test fun grossschreibungDesGetipptenBleibt() {
        assertEquals("Ich", wb.korrektur("Ihc"))
    }

    @Test fun schnellGenugFuerJedenTastendruck() {
        val start = System.nanoTime()
        repeat(50) { wb.vorschlaege("wochenedn"); wb.vorschlaege("d"); wb.korrektur("vileicht") }
        val msProRunde = (System.nanoTime() - start) / 1e6 / 50
        assertTrue("$msProRunde ms", msProRunde < 40)
    }
}
