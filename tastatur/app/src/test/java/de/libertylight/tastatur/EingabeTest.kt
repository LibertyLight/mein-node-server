package de.libertylight.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ein Textfeld, das nur aus Text und Cursor am Ende besteht. */
class SimulierterText : TextFeld {
    val text = StringBuilder()
    var gruppen = 0
    override fun vorCursor(anzahl: Int): CharSequence = text.takeLast(anzahl)
    override fun loescheVorCursor(anzahl: Int) { text.setLength(maxOf(0, text.length - anzahl)) }
    override fun schreibe(text: String) { this.text.append(text) }
    override fun loescheTaste() { if (text.isNotEmpty()) text.setLength(text.length - 1) }
    override fun gruppe(block: () -> Unit) { gruppen++; block() }
    override fun toString() = text.toString()
}

/** Tippen und Korrigieren am simulierten Textfeld, mit den echten Sprachdaten. */
class EingabeTest {

    private class Ablage : Woerterbuch.Speicher {
        override fun lies(): String? = null
        override fun schreib(inhalt: String) {}
    }

    companion object {
        val modell = Sprachmodell(
            File("src/main/res/raw/woerter_de.txt").readLines(),
            File("src/main/res/raw/folgen_de.txt").readLines(),
        )
    }

    private class Aufbau(modell: Sprachmodell) {
        val feld = SimulierterText()
        val wb = Woerterbuch(modell, Ablage())
        var jetzt = 1_000L
        val eingabe = Eingabe(feld, { wb }, { jetzt })
        fun tippe(text: String) { for (c in text) eingabe.zeichen(c.toString(), null) }
        fun leer() { eingabe.leer(); jetzt += 1000 }
    }

    private fun aufbau() = Aufbau(modell)

    @Test fun leertasteKorrigiertDasWort() {
        val a = aufbau()
        a.tippe("ihc"); a.leer()
        assertEquals("ich ", a.feld.toString())
        a.tippe("habe"); a.leer(); a.tippe("Shcule"); a.leer()
        assertEquals("ich habe Schule ", a.feld.toString())
    }

    @Test fun satzzeichenKorrigiertAuchUndBleibtDanach() {
        val a = aufbau()
        a.tippe("Es ist "); a.tippe("nicth!")
        assertEquals("Es ist nicht!", a.feld.toString())
    }

    @Test fun loeschenNachKorrekturHolztDasGetippteZurueckUndMerktEsSich() {
        val a = aufbau()
        a.tippe("ihc"); a.leer()
        assertEquals("ich ", a.feld.toString())
        a.eingabe.loesche()
        assertEquals("ihc ", a.feld.toString())
        // weiter getippt wird normal, und das Wort bleibt so
        a.eingabe.loesche(); a.eingabe.loesche(); a.eingabe.loesche(); a.eingabe.loesche()
        assertEquals("", a.feld.toString())
        a.tippe("ihc"); a.leer()
        assertEquals("ihc ", a.feld.toString())
    }

    @Test fun zweitesLoeschenLoeschtGewoehnlich() {
        val a = aufbau()
        a.tippe("ihc"); a.leer(); a.eingabe.loesche()      // zurück zu "ihc "
        a.eingabe.loesche()                                // gewöhnliches Löschen
        assertEquals("ihc", a.feld.toString())
    }

    @Test fun loeschenNachKorrekturBeiSatzzeichenStelltAuchDasSatzzeichenWiederHer() {
        val a = aufbau()
        a.tippe("ihc,")
        assertEquals("ich,", a.feld.toString())
        a.eingabe.loesche()
        assertEquals("ihc,", a.feld.toString())
    }

    @Test fun grossschreibungVonSubstantivenUndRueckgaengig() {
        val a = aufbau()
        a.tippe("das haus"); a.leer()
        assertEquals("das Haus ", a.feld.toString())
        a.eingabe.loesche()
        assertEquals("das haus ", a.feld.toString())
    }

    @Test fun doppelteLeertasteMachtEinenPunkt() {
        val a = aufbau()
        a.tippe("Fertig"); a.eingabe.leer(); a.jetzt += 200; a.eingabe.leer()
        assertEquals("Fertig. ", a.feld.toString())
    }

    @Test fun doppelteLeertasteNachKorrekturMachtAuchEinenPunkt() {
        val a = aufbau()
        a.tippe("Es ist nicth"); a.eingabe.leer(); a.jetzt += 200; a.eingabe.leer()
        assertEquals("Es ist nicht. ", a.feld.toString())
    }

    @Test fun inAdressenWirdNichtKorrigiert() {
        val a = aufbau()
        a.tippe("www.ihcdumm"); a.leer()
        assertEquals("www.ihcdumm ", a.feld.toString())
        a.tippe("@ihc"); a.leer()
        assertEquals("www.ihcdumm @ihc ", a.feld.toString())
    }

