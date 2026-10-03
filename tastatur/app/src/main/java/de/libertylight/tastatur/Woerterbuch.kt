package de.libertylight.tastatur

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Das Wort vor dem aktuellen -- und ob gerade ein Satz beginnt. */
data class Kontext(val wort: String = "", val satzanfang: Boolean = false) {
    companion object {
        val KEINER = Kontext()
        val SATZANFANG = Kontext(satzanfang = true)
    }
}

/** Was die Vorschlagsleiste zeigt, und was die Leertaste einsetzen würde. */
data class Vorschlaege(val liste: List<String>, val korrektur: String?)

/**
 * Vorschläge und Autokorrektur.
 *
 * Für ein getipptes, unbekanntes Wort wird gerechnet, welches Wort am wahrscheinlichsten gemeint war:
 *
 *     wahrscheinlichkeit(Wort | davor)  -  Kosten, dass sich daraus das Getippte ergibt
 *
 * Die Kosten kommen aus dem [Fehlermodell] -- Nachbartasten, wo der Finger wirklich aufsetzte,
 * vertauschte oder vergessene Buchstaben. Dagegen steht, wie wahrscheinlich es ist, dass das Getippte
 * ein echtes Wort ist, das nur nicht in der Liste steht: ein Name, eine Zusammensetzung oder eine
 * gebeugte Form. Korrigiert wird nur, wenn der beste Kandidat deutlich vorn liegt.
 *
 * Gelernt wird nur auf dem Gerät und über [speicher].
 */
