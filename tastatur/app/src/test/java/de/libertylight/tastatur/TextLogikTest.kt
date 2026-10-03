package de.libertylight.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLogikTest {

    @Test fun aktuellesWort() {
        assertEquals("Hal", TextLogik.aktuellesWort("Na, Hal"))
        assertEquals("", TextLogik.aktuellesWort("Hallo "))
        assertEquals("geht's", TextLogik.aktuellesWort("Wie geht's"))
        assertEquals("", TextLogik.aktuellesWort(""))
    }

    @Test fun vorherigesWort() {
        assertEquals("guten", TextLogik.vorherigesWort("Einen guten "))
        assertEquals("guten", TextLogik.vorherigesWort("Einen guten Mo"))
        assertEquals("", TextLogik.vorherigesWort("Fertig. Da"))
        assertEquals("", TextLogik.vorherigesWort("Hallo"))
    }

    @Test fun doppelLeerzeichen() {
        assertTrue(TextLogik.doppelLeerzeichenPunkt("Hallo "))
        assertFalse(TextLogik.doppelLeerzeichenPunkt("Hallo. "))
        assertFalse(TextLogik.doppelLeerzeichenPunkt(" "))
    }

    @Test fun schreibungUebernehmen() {
        assertEquals("Hallo", TextLogik.passeSchreibungAn("hallo", "Hal"))
        assertEquals("HALLO", TextLogik.passeSchreibungAn("hallo", "HA"))
        assertEquals("hallo", TextLogik.passeSchreibungAn("hallo", "ha"))
        assertEquals("Haus", TextLogik.passeSchreibungAn("Haus", "h"))
    }

    @Test fun grossbuchstaben() {
        assertEquals("Ä", TextLogik.gross("ä"))
        assertEquals("ß", TextLogik.gross("ß"))
    }

    @Test fun kontext() {
        assertEquals(Kontext("guten"), TextLogik.kontext("Einen guten Mo"))
        assertEquals(Kontext("guten"), TextLogik.kontext("Einen guten "))
        assertEquals(Kontext.SATZANFANG, TextLogik.kontext(""))
        assertEquals(Kontext.SATZANFANG, TextLogik.kontext("Fertig. Da"))
        assertEquals(Kontext.SATZANFANG, TextLogik.kontext("Hallo!\n"))
        assertEquals(Kontext.KEINER, TextLogik.kontext("Hallo, da"))   // nach dem Komma kein Vorgaengerwort
        assertEquals(Kontext.SATZANFANG, TextLogik.kontext("Hallo"))   // das erste Wort im Feld beginnt einen Satz
    }
}
