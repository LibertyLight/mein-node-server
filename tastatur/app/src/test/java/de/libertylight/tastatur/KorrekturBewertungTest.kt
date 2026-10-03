package de.libertylight.tastatur

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Misst die Autokorrektur an Texten, die nie zum Bauen der Wortliste dienten, mit einem
 * simulierten Nutzer: Der "tippt" die Wörter mit Fingerrauschen auf der echten Tastaturgeometrie,
 * dazu Vertauschen, Auslassen, Verdoppeln und faules Tippen ohne Umlaute.
 *
 * Läuft nur mit TASTATUR_TESTDATEN=<Ordner mit web.txt, wiki.txt, tatoeba_test.txt>.
 * Ohne den Ordner wird der Test übersprungen.
 */
class KorrekturBewertungTest {

    private class Ablage : Woerterbuch.Speicher {
        override fun lies(): String? = null
        override fun schreib(inhalt: String) {}
    }

    // ---------------------------------------------------------------- Daten

    private data class Probe(
        val gemeint: String,          // wie im Text geschrieben (mit Großschreibung)
        val getippt: String,          // was auf der Tastatur herauskam
        val spur: List<Anschlag?>,
        val kontext: Kontext,
        val vorgaenger: String,
    )

    private val buchstaben = Regex("[A-Za-zÄÖÜäöüß]+")
    private val karte = Tastenkarte.STANDARD
    private val mitten = Raster.mitten(Belegung.reihen(Seite.BUCHSTABEN, Optionen()))

    private fun ordner(): File? = System.getenv("TASTATUR_TESTDATEN")?.let { File(it) }?.takeIf { it.isDirectory }

    private fun modell(): Sprachmodell = Sprachmodell(
        File("src/main/res/raw/woerter_de.txt").readLines(),
        File("src/main/res/raw/folgen_de.txt").readLines(),
    )

