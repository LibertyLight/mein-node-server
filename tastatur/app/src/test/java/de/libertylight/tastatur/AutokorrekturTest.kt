package de.libertylight.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/** Autokorrektur mit den echten Sprachdaten der App. */
class AutokorrekturTest {

    private class Ablage : Woerterbuch.Speicher {
        var inhalt: String? = null
        override fun lies() = inhalt
        override fun schreib(inhalt: String) { this.inhalt = inhalt }
    }

    companion object {
        val modell = Sprachmodell(
            File("src/main/res/raw/woerter_de.txt").readLines(),
            File("src/main/res/raw/folgen_de.txt").readLines(),
        )
    }

    private fun neu(ablage: Ablage = Ablage()) = Woerterbuch(modell, ablage)

    private val wb = neu()

    // ---------------------------------------------------------------- Korrigieren

    @Test fun typischeTippfehlerWerdenBehoben() {
        val faelle = mapOf(
            "ihc" to "ich", "nicth" to "nicht", "hbae" to "habe", "fur" to "für", "uber" to "über",
            "vileicht" to "vielleicht", "eigentlcih" to "eigentlich", "Shcule" to "Schule", "Wochenednde" to "Wochenende",
            "koennen" to "können", "weiss" to "weiß", "gestren" to "gestern", "morgne" to "morgen", "scohn" to "schon",
        )
        val fehler = faelle.mapNotNull { (getippt, erwartet) ->
            val ist = wb.korrektur(getippt)
            if (ist != erwartet) "$getippt → $ist (erwartet $erwartet)" else null
        }
        assertTrue(fehler.joinToString("\n"), fehler.isEmpty())
    }

    @Test fun zusammengeschriebeneWoerterWerdenGetrennt() {
        assertEquals("ich habe", wb.korrektur("ichhabe"))
        assertEquals("das ist", wb.korrektur("dasist"))
    }

    @Test fun echteWoerterBleibenUnangetastet() {
        // bekannte, unbekannte (Name, Marke, Zusammensetzung, Beugung), Abkürzung, Umgangssprache
        for (w in listOf("Hund", "und", "Itzehoe", "WhatsApp", "iPhone", "Haustürschlüssel", "Schulbusfahrer", "Gegenstands",
            "mochte", "NRW", "USA", "ok", "okay", "moin", "Kratt", "Bundesregierung")) {
            assertNull("$w wurde geändert", wb.korrektur(w))
        }
    }

    @Test fun kurzeWoerterUndZeichenfolgenMitZiffernBleiben() {
        for (w in listOf("tg", "x", "B12", "a1b2", "geht's", "E-Mail", "ab")) assertNull(w, wb.korrektur(w))
    }

    @Test fun substantiveWerdenGrossgeschrieben() {
        assertEquals("Haus", wb.korrektur("haus"))
        assertEquals("Schule", wb.korrektur("schule"))
        assertEquals("Immer", wb.korrektur("IMmer"))     // Umschalttaste zu lange gehalten
        assertNull(wb.korrektur("essen"))                // Verb und Substantiv: nicht raten
        assertNull(wb.korrektur("Haus"))
        assertNull(wb.korrektur("ist"))
    }

    @Test fun grossschreibungLaesstSichAbschalten() {
        val ohne = neu().apply { substantiveGross = false }
        assertNull(ohne.korrektur("haus"))
        assertEquals("ich", ohne.korrektur("ihc"))
    }

    @Test fun korrekturUebernimmtDieGrossschreibungDesGetippten() {
        assertEquals("Ich", wb.korrektur("Ihc"))
        assertEquals("Schule", wb.korrektur("Schuel"))
    }

    // ---------------------------------------------------------------- Lernen

    @Test fun bestaetigtesWortWirdNichtMehrGeaendert() {
        val eigenes = neu()
        assertEquals("Haus", eigenes.korrektur("haus"))
        eigenes.bestaetige("haus")
        assertNull(eigenes.korrektur("haus"))
        assertEquals("ich", eigenes.korrektur("ihc"))
        eigenes.bestaetige("ihc")
        assertNull(eigenes.korrektur("ihc"))
    }

