package de.libertylight.tastatur

/**
 * Wortvorschlaege: ein Grundwortschatz plus alles, was beim Tippen gelernt wird.
 *
 * - Vervollstaendigung: Woerter, die mit dem Getippten beginnen
 * - Korrektur: aehnliche Woerter (ein bis zwei Tippfehler entfernt)
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

    /** Grundwortschatz: Rang 0 ist das haeufigste Wort. Schluessel klein geschrieben. */
    private val grund = HashMap<String, Pair<String, Int>>()
    private val gelernt = HashMap<String, Pair<String, Int>>()
    private val folgen = HashMap<String, HashMap<String, Int>>()
    private var ungespeichert = 0

    init {
        grundwortschatz.forEachIndexed { rang, wort ->
            val w = wort.trim()
            if (w.isNotEmpty()) grund.putIfAbsent(w.lowercase(), w to rang)
        }
        lade()
    }

    private fun punkte(schluessel: String): Int {
        val g = gelernt[schluessel]?.second ?: 0
        val rang = grund[schluessel]?.second
        val basis = if (rang == null) 0 else (grund.size - rang) * 10 / grund.size.coerceAtLeast(1)
        return g * 20 + basis
    }

    private fun schreibweise(schluessel: String): String =
        gelernt[schluessel]?.first ?: grund[schluessel]?.first ?: schluessel

    private fun alleSchluessel(): Sequence<String> = (grund.keys.asSequence() + gelernt.keys.asSequence()).distinct()

    /**
     * Bis zu [anzahl] Vorschlaege. Bei leerem [praefix] kommen Folgewoerter zu [vorher].
     * Das Getippte selbst steht vorn, damit man es unveraendert uebernehmen kann.
     */
    fun vorschlaege(praefix: String, vorher: String = "", anzahl: Int = 3): List<String> {
        if (praefix.isEmpty()) {
            val nachfolger = folgen[vorher.lowercase()] ?: return emptyList()
            return nachfolger.entries.sortedByDescending { it.value }.take(anzahl).map { schreibweise(it.key) }
        }

        val klein = praefix.lowercase()
        val ergebnis = LinkedHashSet<String>()
        ergebnis += praefix

        val folgePunkte = folgen[vorher.lowercase()] ?: emptyMap()
        alleSchluessel()
            .filter { it.length > klein.length && it.startsWith(klein) }
            .sortedByDescending { punkte(it) + (folgePunkte[it] ?: 0) * 30 }
            .take(anzahl)
            .forEach { ergebnis += TextLogik.passeSchreibungAn(schreibweise(it), praefix) }

        if (ergebnis.size < anzahl + 1 && klein.length >= 3) {
            val grenze = if (klein.length >= 6) 2 else 1
            alleSchluessel()
                .filter { it != klein && TextLogik.abstand(klein, it, grenze) <= grenze }
                .sortedByDescending { punkte(it) }
                .take(anzahl)
                .forEach { ergebnis += TextLogik.passeSchreibungAn(schreibweise(it), praefix) }
        }

        // Ein bekanntes Wort, das genau so getippt wurde, steht nicht doppelt.
        return ergebnis.distinctBy { it.lowercase() }.take(anzahl)
    }

    /** Ist das Getippte ein bekanntes Wort? */
    fun kennt(wort: String): Boolean = wort.lowercase().let { it in grund || it in gelernt }

    /** Ein abgeschlossenes Wort merken -- und welches Wort davor stand. */
    fun lerne(wort: String, vorher: String = "") {
        if (wort.length < 2 || wort.length > 40 || !wort.any { it.isLetter() }) return
        if (wort.any { it.isDigit() }) return // Nummern, Codes, Uhrzeiten nicht lernen
        val schluessel = wort.lowercase()
        // Mitten im Satz (es gibt ein Vorgaengerwort) zaehlt die getippte Schreibweise.
        // Am Satzanfang ist ein Grossbuchstabe Pflicht -- dort die bekannte Schreibweise behalten.
        val bekannt = gelernt[schluessel]?.first ?: grund[schluessel]?.first
        val schreibweise = if (vorher.isNotEmpty() || bekannt == null) wort else bekannt
        gelernt[schluessel] = schreibweise to ((gelernt[schluessel]?.second ?: 0) + 1)

        if (vorher.isNotEmpty()) {
            val liste = folgen.getOrPut(vorher.lowercase()) { HashMap() }
            liste[schluessel] = (liste[schluessel] ?: 0) + 1
        }
        if (++ungespeichert >= 20) speichere()
    }

    fun vergiss(wort: String) {
        val schluessel = wort.lowercase()
        gelernt.remove(schluessel)
        folgen.remove(schluessel)
        folgen.values.forEach { it.remove(schluessel) }
        speichere()
    }

    fun vergissAlles() {
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
    }
}
