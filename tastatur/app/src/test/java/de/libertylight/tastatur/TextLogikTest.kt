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

    @Test fun abstand() {
        assertEquals(0, TextLogik.abstand("haus", "haus"))
        assertEquals(1, TextLogik.abstand("hause", "haus"))
        assertEquals(2, TextLogik.abstand("abc", "xyz", 1))
    }
}