    @Test fun ohneKorrekturErlaubnisBleibtAllesWieGetippt() {
        val a = aufbau()
        a.eingabe.korrigieren = false
        a.tippe("ihc haus"); a.leer()
        assertEquals("ihc haus ", a.feld.toString())
    }

    @Test fun inPasswortfeldernWirdNichtsGelernt() {
        val a = aufbau()
        a.eingabe.lernen = false; a.eingabe.korrigieren = false
        repeat(3) { a.tippe("Geheimwortxyz"); a.leer() }
        assertFalse(a.wb.kennt("Geheimwortxyz"))
    }

    @Test fun enterSchliesstDasWortAb() {
        val a = aufbau()
        a.tippe("ihc"); a.eingabe.wortAbschliessen()
        assertEquals("ich", a.feld.toString())
    }

    @Test fun vorschlagAntippenErsetztUndSetztEinLeerzeichen() {
        val a = aufbau()
        a.tippe("ihc")
        val leiste = a.eingabe.leiste()!!
        assertEquals("ihc", leiste.wort)
        assertTrue(leiste.unbekannt)
        assertEquals(1, leiste.hervorgehoben)
        a.eingabe.waehle(leiste.liste[1], leiste.wort, false)
        assertEquals("ich ", a.feld.toString())
        // die Leertaste direkt danach fügt nichts hinzu
        a.eingabe.leer()
        assertEquals("ich ", a.feld.toString())
    }

    @Test fun satzzeichenNachVorschlagRuecktAnDasWortHeran() {
        val a = aufbau()
        a.tippe("ihc")
        a.eingabe.waehle("ich", "ihc", false)
        a.eingabe.zeichen(",", null)
        assertEquals("ich, ", a.feld.toString())
    }

    @Test fun getipptesAntippenObwohlKorrekturBereitStandBestaetigtEs() {
        val a = aufbau()
        a.tippe("Shcule")
        val leiste = a.eingabe.leiste()!!
        assertEquals("Schule", leiste.liste[leiste.hervorgehoben])
        a.eingabe.waehle(leiste.wort, leiste.wort, true)
        assertEquals("Shcule ", a.feld.toString())
        // beim nächsten Mal bleibt es so
        a.tippe("Shcule"); a.leer()
        assertEquals("Shcule Shcule ", a.feld.toString())
    }

    @Test fun leisteZeigtNaechsteWoerterUndErgaenzungen() {
        val a = aufbau()
        a.tippe("guten ")
        val danach = a.eingabe.leiste()!!
        assertEquals("", danach.wort)
        assertEquals("Tag", danach.liste[0])
        a.tippe("Mo")
        val ergaenzung = a.eingabe.leiste()!!
        assertEquals("Mo", ergaenzung.wort)
        assertTrue(ergaenzung.liste.toString(), "Morgen" in ergaenzung.liste)
        assertFalse(ergaenzung.unbekannt)
    }

    @Test fun beruehrungsstellenBleibenAmWortUndFallenBeimLoeschenWeg() {
        val a = aufbau()
        val karte = Tastenkarte.STANDARD
        fun druck(c: Char) = karte.mitte(c)!!.let { Anschlag(c, it.first, it.second) }
        // "nicth" mit sauberen Fingerdrücken, dazu ein falscher Buchstabe, der wieder gelöscht wird
        for (c in "nicthx") a.eingabe.zeichen(c.toString(), druck(c))
        a.eingabe.loesche()
        assertEquals("nicth", a.feld.toString())
        a.leer()
        assertEquals("nicht ", a.feld.toString())
    }

    @Test fun unpassendeBeruehrungsstellenSchadenNicht() {
        val a = aufbau()
        // Fingerdrücke, die nicht zu den Buchstaben im Feld passen (z. B. nach Bearbeitung von außen)
        for (c in "ihc") a.eingabe.zeichen(c.toString(), Anschlag('q', 0f, 0f))
        a.leer()
        assertEquals("ich ", a.feld.toString())
    }

    @Test fun textVonAussenBeendetDasWort() {
        val a = aufbau()
        a.tippe("ihc")
        a.eingabe.schreibeText("😀")
        a.leer()
        assertEquals("ihc😀 ", a.feld.toString())   // das Wort war schon zu Ende, wird nicht mehr angefasst
    }

    @Test fun mehrereAenderungenLaufenAlsEineGruppe() {
        val a = aufbau()
        a.tippe("ihc")
        val vorher = a.feld.gruppen
        a.leer()
        assertEquals(vorher + 1, a.feld.gruppen)
        assertNotNull(a.feld.toString())
        assertNull(null)
    }
}
