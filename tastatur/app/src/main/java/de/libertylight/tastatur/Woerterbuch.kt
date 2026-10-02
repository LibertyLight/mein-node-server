package de.libertylight.tastatur

import kotlin.math.ln

/**
 * Wortvorschlaege und Autokorrektur: ein Grundwortschatz (nach Haeufigkeit sortiert)
 * plus alles, was beim Tippen gelernt wird.
 *
 * - Vervollstaendigung: Woerter, die mit dem Getippten beginnen
 * - Korrektur: das wahrscheinlichste Wort mit kleinem Tippfehler-Abstand
 * - Folgewort: was du nach dem vorherigen Wort oft schreibst
 *
 * Gespeichert wird nur auf dem Geraet, als einfache Textzeilen ueber [speicher].
 */
class Woerterbuch(
    grundwortschatz: List<String>,
    private val speicher: Speicher,
) {
    interface Speicher {
        fun lies(): String?
        fun schreib(inhalt: String)
    }

    /** Rang im Grundwortschatz (0 = haeufigstes Wort), Schluessel klein geschrieben. */
    private val rang = HashMap<String, Int>(grundwortschatz.size * 2)
    /** Nur Woerter, deren Schreibweise vom Schluessel abweicht (Nomen) -- spart Speicher. */
    private val grossform = HashMap<String, String>()
    /** Alphabetisch sortiert, fuer schnelle Praefixsuche. */
    private val sortiert: Array<String>
    /** Schluessel nach Laenge, fuer die Korrektursuche. */
    private val nachLaenge = Array(MAX_LAENGE + 1) { ArrayList<String>() }
    /** Haeufigkeitspunkte je Rang, vorberechnet (0 = selten, 10 = sehr haeufig). */
    private val basis: FloatArray

    private val gelernt = HashMap<String, Pair<String, Int>>()
    private val folgen = HashMap<String, HashMap<String, Int>>()
    private var ungespeichert = 0

    init {
        for (eintrag in grundwortschatz) {
            val w = eintrag.trim()
            if (w.isEmpty() || w.length > MAX_LAENGE) continue
            val k = w.lowercase()
            if (k in rang) continue
            rang[k] = rang.size
            if (w != k) grossform[k] = w
            nachLaenge[k.length].add(k)
        }
        sortiert = rang.keys.toTypedArray().also { it.sort() }
        val logGroesse = ln(rang.size + 2.0)
        basis = FloatArray(rang.size) { (10.0 * (1.0 - ln(it + 1.0) / logGroesse)).toFloat() }
        lade()
    }

    /** 0 (selten/unbekannt) bis 10 (sehr haeufig), dazu Bonus fuers Gelernte. */
    private fun punkte(k: String): Double {
        val r = rang[k]
        val grund = if (r == null) 0.0 else basis[r].toDouble()
        val g = gelernt[k]?.second ?: 0
        return grund + if (g > 0) 4.0 + ln(g.toDouble()) * 2 else 0.0
    }

    private fun schreibweise(k: String): String = gelernt[k]?.first ?: grossform[k] ?: k

    fun kennt(wort: String): Boolean = wort.lowercase().let { it in rang || it in gelernt }

    /** Alle Schluessel mit diesem Praefix: Grundwortschatz per Binaersuche, dazu das Gelernte. */
    private fun mitPraefix(klein: String): Sequence<String> {
        var lo = 0
        var hi = sortiert.size
        while (lo < hi) {
            val mitte = (lo + hi) ushr 1
            if (sortiert[mitte] < klein) lo = mitte + 1 else hi = mitte
        }
        val grund = generateSequence(lo) { it + 1 }
            .takeWhile { it < sortiert.size && sortiert[it].startsWith(klein) }
            .map { sortiert[it] }
        return grund + gelernt.keys.asSequence().filter { it.startsWith(klein) && it !in rang }
    }

    // Die Leiste fragt bei jedem Tastendruck, die Leertaste gleich danach noch einmal:
    // das letzte Ergebnis merken. Jede Aenderung am Gelernten macht es ungueltig.
    private var merkSchluessel = ""
    private var merkKandidaten: List<Pair<String, Float>> = emptyList()

    /** Korrekturkandidaten mit ihrem Tippfehler-Abstand. */
    private fun kandidaten(klein: String, grenze: Float): List<Pair<String, Float>> {
        val schluessel = "$klein|$grenze"
        if (schluessel == merkSchluessel) return merkKandidaten
        return sucheKandidaten(klein, grenze).also { merkSchluessel = schluessel; merkKandidaten = it }
    }

    private fun sucheKandidaten(klein: String, grenze: Float): List<Pair<String, Float>> {
        val ergebnis = ArrayList<Pair<String, Float>>()
        // Doppelbuchstaben kosten nur 0,5 -- die Laenge darf also um bis zu 2 x Grenze abweichen
        val spanne = (grenze * 2).toInt()
        val von = (klein.length - spanne).coerceAtLeast(1)
        val bis = (klein.length + spanne).coerceAtMost(MAX_LAENGE)
        // Der erste Buchstabe ist fast immer richtig getroffen. Erlaubt sind nur: derselbe,
        // sein Umlaut, oder vertauscht/vergessen (dann passt der zweite Buchstabe).
        val erster = klein[0]
        val zweiter = klein.getOrElse(1) { erster }
        val umlaut = UMLAUTE[erster] ?: erster
        for (laenge in von..bis) {
            for (k in nachLaenge[laenge]) {
                val k0 = k[0]
                if (k0 != erster && k0 != umlaut && k0 != zweiter && (k.length < 2 || k[1] != erster)) continue
                val d = TextLogik.tippAbstand(klein, k, grenze)
                if (d <= grenze) ergebnis += k to d
            }
        }
        for (k in gelernt.keys) {
            if (k in rang) continue
            val d = TextLogik.tippAbstand(klein, k, grenze)
            if (d <= grenze) ergebnis += k to d
        }
        return ergebnis
    }

    private fun korrekturPunkte(k: String, abstand: Float, getippt: String, folge: Map<String, Int>): Double {
        var p = punkte(k) - abstand * 10.0 + (folge[k] ?: 0) * 3.0
        // Der erste Buchstabe ist selten falsch
        if (k.firstOrNull() == getippt.firstOrNull()) p += 1.5
        return p
    }

    /**
     * Die Korrektur, die beim Leerzeichen eingesetzt wird -- oder null, wenn das Wort
     * bekannt ist oder es keinen ueberzeugenden Kandidaten gibt.
     */
    fun korrektur(wort: String, vorher: String = ""): String? {
        if (!sollKorrigieren(wort)) return null
        val klein = wort.lowercase()
        val folge = folgen[vorher.lowercase()] ?: emptyMap()
        val (k, _) = kandidaten(klein, grenzeFuer(wort, vorher))
            // Seltene Woerter nur bei sehr kleinem Fehler einsetzen
            .filter { (k, d) -> punkte(k) >= 2.0 || d <= 0.7f }
            // "Dorfs", "gesehener": bekanntes Wort plus Endung ist meist eine Beugung, kein Fehler
            .filter { (k, _) -> !(klein.startsWith(k) && klein.length - k.length <= 3) }
            .maxByOrNull { (k, d) -> korrekturPunkte(k, d, klein, folge) } ?: return null
        return TextLogik.passeSchreibungAn(schreibweise(k), wort)
    }

    private fun grenzeFuer(wort: String, vorher: String): Float {
        val grenze = TextLogik.fehlerGrenze(wort.length)
        // Grossgeschrieben mitten im Satz ist oft ein Name: nur kleine Fehler korrigieren
        return if (wort[0].isUpperCase() && vorher.isNotEmpty()) minOf(grenze, 1f) else grenze
    }

    private fun sollKorrigieren(wort: String): Boolean {
        if (wort.length < 3 || wort.length > MAX_LAENGE) return false
        // Einmal getippt reicht nicht: sonst waere jeder unkorrigierte Tippfehler "bekannt".
        // Ab zweimal (oder bewusst bestaetigt) gilt ein eigenes Wort als richtig.
        val klein = wort.lowercase()
        if (klein in rang || (gelernt[klein]?.second ?: 0) >= 2) return false
        if (wort.any { it.isDigit() }) return false
        // GROSS geschriebene Abkuerzungen und BinnenMajuskeln (Namen, Marken) in Ruhe lassen
        if (wort.drop(1).any { it.isUpperCase() }) return false
        return true
    }

    /**
     * Bis zu [anzahl] Vorschlaege. Bei leerem [praefix] kommen Folgewoerter zu [vorher].
     * Das Getippte steht vorn; eine Autokorrektur direkt dahinter.
     */
    fun vorschlaege(praefix: String, vorher: String = "", anzahl: Int = 3): List<String> {
        if (praefix.isEmpty()) {
            val nachfolger = folgen[vorher.lowercase()] ?: return emptyList()
            return nachfolger.entries.sortedByDescending { it.value }.take(anzahl).map { schreibweise(it.key) }
        }

        val klein = praefix.lowercase()
        val ergebnis = LinkedHashSet<String>()
        ergebnis += praefix
        korrektur(praefix, vorher)?.let { ergebnis += it }

        val folge = folgen[vorher.lowercase()] ?: emptyMap()
        // Bei kurzen Praefixen sind es tausende Treffer -- die besten per Teilsortierung
        mitPraefix(klein)
            .filter { it.length > klein.length }
            .sortedByDescending { punkte(it) + (folge[it] ?: 0) * 3.0 }
            .take(anzahl)
            .forEach { ergebnis += TextLogik.passeSchreibungAn(schreibweise(it), praefix) }

        if (ergebnis.size < anzahl && klein.length >= 3) {
            kandidaten(klein, grenzeFuer(praefix, vorher))
                .filter { it.first != klein }
                .sortedByDescending { (k, d) -> korrekturPunkte(k, d, klein, folge) }
                .take(anzahl)
                .forEach { ergebnis += TextLogik.passeSchreibungAn(schreibweise(it.first), praefix) }
        }
        return ergebnis.distinctBy { it.lowercase() }.take(anzahl)
    }

    /** Ein abgeschlossenes Wort merken -- und welches Wort davor stand. */
    fun lerne(wort: String, vorher: String = "", anzahl: Int = 1) {
        if (wort.length < 2 || wort.length > MAX_LAENGE || !wort.any { it.isLetter() }) return
        if (wort.any { it.isDigit() }) return // Nummern, Codes, Uhrzeiten nicht lernen
        val schluessel = wort.lowercase()
        // Mitten im Satz (es gibt ein Vorgaengerwort) zaehlt die getippte Schreibweise.
        // Am Satzanfang ist ein Grossbuchstabe Pflicht -- dort die bekannte Schreibweise behalten.
        val bekannt = gelernt[schluessel]?.first ?: grossform[schluessel] ?: schluessel.takeIf { it in rang }
        val schreibweise = if (vorher.isNotEmpty() || bekannt == null) wort else bekannt
        gelernt[schluessel] = schreibweise to ((gelernt[schluessel]?.second ?: 0) + anzahl)
        merkSchluessel = ""

        if (vorher.isNotEmpty()) {
            val liste = folgen.getOrPut(vorher.lowercase()) { HashMap() }
            liste[schluessel] = (liste[schluessel] ?: 0) + 1
        }
        if (++ungespeichert >= 20) speichere()
    }

    fun vergiss(wort: String) {
        merkSchluessel = ""
        val schluessel = wort.lowercase()
        gelernt.remove(schluessel)
        folgen.remove(schluessel)
        folgen.values.forEach { it.remove(schluessel) }
        speichere()
    }

    fun vergissAlles() {
        merkSchluessel = ""
        gelernt.clear()
        folgen.clear()
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
            }
        }
    }

    companion object {
        const val MAX_WOERTER = 5000
        const val MAX_FOLGEN = 8
        const val MAX_LAENGE = 32
        private val UMLAUTE = mapOf('a' to 'ä', 'ä' to 'a', 'o' to 'ö', 'ö' to 'o', 'u' to 'ü', 'ü' to 'u')
    }
}