    @Test fun eigeneWoerterWerdenNachZweiMalBekannt() {
        val eigenes = neu()
        val wort = "Qwurzel"
        eigenes.lerne(wort, "die")
        assertFalse(eigenes.kennt(wort))
        eigenes.lerne(wort, "die")
        assertTrue(eigenes.kennt(wort))
        assertNull(eigenes.korrektur(wort))
    }

    @Test fun gelerntesUndBestaetigtesUeberlebtNeustart() {
        val ablage = Ablage()
        neu(ablage).apply { lerne("Zwiebelmuster", "das"); lerne("Zwiebelmuster", "das"); bestaetige("haus"); speichere() }
        val neuer = neu(ablage)
        assertTrue(neuer.kennt("Zwiebelmuster"))
        assertNull(neuer.korrektur("haus"))
    }

    @Test fun lerntKeineZahlenUndEinzelbuchstaben() {
        val eigenes = neu()
        eigenes.lerne("x"); eigenes.lerne("12:30"); eigenes.lerne("A1B2")
        assertFalse(eigenes.kennt("x"))
        assertFalse(eigenes.kennt("a1b2"))
    }

    @Test fun vergessenUndAllesVergessen() {
        val ablage = Ablage()
        val eigenes = neu(ablage)
        repeat(2) { eigenes.lerne("Tippfehlr") }
        assertTrue(eigenes.kennt("Tippfehlr"))
        eigenes.vergiss("Tippfehlr")
        assertFalse(eigenes.kennt("Tippfehlr"))
        repeat(2) { eigenes.lerne("Wortzwei") }
        eigenes.vergissAlles()
        assertFalse(neu(ablage).kennt("Wortzwei"))
    }

    // ---------------------------------------------------------------- Berührungsstellen

    @Test fun beruehrungsstelleEntscheidetZwischenNachbartasten() {
        // "m" und "n" liegen nebeneinander. Ein Druck ganz links in "n" meint eher "n", einer an der Grenze auch "m".
        val karte = Tastenkarte.STANDARD
        val n = karte.mitte('n')!!
        val m = karte.mitte('m')!!
        val nahAnM = Anschlag('n', (n.first + m.first) / 2f + 0.02f - 0.04f, n.second)
        val mitteN = Anschlag('n', n.first, n.second)
        // gleiche Buchstaben, aber unterschiedlich weit von m entfernt: "hauns" (n getippt) soll bei der Grenze eher Richtung "Haums" kosten als bei der Mitte
        val f = Fehlermodell(karte, Parameter())
        val grenze = f.ersetzTabelle("n".toCharArray(), listOf(nahAnM))[0][Fehlermodell.zeichenNr('m')]
        val zentrum = f.ersetzTabelle("n".toCharArray(), listOf(mitteN))[0][Fehlermodell.zeichenNr('m')]
        assertTrue("an der Grenze ($grenze) muss billiger sein als in der Mitte ($zentrum)", grenze < zentrum - 1.0f)
    }

    // ---------------------------------------------------------------- Vorschläge

    @Test fun vorschlaegeZeigenTippfehlerUndKorrekturUndErgaenzungen() {
        val v = wb.vorschlaege("Shcule")
        assertEquals(listOf("Shcule", "Schule"), v.liste.take(2))
        assertEquals("Schule", v.korrektur)
        val w = wb.vorschlaege("Hal", Kontext.SATZANFANG)
        assertEquals("Hal", w.liste[0])
        assertTrue(w.liste.toString(), "Hallo" in w.liste)
        assertNull(w.korrektur)
    }

    @Test fun naechsteWoerterKommenAusDemSprachmodell() {
        assertEquals("Tag", wb.vorschlaege("", Kontext("guten")).liste[0])
        assertTrue(wb.vorschlaege("", Kontext("guten")).liste.toString(), "Morgen" in wb.vorschlaege("", Kontext("guten")).liste)
        assertEquals("Dank", wb.vorschlaege("", Kontext("vielen")).liste[0])
        assertEquals("ist", wb.vorschlaege("", Kontext("wo")).liste[0])
        // am Satzanfang großgeschrieben
        assertTrue(wb.vorschlaege("", Kontext.SATZANFANG).liste.all { it[0].isUpperCase() })
    }