class Woerterbuch(
    private val modell: Sprachmodell,
    private val speicher: Speicher,
    karte: Tastenkarte = Tastenkarte.STANDARD,
    private val p: Parameter = Parameter(),
) {
    interface Speicher {
        fun lies(): String?
        fun schreib(inhalt: String)
    }

    private var fehler = Fehlermodell(karte, p)
    private val suche = FuzzySuche(modell, p)

    /** Substantive bei der Autokorrektur großschreiben ("haus" -> "Haus"). */
    var substantiveGross = true

    private val gelernt = HashMap<String, Pair<String, Int>>()
    private val folgen = HashMap<String, HashMap<String, Int>>()
    private val bestaetigt = HashSet<String>()
    private var ungespeichert = 0
    private var nutzerNeu: List<String>? = null

    init { lade() }

    /** Wo die Tasten gerade liegen (ändert sich bei Querformat oder eigenen Umlauttasten). */
    fun setzeKarte(neu: Tastenkarte) {
        fehler = Fehlermodell(neu, p)
        merkSchluessel = ""
    }

    // ================================================================ Abfragen

    /** Ist das Wort bekannt, also in der Wortliste oder vom Nutzer mindestens zweimal geschrieben? */
    fun kennt(wort: String): Boolean {
        val klein = wort.lowercase()
        return modell.index(klein) >= 0 || (gelernt[klein]?.second ?: 0) >= 2
    }

    private fun kontextNr(k: Kontext): Int = when {
        k.satzanfang -> Sprachmodell.SATZANFANG_NR
        k.wort.isEmpty() -> -1
        else -> modell.index(k.wort.lowercase())
    }

    private fun nutzerBonus(klein: String, vorherKlein: String): Double {
        var b = 0.0
        val g = gelernt[klein]?.second ?: 0
        if (g > 0) b += min(p.nutzerWortMax, p.nutzerWort + p.nutzerWortLog * ln(g.toDouble()))
        val f = folgen[vorherKlein]?.get(klein)
        if (f != null) b += min(4.0, p.nutzerFolge + 0.7 * ln(f.toDouble()))
        return b
    }

    /** ln Wahrscheinlichkeit eines Wortes aus der Liste in diesem Zusammenhang. */
    private fun bewerte(idx: Int, ctxNr: Int, vorherKlein: String): Double {
        val uni = modell.lnP(idx)
        var s = uni
        val f = modell.folgeLnP(ctxNr, idx)
        if (f != null) s += min(p.folgeMax, p.folgeGewicht * max(0.0, f - uni))
        return s + nutzerBonus(modell.schluessel[idx], vorherKlein)
    }

    private fun bewerteNutzerWort(klein: String, vorherKlein: String): Double =
        p.nutzerUnbekanntLnP + nutzerBonus(klein, vorherKlein)

    private fun nutzerNeueWoerter(): List<String> =
        nutzerNeu ?: gelernt.keys.filter { modell.index(it) < 0 }.also { nutzerNeu = it }

    // ================================================================ Korrektur

    private class Kandidat(val text: String, val bewertung: Double, val kosten: Float)

    private class Entscheidung(val korrektur: String?, val alternativen: List<Kandidat>, val vorsprung: Double)

    private var merkSchluessel = ""
    private var merkEntscheidung = Entscheidung(null, emptyList(), 0.0)

    private fun spurCode(spur: List<Anschlag?>?): Int {
        var h = 17
        spur?.forEach { a -> h = h * 31 + (a?.let { (it.x * 40).toInt() * 997 + (it.y * 40).toInt() } ?: 0) }
        return h
    }

    private fun darfKorrigiert(wort: String): Boolean {
        if (wort.length < p.minLaenge || wort.length > FuzzySuche.MAX_LAENGE) return false
        if (!wort.all { it.isLetter() }) return false
        if (wort.length >= 2 && wort.all { it.isUpperCase() }) return false
        // Binnenmajuskeln (McDonald, iPhone) lassen; "IMmer" (Umschalttaste zu lange) ist erlaubt
        if (!zweiGross(wort) && wort.drop(1).any { it.isUpperCase() }) return false
        return true
    }

    private fun zweiGross(wort: String) =
        wort.length >= 3 && wort[0].isUpperCase() && wort[1].isUpperCase() && wort.drop(2).none { it.isUpperCase() }

    private fun grossErstes(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    /** Das Wort ist bekannt -- höchstens die Groß-/Kleinschreibung wird angepasst. */
    private fun schreibweiseKorrigieren(wort: String, klein: String, idx: Int): String? {
        if (zweiGross(wort)) {
            // "IMmer": Umschalttaste zu lange gehalten
            return if (idx >= 0 && modell.istSubstantiv(idx)) modell.schreibweise(idx) else grossErstes(klein)
        }
        if (!substantiveGross || wort != klein || wort in bestaetigt) return null
        return if (idx >= 0 && modell.grossAnteil(idx) >= p.substantivSchwelle) grossErstes(modell.schreibweise(idx)) else null
    }

    private fun entscheide(wort: String, k: Kontext, spur: List<Anschlag?>?): Entscheidung {
        val schluessel = "$wort|${k.wort}|${k.satzanfang}|${spurCode(spur)}|${gelernt.size}|${bestaetigt.size}"
        if (schluessel == merkSchluessel) return merkEntscheidung
        val ergebnis = rechne(wort, k, spur)
        merkSchluessel = schluessel
        merkEntscheidung = ergebnis
        return ergebnis
    }

    private fun rechne(wort: String, k: Kontext, spur: List<Anschlag?>?): Entscheidung {
        val leer = Entscheidung(null, emptyList(), 0.0)
        if (wort.isEmpty() || !darfKorrigiert(wort)) return leer
        val klein = wort.lowercase()
        val idx = modell.index(klein)
        val bekannt = idx >= 0 || (gelernt[klein]?.second ?: 0) >= 2
        if (bekannt) return Entscheidung(schreibweiseKorrigieren(wort, klein, idx), emptyList(), 0.0)
        if (wort in bestaetigt) return leer

        val vorherKlein = k.wort.lowercase()
        val ctx = kontextNr(k)
        val typ = klein.toCharArray()
        val ersetz = fehler.ersetzTabelle(typ, spur)
        val ins = fehler.einfuegenKosten(typ)

        val kandidaten = ArrayList<Kandidat>()
        for (t in suche.suche(typ, ersetz, ins, p.suchGrenze)) {
            kandidaten += Kandidat(angleichen(modell.schreibweise(t.index), wort), bewerte(t.index, ctx, vorherKlein) - t.kosten, t.kosten)
        }
        for (w in nutzerNeueWoerter()) {
            val kosten = suche.kosten(typ, ersetz, ins, w)
            if (kosten <= p.suchGrenze) {
                kandidaten += Kandidat(angleichen(gelernt.getValue(w).first, wort), bewerteNutzerWort(w, vorherKlein) - kosten, kosten)
            }
        }
        trennen(klein, wort, ctx, vorherKlein)?.let { kandidaten += it }
        if (kandidaten.isEmpty()) return leer

        kandidaten.sortByDescending { it.bewertung }
        val behalten = behalten(wort, klein, k)
        val bester = kandidaten[0]
        val vorsprung = bester.bewertung - behalten
        return Entscheidung(if (vorsprung > p.rand) bester.text else null, kandidaten, vorsprung)
    }

    /** Das Ergebnis übernimmt die Großschreibung des Getippten; ein Substantiv bleibt groß. */
    private fun angleichen(vorschlag: String, getippt: String): String =
        if (getippt[0].isUpperCase()) grossErstes(vorschlag)
        else if (!substantiveGross && vorschlag[0].isUpperCase()) vorschlag.replaceFirstChar { it.lowercaseChar() }
        else vorschlag

    /**
     * Wie wahrscheinlich ist es, dass das Getippte ein echtes Wort ist, das nur nicht in der Liste steht?
     * Ein typisches unbekanntes Wort hat [Parameter.unbekanntLnP], egal wie lang es ist. Darauf kommt, was
     * auf einen Namen, eine Zusammensetzung oder eine gebeugte Form hindeutet.
     */
    private fun behalten(wort: String, klein: String, k: Kontext): Double {
        var s = p.unbekanntLnP
        if (wort[0].isUpperCase() && !k.satzanfang) s += p.namenBonus
        if (istZusammensetzung(klein, 0)) s += p.kompositumBonus
        if (istBeugung(klein)) s += p.beugungsBonus
        return s
    }

    private val endungen = setOf("s", "es", "e", "en", "er", "em", "n", "ns", "ens", "ern", "t", "te", "ten", "tem", "ter", "tes", "st", "in", "innen", "ung", "heit", "keit")

    /**
     * Gebeugte Form eines bekannten Wortes: bekanntes Wort plus Endung ("Gegenstands"), oder ein
     * bekanntes Wort ist das Getippte plus Endung ("verkeilt" -> "verkeilte"). Das ist selten ein Tippfehler.
     */
    private fun istBeugung(klein: String): Boolean {
        if (klein.length < 5) return false
        for (e in endungen) {
            if (klein.length - e.length >= 4 && klein.endsWith(e) && modell.index(klein.dropLast(e.length)) >= 0) return true
        }
        var gesehen = 0
        for (i in modell.praefixBereich(klein)) {
            if (++gesehen > 60) break
            val rest = modell.schluessel[i].substring(klein.length)
            if (rest.isNotEmpty() && rest in endungen) return true
        }
        return false
    }

    /** "ichhabe" -> "ich habe": zwei häufige Wörter ohne Leerzeichen dazwischen. */
    private fun trennen(klein: String, wort: String, ctx: Int, vorherKlein: String): Kandidat? {
        if (klein.length < 4) return null
        var bester: Kandidat? = null
        for (s in 2..klein.length - 2) {
            val a = klein.substring(0, s)
            val b = klein.substring(s)
            val ia = modell.index(a)
            val ib = modell.index(b)
            if (ia < 0 || ib < 0) continue
            if (a.length == 2 && !modell.istFunktionswort(ia)) continue
            if (b.length < 3) continue   // sonst wird jedes Wort auf -in, -er, -es "getrennt"
            // zwei Substantive sind eine Zusammensetzung, kein Tippfehler
            if (modell.istSubstantiv(ia) && modell.istSubstantiv(ib)) continue
            val bewertung = bewerte(ia, ctx, vorherKlein) + bewerte(ib, ia, a) - p.kostenLeerzeichen
            if (bester == null || bewertung > bester.bewertung) {
                bester = Kandidat(angleichen(modell.schreibweise(ia), wort) + " " + modell.schreibweise(ib), bewertung, p.kostenLeerzeichen.toFloat())
            }
        }
        return bester
    }

    private val fugen = listOf("", "s", "es", "n", "en", "e", "er")

    /** Besteht das Wort aus bekannten Teilen ("Haustürschlüssel")? Dann ist es kein Tippfehler. */
    private fun istZusammensetzung(klein: String, tiefe: Int): Boolean {
        val n = klein.length
        if (n < 7 || tiefe > 1) return false
        for (s in 3..n - 3) {
            val rechts = klein.substring(s)
            val ir = modell.index(rechts)
            val rechtsOk = (ir >= 0 && rechts.length >= 3) || (tiefe == 0 && rechts.length >= 6 && istZusammensetzung(rechts, tiefe + 1))
            if (!rechtsOk) continue
            val links = klein.substring(0, s)
            for (fuge in fugen) {
                if (!links.endsWith(fuge)) continue
                val stamm = links.dropLast(fuge.length)
                if (stamm.length < 3) continue
                val il = modell.index(stamm)
                if (il >= 0 && !modell.istFunktionswort(il)) return true
            }
        }
        return false
    }

    /** Für die Abstimmung: die besten Kandidaten mit Bewertung und der Wert fürs Stehenlassen. */
    internal fun erklaere(wort: String, kontext: Kontext = Kontext.KEINER, spur: List<Anschlag?>? = null): String {
        val klein = wort.lowercase()
        if (modell.index(klein) >= 0) return "bekannt"
        val e = rechne(wort, kontext, spur)
        return "behalten=%.1f (zusammensetzung=%s, beugung=%s) | ".format(behalten(wort, klein, kontext), istZusammensetzung(klein, 0), istBeugung(klein)) +
            e.alternativen.take(3).joinToString { "%s %.1f (kosten %.1f)".format(it.text, it.bewertung, it.kosten) }
    }

    /** Was die Leertaste anstelle des getippten Wortes einsetzen würde -- oder null. */
    fun korrektur(wort: String, kontext: Kontext = Kontext.KEINER, spur: List<Anschlag?>? = null): String? =
        entscheide(wort, kontext, spur).korrektur

    // ================================================================ Vorschläge

    private fun naechsteWoerter(k: Kontext, anzahl: Int): List<String> {
        val ctx = kontextNr(k)
        val vorherKlein = k.wort.lowercase()
        val eigene = folgen[vorherKlein]
        val eigeneSumme = eigene?.values?.sum() ?: 0
        // Je öfter du nach diesem Wort schon etwas geschrieben hast, desto mehr zählt das Eigene:
        // nach 5 Malen etwa die Hälfte, nach 20 Malen fast alles.
        val eigenAnteil = eigeneSumme / (eigeneSumme + 5.0)
        val punkte = HashMap<String, Double>()   // klein -> Wahrscheinlichkeit (gemischt)
        val grossNach = HashSet<String>()        // Wörter, die an dieser Stelle meist großgeschrieben stehen
        modell.nachfolger(ctx)?.forEach { e ->
            val idx = Sprachmodell.folgeNummer(e)
            punkte[modell.schluessel[idx]] = (1 - eigenAnteil) * Math.pow(2.0, -Sprachmodell.folgeQ(e).toDouble())
            if (Sprachmodell.folgeGross(e)) grossNach += modell.schluessel[idx]
        }
        eigene?.forEach { (w, anzahlGeschrieben) ->
            val idx = modell.index(w)
            // Das Wort kann auch im Korpus stehen; sonst kleiner Grundwert aus der Häufigkeit
            val korpus = punkte[w] ?: (1 - eigenAnteil) * 0.1 * (if (idx >= 0) Math.exp(modell.lnP(idx)) else 1e-7)
            punkte[w] = korpus + eigenAnteil * anzahlGeschrieben / eigeneSumme
        }
        // wer in beiden vorkommt, dessen Korpus-Anteil wurde oben schon gezählt (nicht doppelt)
        return punkte.entries.sortedByDescending { it.value }.take(anzahl).map { (w, _) ->
            val idx = modell.index(w)
            val text = if (idx >= 0) modell.schreibweise(idx) else gelernt[w]?.first ?: w
            if (k.satzanfang || w in grossNach) grossErstes(text) else text
        }
    }

    /** Die [anzahl] wahrscheinlichsten Wörter des Bereichs, die länger sind als [mindestLaenge] -- ohne alles zu sortieren. */
    private fun besteImBereich(bereich: IntRange, mindestLaenge: Int, ctx: Int, vorherKlein: String, anzahl: Int): List<Int> {
        val nummern = IntArray(anzahl) { -1 }
        val punkte = DoubleArray(anzahl) { Double.NEGATIVE_INFINITY }
        for (i in bereich) {
            if (modell.schluessel[i].length <= mindestLaenge) continue
            val s = bewerte(i, ctx, vorherKlein)
            if (s <= punkte[anzahl - 1]) continue
            var pos = anzahl - 1
            while (pos > 0 && punkte[pos - 1] < s) { punkte[pos] = punkte[pos - 1]; nummern[pos] = nummern[pos - 1]; pos-- }
            punkte[pos] = s
            nummern[pos] = i
        }
        return nummern.filter { it >= 0 }
    }

    /**
     * Bis zu [anzahl] Vorschläge für [praefix]; bei leerem Präfix die wahrscheinlichsten nächsten Wörter.
     * An erster Stelle steht das Getippte selbst, dahinter die Autokorrektur (falls es eine gibt).
     */
    fun vorschlaege(praefix: String, kontext: Kontext = Kontext.KEINER, spur: List<Anschlag?>? = null, anzahl: Int = 3): Vorschlaege {
        if (praefix.isEmpty()) return Vorschlaege(naechsteWoerter(kontext, anzahl), null)
        val klein = praefix.lowercase()
        val vorherKlein = kontext.wort.lowercase()
        val ctx = kontextNr(kontext)
        val entscheidung = entscheide(praefix, kontext, spur)

        val ergebnis = LinkedHashMap<String, String>()   // klein -> Anzeige
        fun nimm(text: String) { ergebnis.putIfAbsent(text.lowercase(), text) }
        nimm(praefix)
        entscheidung.korrektur?.let { nimm(it) }

        // Ergänzungen: Wörter, die mit dem Getippten beginnen
        val bereich = modell.praefixBereich(klein)
        for (i in besteImBereich(bereich, klein.length, ctx, vorherKlein, anzahl)) nimm(angleichen(modell.schreibweise(i), praefix))
        for (w in nutzerNeueWoerter()) if (w.startsWith(klein) && w.length > klein.length) nimm(angleichen(gelernt.getValue(w).first, praefix))

        // weitere Korrekturen, falls noch Platz ist
        for (c in entscheidung.alternativen) { if (ergebnis.size >= anzahl) break; nimm(c.text) }
        return Vorschlaege(ergebnis.values.take(anzahl), entscheidung.korrektur)
    }

    // ================================================================ Lernen

    /** Ein abgeschlossenes Wort merken -- und welches Wort davor stand. */
    fun lerne(wort: String, vorher: String = "", anzahl: Int = 1) {
        if (wort.length < 2 || wort.length > 40 || !wort.any { it.isLetter() }) return
        if (wort.any { it.isDigit() }) return // Nummern, Codes, Uhrzeiten nicht lernen
        val klein = wort.lowercase()
        // Mitten im Satz (es gibt ein Vorgängerwort) zählt die getippte Schreibweise.
        // Am Satzanfang ist ein Großbuchstabe Pflicht -- dort die bekannte Schreibweise behalten.
        val idx = modell.index(klein)
        val bekannt = gelernt[klein]?.first ?: if (idx >= 0) modell.schreibweise(idx) else null
        val schreibweise = if (vorher.isNotEmpty() || bekannt == null) wort else bekannt
        val neu = gelernt[klein] == null
        gelernt[klein] = schreibweise to ((gelernt[klein]?.second ?: 0) + anzahl)
        if (neu && idx < 0) nutzerNeu = null
        if (vorher.isNotEmpty()) {
            val liste = folgen.getOrPut(vorher.lowercase()) { HashMap() }
            liste[klein] = (liste[klein] ?: 0) + 1
        }
        if (++ungespeichert >= 20) speichere()
    }

    /** Der Nutzer will genau diese Schreibweise (⌫ nach einer Korrektur, Antippen des Getippten): nie wieder ändern. */
    fun bestaetige(wort: String) {
        bestaetigt += wort
        lerne(wort, "", 2)
        speichere()
    }

    fun vergiss(wort: String) {
        val klein = wort.lowercase()
        gelernt.remove(klein)
        folgen.remove(klein)
        folgen.values.forEach { it.remove(klein) }
        bestaetigt.removeAll { it.lowercase() == klein }
        nutzerNeu = null
        speichere()
    }

    fun vergissAlles() {
        gelernt.clear()
        folgen.clear()
        bestaetigt.clear()
        nutzerNeu = null
        speichere()
    }

    fun speichere() {
        val text = StringBuilder()
        // Bei sehr vielen Eintraegen nur die haeufigsten behalten.
        gelernt.entries.sortedByDescending { it.value.second }.take(MAX_WOERTER).forEach { (_, wert) ->
            text.append("w\t").append(wert.first).append('\t').append(wert.second).append('\n')
        }
        folgen.entries.take(MAX_WOERTER).forEach { (von, ziele) ->
            ziele.entries.sortedByDescending { it.value }.take(MAX_FOLGEN).forEach { (nach, anzahl) ->
                text.append("f\t").append(von).append('\t').append(nach).append('\t').append(anzahl).append('\n')
            }
        }
        bestaetigt.take(MAX_WOERTER).forEach { text.append("b\t").append(it).append('\n') }
        speicher.schreib(text.toString())
        ungespeichert = 0
    }

    private fun lade() {
        val inhalt = speicher.lies() ?: return
        for (zeile in inhalt.lineSequence()) {
            val teile = zeile.split('\t')
            when {
                teile.size == 3 && teile[0] == "w" ->
                    teile[2].toIntOrNull()?.let { gelernt[teile[1].lowercase()] = teile[1] to it }
                teile.size == 4 && teile[0] == "f" ->
                    teile[3].toIntOrNull()?.let { folgen.getOrPut(teile[1]) { HashMap() }[teile[2]] = it }
                teile.size == 2 && teile[0] == "b" -> bestaetigt += teile[1]
            }
        }
    }

    companion object {
        const val MAX_WOERTER = 5000
        const val MAX_FOLGEN = 8
    }
}
