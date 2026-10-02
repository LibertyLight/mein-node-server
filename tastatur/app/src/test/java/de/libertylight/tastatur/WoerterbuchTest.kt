package de.libertylight.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoerterbuchTest {

    private class Ablage(var inhalt: String? = null) : Woerterbuch.Speicher {
        override fun lies() = inhalt
        override fun schreib(inhalt: String) { this.inhalt = inhalt }
    }

    private val grund = listOf("ich", "die", "Hallo", "haben", "habe", "Haus", "heute", "morgen", "Morgen")

    @Test fun vervollstaendigtUndBehaeltGetipptesVorn() {
        val wb = Woerterbuch(grund, Ablage())
        val v = wb.vorschlaege("ha")
        assertEquals("ha", v[0])
        assertTrue(v.containsAll(listOf("haben", "habe")) || v.contains("Hallo"))
        assertEquals(3, v.size)
    }

    @Test fun gelernteWoerterGewinnen() {
        val wb = Woerterbuch(grund, Ablage())
        repeat(3) { wb.lerne("Hamster") }
        assertEquals("Hamster", wb.vorschlaege("ha")[1])
    }

    @Test fun grossschreibungDesGetipptenWirdUebernommen() {
        val wb = Woerterbuch(grund, Ablage())
        assertTrue(wb.vorschlaege("He").contains("Heute"))
    }

    @Test fun schlaegtKorrekturenVor() {
        val wb = Woerterbuch(grund, Ablage())
        assertTrue(wb.vorschlaege("hute").contains("heute"))
    }

    @Test fun folgewoerterNachVorherigemWort() {
        val wb = Woerterbuch(grund, Ablage())
        wb.lerne("Morgen", "guten")
        wb.lerne("Morgen", "guten")
        wb.lerne("Abend", "guten")
        assertEquals(listOf("Morgen", "Abend"), wb.vorschlaege("", "Guten"))
    }

    @Test fun lerntKeineZahlenUndEinzelbuchstaben() {
        val wb = Woerterbuch(emptyList(), Ablage())
        wb.lerne("x")
        wb.lerne("12:30")
        wb.lerne("A1B2")
        assertFalse(wb.kennt("x"))
        assertFalse(wb.kennt("a1b2"))
    }

    @Test fun speichertUndLaedt() {
        val ablage = Ablage()
        Woerterbuch(grund, ablage).apply { lerne("Itzehoe"); lerne("Kratt", "im"); speichere() }
        val neu = Woerterbuch(grund, ablage)
        assertTrue(neu.kennt("itzehoe"))
        assertEquals(listOf("Kratt"), neu.vorschlaege("", "im"))
    }

    @Test fun vergessen() {
        val ablage = Ablage()
        val wb = Woerterbuch(emptyList(), ablage)
        wb.lerne("Tippfehlr")
        wb.vergiss("Tippfehlr")
        assertFalse(wb.kennt("tippfehlr"))
        wb.lerne("Wort")
        wb.vergissAlles()
        assertFalse(Woerterbuch(emptyList(), ablage).kennt("wort"))
    }
}