    @Test fun eigenesFolgewortRueckVor() {
        val eigenes = neu()
        repeat(3) { eigenes.lerne("Itzehoe", "nach") }
        assertEquals("Itzehoe", eigenes.vorschlaege("", Kontext("nach")).liste[0])
    }

    // ---------------------------------------------------------------- Bauteile

    @Test fun fuzzySucheLiefertDieselbenKostenWieDieDirekteRechnung() {
        val karte = Tastenkarte.STANDARD
        val p = Parameter()
        val fehler = Fehlermodell(karte, p)
        val suche = FuzzySuche(modell, p)
        val rng = Random(5)
        val buchstaben = "abcdefghijklmnopqrstuvwxyz"
        repeat(40) {
            // ein Wort aus der Liste, mit zwei zufälligen Fehlern
            val wort = modell.schluessel[rng.nextInt(modell.anzahl)].take(12)
            val sb = StringBuilder(wort)
            repeat(2) {
                if (sb.length > 2) when (rng.nextInt(3)) {
                    0 -> sb.deleteCharAt(rng.nextInt(sb.length))
                    1 -> sb.insert(rng.nextInt(sb.length), buchstaben[rng.nextInt(26)])
                    else -> { val i = rng.nextInt(sb.length - 1); val c = sb[i]; sb.setCharAt(i, sb[i + 1]); sb.setCharAt(i + 1, c) }
                }
            }
            val typ = sb.toString().toCharArray()
            val ersetz = fehler.ersetzTabelle(typ, null)
            val ins = fehler.einfuegenKosten(typ)
            val treffer = suche.suche(typ, ersetz, ins, p.suchGrenze)
            // jeder Treffer muss mit der direkten Rechnung übereinstimmen
            for (t in treffer.take(20)) {
                val direkt = suche.kosten(typ, ersetz, ins, modell.schluessel[t.index])
                assertEquals("${String(typ)} → ${modell.schluessel[t.index]}", direkt, t.kosten, 0.001f)
            }
            // und es darf kein billigeres Wort fehlen: Stichprobe unter den Wörtern mit gleichem Anfang
            val bereich = modell.praefixBereich(String(typ).take(2))
            val gefunden = treffer.map { it.index }.toSet()
            for (i in bereich.take(300)) {
                val direkt = suche.kosten(typ, ersetz, ins, modell.schluessel[i])
                if (direkt <= p.suchGrenze) assertTrue("fehlt: ${modell.schluessel[i]} für ${String(typ)} (Kosten $direkt)", i in gefunden)
            }
        }
    }

    @Test fun sprachmodellKenntWoerterUndFolgen() {
        val i = modell.index("haus")
        assertTrue(i >= 0)
        assertEquals("Haus", modell.schreibweise(i))
        assertTrue(modell.istSubstantiv(i))
        assertFalse(modell.istSubstantiv(modell.index("und")))
        assertTrue(modell.istFunktionswort(modell.index("ich")))
        assertFalse(modell.istFunktionswort(modell.index("haus")))
        assertNotNull(modell.nachfolger(modell.index("guten")))
        assertNotNull(modell.nachfolger(Sprachmodell.SATZANFANG_NR))
        assertEquals(-1, modell.index("qqqqqq"))
        assertTrue(modell.praefixBereich("hausa").count() > 0)
    }

    @Test fun abfragenSindSchnellGenugFuerJedenTastendruck() {
        repeat(20) { wb.vorschlaege("wochenednde"); wb.vorschlaege("d"); wb.korrektur("vileicht"); wb.vorschlaege("sch") } // warmlaufen
        val start = System.nanoTime()
        repeat(100) { wb.vorschlaege("wochenednde"); wb.vorschlaege("d", Kontext("das")); wb.korrektur("vileicht"); wb.vorschlaege("sch") }
        val msProAbfrage = (System.nanoTime() - start) / 1e6 / 400
        assertTrue("$msProAbfrage ms pro Abfrage", msProAbfrage < 15)
    }
}