    private fun saetze(datei: File, anzahl: Int, rng: Random): List<List<String>> {
        val alle = datei.readLines().map { z ->
            val tab = z.indexOf('\t')
            if (tab in 1..12 && z.substring(0, tab).all { it.isDigit() }) z.substring(tab + 1) else z
        }
        val gemischt = alle.shuffled(kotlin.random.Random(rng.nextLong())).take(anzahl)
        return gemischt.map { s -> buchstaben.findAll(s).map { it.value }.toList() }.filter { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- Nutzer simulieren

    private class Fehlerraten(
        val sigma: Double = 0.22,         // Streuung des Fingers in Tastenbreiten
        val tausch: Double = 0.002,       // je Buchstabenpaar
        val auslassen: Double = 0.003,
        val doppelt: Double = 0.002,
        val einfuegen: Double = 0.001,
        val faulUmlaut: Double = 0.5,     // Anteil, bei dem u statt ü getippt wird
        val klein: Boolean = false,       // alles klein tippen (faul)
    )

    private fun taste(x: Double, y: Double): Char {
        var beste = '?'
        var bestD = Double.MAX_VALUE
        for ((c, m) in mitten) {
            if (c !in 'a'..'z') continue
            val dx = max(abs(x - m.first) - 0.46, 0.0)
            val dy = max(abs(y - m.second) - 0.76, 0.0)
            val d = dx * dx + dy * dy + 1e-6 * ((x - m.first) * (x - m.first) + (y - m.second) * (y - m.second))
            if (d < bestD) { bestD = d; beste = c }
        }
        return beste
    }

    private fun tippeBuchstabe(c: Char, rng: Random, sigma: Double): Pair<Char, Anschlag>? {
        val m = mitten[c] ?: return null
        val x = m.first + rng.nextGaussian() * sigma
        val y = m.second + rng.nextGaussian() * sigma
        val getroffen = taste(x, y)
        return getroffen to Anschlag(getroffen, x.toFloat(), y.toFloat())
    }

    private fun tippe(wort: String, kontext: Kontext, vorgaenger: String, rng: Random, r: Fehlerraten): Probe {
        val zeichen = StringBuilder()
        val spur = ArrayList<Anschlag?>()
        val wortKlein = wort.lowercase()
        var i = 0
        while (i < wortKlein.length) {
            val c = wortKlein[i]
            when {
                c in 'a'..'z' -> tippeBuchstabe(c, rng, r.sigma)?.let { (g, a) -> zeichen.append(g); spur += a }
                c == 'ß' -> if (rng.nextDouble() < r.faulUmlaut) {
                    repeat(2) { tippeBuchstabe('s', rng, r.sigma)?.let { (g, a) -> zeichen.append(g); spur += a } }
                } else { zeichen.append('ß'); spur += null }
                else -> { // ä ö ü
                    val grund = when (c) { 'ä' -> 'a'; 'ö' -> 'o'; else -> 'u' }
                    if (rng.nextDouble() < r.faulUmlaut) tippeBuchstabe(grund, rng, r.sigma)?.let { (g, a) -> zeichen.append(g); spur += a }
                    else { zeichen.append(c); spur += null }
                }
            }
            i++
        }
        // Vertauschen, Auslassen, Verdoppeln, Einfügen
        var k = 0
        while (k < zeichen.length) {
            val w = rng.nextDouble()
            when {
                w < r.tausch && k + 1 < zeichen.length -> {
                    val c = zeichen[k]; zeichen.setCharAt(k, zeichen[k + 1]); zeichen.setCharAt(k + 1, c)
                    val a = spur[k]; spur[k] = spur[k + 1]; spur[k + 1] = a
                    k++
                }
                w < r.tausch + r.auslassen && zeichen.length > 1 -> { zeichen.deleteCharAt(k); spur.removeAt(k); k-- }
                w < r.tausch + r.auslassen + r.doppelt -> { zeichen.insert(k, zeichen[k]); spur.add(k, spur[k]); k++ }
                w < r.tausch + r.auslassen + r.doppelt + r.einfuegen -> {
                    val nachbar = "qwertzuiopasdfghjklyxcvbnm"[rng.nextInt(26)]
                    val m = mitten[nachbar]!!
                    zeichen.insert(k, nachbar); spur.add(k, Anschlag(nachbar, m.first, m.second)); k++
                }
            }
            k++
        }
        var getippt = zeichen.toString()
        // Großschreibung: erster Buchstabe wie im Text (Umschalttaste), Rest klein; Abkürzungen ganz in Großbuchstaben
        // (Faul: alles klein -- nur am Satzanfang setzt die automatische Umschaltung einen Großbuchstaben.)
        val klein = r.klein && !kontext.satzanfang
        if (!klein && wort.length >= 2 && wort.all { it.isUpperCase() }) getippt = getippt.uppercase()
        else if (!klein && wort[0].isUpperCase() && getippt.isNotEmpty()) getippt = getippt.replaceFirstChar { it.uppercaseChar() }
        return Probe(wort, getippt, spur, kontext, vorgaenger)
    }

    private fun proben(saetze: List<List<String>>, anzahl: Int, rng: Random, r: Fehlerraten, minLaenge: Int = 3, maxLaenge: Int = 99): List<Probe> {
        val liste = ArrayList<Probe>()
        for (satz in saetze) {
            for ((pos, w) in satz.withIndex()) {
                if (w.length < minLaenge || w.length > maxLaenge) continue
                val kontext = if (pos == 0) Kontext.SATZANFANG else Kontext(satz[pos - 1].lowercase())
                liste += tippe(w, kontext, if (pos == 0) "" else satz[pos - 1], rng, r)
                if (liste.size >= anzahl) return liste
            }
        }
        return liste
    }

    // ---------------------------------------------------------------- Auswertung

    private class Ergebnis {
        var sauber = 0; var saubereVeraendert = 0; var saubereGrossklein = 0; var saubereFalsch = 0
        var saubereBekannt = 0; var saubereBekanntFalsch = 0; var saubereUnbekannt = 0; var saubereUnbekanntFalsch = 0
        var tippfehlerBekannt = 0; var behoben = 0; var verbessertFalsch = 0; var nichtBehoben = 0
        var tippfehlerUnbekannt = 0; var unbekanntVeraendert = 0
        var echtesWort = 0
        var zeit = 0L; var abfragen = 0
        val beispieleBekanntFalsch = ArrayList<String>(); val beispieleFalsch = ArrayList<String>(); val beispieleNichtBehoben = ArrayList<String>(); val beispieleVerbessertFalsch = ArrayList<String>()

        /**
         * Nutzen für den Alltag: ein richtig behobener Tippfehler zählt +1, eine Fehlkorrektur eines
         * richtig getippten Wortes -6 (Rückgängigmachen und Ärger), ein falsch ersetzter Tippfehler -1,5.
         * Tippfehler werden mit 0,6 gewichtet, weil die Simulation mehr davon erzeugt als reales Tippen.
         */
        fun nutzen(fp: Double = 6.0, falsch: Double = 1.5, tippGewicht: Double = 0.6) =
            tippGewicht * (behoben - falsch * verbessertFalsch) - fp * saubereFalsch

        fun zeile(name: String) = String.format(
            "%-14s Tippfehler (Wort bekannt): %5d | behoben %5.1f %% | falsch ersetzt %4.1f %% | nicht erkannt %5.1f %% || saubere Woerter: %6d | falsch geaendert: bekannte %5.2f %%, unbekannte %5.2f %% || Nutzen %6.0f | %.2f ms",
            name, tippfehlerBekannt, 100.0 * behoben / max(1, tippfehlerBekannt), 100.0 * verbessertFalsch / max(1, tippfehlerBekannt),
            100.0 * nichtBehoben / max(1, tippfehlerBekannt), sauber, 100.0 * saubereBekanntFalsch / max(1, saubereBekannt),
            100.0 * saubereUnbekanntFalsch / max(1, saubereUnbekannt), nutzen(), zeit / 1e6 / max(1, abfragen),
        )
    }

    private fun bewerte(wb: Woerterbuch, modell: Sprachmodell, proben: List<Probe>, mitSpur: Boolean = true): Ergebnis =
        bewerte(modell, proben, { p -> wb.korrektur(p.getippt, p.kontext, if (mitSpur) p.spur else null) }) { p -> wb.erklaere(p.getippt, p.kontext, p.spur) }

    /** Auswertung für ein beliebiges Korrekturverfahren. */
    private fun bewerte(modell: Sprachmodell, proben: List<Probe>, korrektur: (Probe) -> String?, erklaere: (Probe) -> String = { "" }): Ergebnis {
        val e = Ergebnis()
        for (p in proben) {
            val gemeintKlein = p.gemeint.lowercase()
            val getipptKlein = p.getippt.lowercase()
            val sauber = getipptKlein == gemeintKlein
            val gemeintBekannt = modell.index(gemeintKlein) >= 0
            val getipptBekannt = modell.index(getipptKlein) >= 0
            val t0 = System.nanoTime()
            val ergebnis = korrektur(p)
            e.zeit += System.nanoTime() - t0
            e.abfragen++
            if (sauber) {
                e.sauber++
                if (gemeintBekannt) e.saubereBekannt++ else e.saubereUnbekannt++
                if (ergebnis != null && gemeintBekannt && ergebnis != p.gemeint && e.beispieleBekanntFalsch.size < 60)
                    e.beispieleBekanntFalsch += "${p.gemeint} → $ergebnis  (nach '${p.vorgaenger}', typed '${p.getippt}')"
                if (ergebnis != null) {
                    e.saubereVeraendert++
                    if (ergebnis.lowercase() == gemeintKlein) {
                        // nur die Schreibweise: stimmt sie mit dem Text überein?
                        if (ergebnis == p.gemeint) e.saubereGrossklein++ else {
                            e.saubereFalsch++
                            if (gemeintBekannt) e.saubereBekanntFalsch++ else e.saubereUnbekanntFalsch++
                        }
                    } else {
                        e.saubereFalsch++
                        if (gemeintBekannt) e.saubereBekanntFalsch++ else e.saubereUnbekanntFalsch++
                        if (e.beispieleFalsch.size < 40) e.beispieleFalsch += "${p.gemeint} → $ergebnis  (nach '${p.vorgaenger}')  [${if (gemeintBekannt) "bekannt" else "unbekannt"}]  ${erklaere(p)}"
                    }
                }
            } else if (getipptBekannt) {
                e.echtesWort++
            } else if (gemeintBekannt) {
                e.tippfehlerBekannt++
                when {
                    ergebnis == null -> { e.nichtBehoben++; if (e.beispieleNichtBehoben.size < 40) e.beispieleNichtBehoben += "${p.gemeint} ← ${p.getippt}" }
                    ergebnis.lowercase() == gemeintKlein -> e.behoben++
                    else -> { e.verbessertFalsch++; if (e.beispieleVerbessertFalsch.size < 40) e.beispieleVerbessertFalsch += "${p.gemeint} ← ${p.getippt} → $ergebnis  (nach '${p.vorgaenger}')" }
                }
            } else {
                e.tippfehlerUnbekannt++
                if (ergebnis != null) e.unbekanntVeraendert++
            }
        }
        return e
    }

    private fun wb(modell: Sprachmodell, p: Parameter) = Woerterbuch(modell, Ablage(), karte, p)

    // ---------------------------------------------------------------- Tests

    private class Regler(
        val name: String, val min: Double, val max: Double, val schritt: Double,
        val lesen: (Parameter) -> Double, val setzen: (Parameter, Double) -> Parameter,
    )

    private val regler = listOf(
        Regler("sigma", 0.15, 0.45, 0.03, { it.sigma.toDouble() }, { p, v -> p.copy(sigma = v.toFloat()) }),
        Regler("sigmaOhneBeruehrung", 0.2, 0.6, 0.04, { it.sigmaOhneBeruehrung.toDouble() }, { p, v -> p.copy(sigmaOhneBeruehrung = v.toFloat()) }),
        Regler("kostenAuslassen", 3.0, 10.0, 0.7, { it.kostenAuslassen.toDouble() }, { p, v -> p.copy(kostenAuslassen = v.toFloat()) }),
        Regler("kostenDoppeltAuslassen", 1.5, 7.0, 0.7, { it.kostenDoppeltAuslassen.toDouble() }, { p, v -> p.copy(kostenDoppeltAuslassen = v.toFloat()) }),
        Regler("kostenEinfuegen", 3.0, 10.0, 0.7, { it.kostenEinfuegen.toDouble() }, { p, v -> p.copy(kostenEinfuegen = v.toFloat()) }),
        Regler("kostenDoppeltEinfuegen", 1.5, 7.0, 0.7, { it.kostenDoppeltEinfuegen.toDouble() }, { p, v -> p.copy(kostenDoppeltEinfuegen = v.toFloat()) }),
        Regler("kostenTausch", 2.0, 9.0, 0.7, { it.kostenTausch.toDouble() }, { p, v -> p.copy(kostenTausch = v.toFloat()) }),
        Regler("kostenErsetzen", 0.0, 5.0, 0.5, { it.kostenErsetzen.toDouble() }, { p, v -> p.copy(kostenErsetzen = v.toFloat()) }),
        Regler("kostenUmlaut", 0.0, 4.0, 0.4, { it.kostenUmlaut.toDouble() }, { p, v -> p.copy(kostenUmlaut = v.toFloat()) }),
        Regler("kostenDigraph", 0.0, 4.0, 0.4, { it.kostenDigraph.toDouble() }, { p, v -> p.copy(kostenDigraph = v.toFloat()) }),
        Regler("ersterBuchstabe", 0.0, 4.0, 0.5, { it.ersterBuchstabe.toDouble() }, { p, v -> p.copy(ersterBuchstabe = v.toFloat()) }),
        Regler("unbekanntLnP", -28.0, -12.0, 1.0, { it.unbekanntLnP }, { p, v -> p.copy(unbekanntLnP = v) }),
        Regler("kompositumBonus", 0.0, 9.0, 0.8, { it.kompositumBonus }, { p, v -> p.copy(kompositumBonus = v) }),
        Regler("beugungsBonus", 0.0, 9.0, 0.8, { it.beugungsBonus }, { p, v -> p.copy(beugungsBonus = v) }),
        Regler("namenBonus", 0.0, 9.0, 0.8, { it.namenBonus }, { p, v -> p.copy(namenBonus = v) }),
        Regler("rand", 0.0, 4.0, 0.4, { it.rand }, { p, v -> p.copy(rand = v) }),
        Regler("folgeGewicht", 0.0, 1.5, 0.15, { it.folgeGewicht }, { p, v -> p.copy(folgeGewicht = v) }),
        Regler("folgeMax", 1.0, 9.0, 0.8, { it.folgeMax }, { p, v -> p.copy(folgeMax = v) }),
        Regler("kostenLeerzeichen", 2.0, 10.0, 0.8, { it.kostenLeerzeichen }, { p, v -> p.copy(kostenLeerzeichen = v) }),
        Regler("suchGrenze", 6.0, 11.0, 0.5, { it.suchGrenze.toDouble() }, { p, v -> p.copy(suchGrenze = v.toFloat()) }),
    )

    private fun probenFuer(dir: File, seedSaetze: Long, seedTippen: Long, jeKorpus: Int, raten: Fehlerraten = Fehlerraten()): List<Pair<String, List<Probe>>> =
        listOf("web.txt", "wiki.txt", "tatoeba_test.txt").map { name ->
            val s = saetze(File(dir, name), 4000, Random(seedSaetze))
            name.removeSuffix(".txt") to proben(s, jeKorpus, Random(seedTippen), raten)
        }

    @Test fun stimmeAutokorrekturAb() {
        val dir = ordner(); assumeTrue("TASTATUR_TESTDATEN nicht gesetzt", dir != null)
        assumeTrue("TASTATUR_ABSTIMMEN nicht gesetzt", System.getenv("TASTATUR_ABSTIMMEN") != null)
        val modell = modell()
        val tuning = probenFuer(dir!!, 81, 82, 6000)
        val pruefung = probenFuer(dir, 91, 92, 6000)

        fun nutzen(p: Parameter, satz: List<Pair<String, List<Probe>>>): Double {
            val w = wb(modell, p)
            return satz.sumOf { (_, proben) -> bewerte(w, modell, proben).nutzen() }
        }
        fun bericht(titel: String, p: Parameter, satz: List<Pair<String, List<Probe>>>) {
            val w = wb(modell, p)
            for ((name, proben) in satz) println("ABS   $titel " + bewerte(w, modell, proben).zeile(name))
        }

        var best = Parameter()
        var bestU = nutzen(best, tuning)
        println("ABS Start: Nutzen $bestU, Pruefung ${nutzen(best, pruefung)}")
        bericht("start", best, pruefung)
        var skala = 1.0
        for (durchgang in 1..3) {
            var verbessert = false
            for (r in regler) {
                for (richtung in listOf(1.0, -1.0)) {
                    var weiter = true
                    while (weiter) {
                        weiter = false
                        val v = (r.lesen(best) + richtung * r.schritt * skala).coerceIn(r.min, r.max)
                        if (abs(v - r.lesen(best)) < 1e-9) break
                        val kandidat = r.setzen(best, v)
                        val u = nutzen(kandidat, tuning)
                        if (u > bestU + 0.0015 * abs(bestU) + 2.0) {
                            best = kandidat; bestU = u; verbessert = true; weiter = true
                            println("ABS Durchgang $durchgang: ${r.name} = ${"%.3f".format(v)}  -> Nutzen ${"%.0f".format(u)}")
                        }
                    }
                }
            }
            if (!verbessert && durchgang > 1) break
            skala *= 0.6
        }
        println("ABS Ergebnis: $best")
        println("ABS Nutzen Tuning $bestU, Pruefung ${nutzen(best, pruefung)}")
        bericht("ende", best, pruefung)
    }

    /**
     * Vorschlagsleiste: Wie oft steht das nächste Wort unter den drei Vorschlägen (ohne etwas zu tippen)?
     * Und nach wie vielen richtig getippten Buchstaben steht das ganze Wort unter den drei Vorschlägen?
     */
    @Test fun messeVorschlaege() {
        val dir = ordner(); assumeTrue("TASTATUR_TESTDATEN nicht gesetzt", dir != null)
        val modell = modell()
        val wb = wb(modell, Parameter())
        for (name in listOf("web.txt", "wiki.txt", "tatoeba_test.txt")) {
            val s = saetze(File(dir, name), 2500, Random(41))
            var naechste = 0; var treffer1 = 0; var treffer3 = 0
            var worte = 0; var gespart = 0.0; var nach2 = 0; var nach3 = 0; var nie = 0
            for (satz in s) {
                for ((pos, w) in satz.withIndex()) {
                    val kontext = if (pos == 0) Kontext.SATZANFANG else Kontext(satz[pos - 1].lowercase())
                    val klein = w.lowercase()
                    if (modell.index(klein) >= 0) {
                        naechste++
                        val v = wb.vorschlaege("", kontext).liste.map { it.lowercase() }
                        if (v.firstOrNull() == klein) treffer1++
                        if (klein in v) treffer3++
                    }
                    if (w.length >= 4 && modell.index(klein) >= 0) {
                        worte++
                        var k = 1
                        var gefunden = -1
                        while (k < w.length) {
                            val v = wb.vorschlaege(w.substring(0, k), kontext).liste.map { it.lowercase() }
                            if (klein in v.drop(1) || (k == w.length - 1 && klein in v)) { gefunden = k; break }
                            k++
                        }
                        if (gefunden < 0) nie++ else { gespart += 1.0 - gefunden.toDouble() / w.length; if (gefunden <= 2) nach2++; if (gefunden <= 3) nach3++ }
                    }
                }
            }
            println(String.format("VOR %-16s naechstes Wort: erster Vorschlag %4.1f %%, unter den drei %4.1f %% (%d Woerter) || Ergaenzung: nach 2 Buchstaben %4.1f %%, nach 3 %4.1f %%, im Mittel %4.1f %% der Buchstaben gespart, nie %4.1f %% (%d Woerter)",
                name.removeSuffix(".txt"), 100.0 * treffer1 / naechste, 100.0 * treffer3 / naechste, naechste,
                100.0 * nach2 / worte, 100.0 * nach3 / worte, 100.0 * gespart / worte, 100.0 * nie / worte, worte))
        }
    }

    /** Ausschlussversuch: Was bringt jede Komponente? Jeweils eine abschalten, auf frischen Sätzen messen. */
    @Test fun ausschlussversuch() {
        val dir = ordner(); assumeTrue("TASTATUR_TESTDATEN nicht gesetzt", dir != null)
        val modell = modell()
        val satz = probenFuer(dir!!, 51, 52, 8000)
        val basis = Parameter()
        val varianten = listOf(
            "so wie ausgeliefert" to (basis to true),
            "ohne Beruehrungsstellen" to (basis to false),
            "ohne Beugungserkennung" to (basis.copy(beugungsBonus = 0.0) to true),
            "ohne Zusammensetzungen" to (basis.copy(kompositumBonus = 0.0) to true),
            "ohne Namens-Bonus" to (basis.copy(namenBonus = 0.0) to true),
            "ohne Folgewoerter" to (basis.copy(folgeGewicht = 0.0) to true),
            "ohne Umlaut-Nachsicht" to (basis.copy(kostenUmlaut = 4f, kostenDigraph = 4f) to true),
            "Rand 0 (sofort korrigieren)" to (basis.copy(rand = 0.0) to true),
            "Rand 2 (vorsichtiger)" to (basis.copy(rand = 2.0) to true),
            "Rand 4 (sehr vorsichtig)" to (basis.copy(rand = 4.0) to true),
        )
        for ((name, v) in varianten) {
            val (p, mitSpur) = v
            val w = wb(modell, p)
            var summe = 0.0
            val teile = satz.map { (korpus, proben) -> bewerte(w, modell, proben, mitSpur).also { summe += it.nutzen() } }
            val behoben = teile.sumOf { it.behoben }; val t = teile.sumOf { it.tippfehlerBekannt }
            val falsch = teile.sumOf { it.verbessertFalsch }
            val fp = teile.sumOf { it.saubereFalsch }; val sauber = teile.sumOf { it.sauber }
            println(String.format("AUS %-28s Nutzen %6.0f | Tippfehler behoben %5.1f %%, falsch ersetzt %4.1f %% | saubere Woerter falsch geaendert %5.2f %%", name, summe, 100.0 * behoben / t, 100.0 * falsch / t, 100.0 * fp / sauber))
        }
    }

    /**
     * Faules Tippen: alles klein. Wie viele Substantive und Namen holt die Autokorrektur
     * (Großschreibung) zurück, und wie viele kleingeschriebene Wörter (Verben, Adjektive) werden zu Unrecht groß?
     */
    @Test fun messeGrossschreibung() {
        val dir = ordner(); assumeTrue("TASTATUR_TESTDATEN nicht gesetzt", dir != null)
        val modell = modell()
        val glatt = Fehlerraten(sigma = 0.0, tausch = 0.0, auslassen = 0.0, doppelt = 0.0, einfuegen = 0.0, faulUmlaut = 0.0, klein = true)
        val satz = listOf("web.txt", "wiki.txt", "tatoeba_test.txt").map { name ->
            proben(saetze(File(dir, name), 3000, Random(61)), 14000, Random(62), glatt)
        }
        for (schwelle in listOf(80.0, 90.0, 95.0, 97.0, 98.0, 99.0, 99.5)) {
            val w = wb(modell, Parameter(substantivSchwelle = schwelle))
            val zeile = StringBuilder(String.format("GRO Schwelle %5.1f |", schwelle))
            for ((i, proben) in satz.withIndex()) {
                var gross = 0; var richtig = 0; var klein = 0; var zuUnrecht = 0
                for (p in proben) {
                    if (p.kontext.satzanfang) continue
                    val ergebnis = w.korrektur(p.getippt, p.kontext, p.spur)
                    if (p.gemeint[0].isUpperCase()) {
                        gross++; if (ergebnis == p.gemeint) richtig++
                    } else {
                        klein++; if (ergebnis != null && ergebnis[0].isUpperCase()) zuUnrecht++
                    }
                }
                zeile.append(String.format(" %-6s Grossgeschriebene geholt %5.1f %% | Kleine zu Unrecht gross %5.2f %% |", listOf("web", "wiki", "tatoeba")[i], 100.0 * richtig / gross, 100.0 * zuUnrecht / klein))
            }
            println(zeile)
        }
    }

    /** Zweibuchstabige Wörter: lohnt es sich, auch sie zu korrigieren ("ud" -> "und")? */
    @Test fun messeKurzeWoerter() {
        val dir = ordner(); assumeTrue("TASTATUR_TESTDATEN nicht gesetzt", dir != null)
        val modell = modell()
        for (name in listOf("web.txt", "wiki.txt", "tatoeba_test.txt")) {
            val proben = proben(saetze(File(dir, name), 5000, Random(71)), 12000, Random(72), Fehlerraten(), 2, 2)
            for (min in listOf(3, 2)) {
                val e = bewerte(wb(modell, Parameter(minLaenge = min)), modell, proben)
                println("KUR " + name.removeSuffix(".txt") + " minLaenge=$min " + e.zeile("2 Buchstaben"))
            }
        }
    }

    @Test fun messeAutokorrektur() {
        val dir = ordner(); assumeTrue("TASTATUR_TESTDATEN nicht gesetzt", dir != null)
        val modell = modell()
        val rng = Random(1)
        val korpora = listOf("web.txt" to 3000, "wiki.txt" to 3000, "tatoeba_test.txt" to 4000)
        println("BEW Parameter: ${Parameter()}")
        for ((name, n) in korpora) {
            val datei = File(dir, name)
            val s = saetze(datei, 6000, Random(7))
            val proben = proben(s, n * 8, Random(11), Fehlerraten())
            val e = bewerte(wb(modell, Parameter()), modell, proben)
            println("BEW " + e.zeile(name.removeSuffix(".txt")))
            if (System.getenv("TASTATUR_BEISPIELE") != null) {
                e.beispieleBekanntFalsch.take(25).forEach { println("BEW    BEKANNT falsch:   $it") }
                e.beispieleFalsch.take(15).forEach { println("BEW    falsch geaendert: $it") }
                e.beispieleVerbessertFalsch.take(15).forEach { println("BEW    falsch ersetzt:   $it") }
                e.beispieleNichtBehoben.take(15).forEach { println("BEW    nicht behoben:    $it") }
            }
        }
    }
}
