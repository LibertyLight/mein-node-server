package de.libertylight.tastatur

/**
 * Wortliste mit Häufigkeiten und häufigen Folgewörtern. Reine Daten, ohne Android.
 *
 * Aufbau der Dateien: siehe tastatur/werkzeuge/baue_sprachdaten.py
 *   woerter: "Schreibweise<TAB>q"  q = -2 * log2(Wahrscheinlichkeit)
 *   folgen:  "vorher<TAB>nachher:q nachher:q"  q = -log2(P(nachher | vorher)), "<s>" = Satzanfang
 */
class Sprachmodell(woerterZeilen: List<String>, folgenZeilen: List<String> = emptyList()) {

    /** Kleingeschriebene Wörter, alphabetisch. Die Position ist die Nummer des Wortes. */
    val schluessel: Array<String>
    private val schreibweisen: Array<String>
    private val q: ByteArray
    private val gross: ByteArray

    private val folgen: Array<IntArray?>
    private var satzanfang: IntArray? = null

    /** Wörter bis zu diesem q gelten als Funktionswörter (ich, und, der ...): etwa die häufigsten 250. */
    val funktionswortQ: Int

    val anzahl: Int get() = schluessel.size

    init {
        class Eintrag(val schluessel: String, val schreib: String, val q: Int, val gross: Int)
        val eintraege = ArrayList<Eintrag>(woerterZeilen.size)
        for (zeile in woerterZeilen) {
            if (zeile.isBlank()) continue
            val teile = zeile.split('\t')
            val schreib = teile[0].trim()
            val wert = teile.getOrNull(1)?.trim()?.toIntOrNull() ?: 30
            // ohne Angabe: gross, wenn die Schreibweise gross ist
            val g = teile.getOrNull(2)?.trim()?.toIntOrNull() ?: if (schreib[0].isUpperCase()) 100 else 0
            eintraege += Eintrag(schreib.lowercase(), schreib, wert.coerceIn(0, 120), g.coerceIn(0, 100))
        }
        var sortiert = true
        for (i in 1 until eintraege.size) if (eintraege[i - 1].schluessel >= eintraege[i].schluessel) { sortiert = false; break }
        // doppelte Schluessel (zwei Schreibweisen desselben Wortes) zusammenfassen: die haeufigere gewinnt
        val liste = if (sortiert) eintraege else eintraege.sortedBy { it.schluessel }.fold(ArrayList<Eintrag>()) { acc, e ->
            if (acc.isNotEmpty() && acc.last().schluessel == e.schluessel) { if (e.q < acc.last().q) acc[acc.size - 1] = e } else acc += e
            acc
        }
        schluessel = Array(liste.size) { liste[it].schluessel }
        schreibweisen = Array(liste.size) { liste[it].schreib }
        q = ByteArray(liste.size) { liste[it].q.toByte() }
        gross = ByteArray(liste.size) { liste[it].gross.toByte() }

        val histogramm = IntArray(121)
        for (b in q) histogramm[b.toInt()]++
        var summe = 0
        var grenze = 0
        while (grenze < 120 && summe + histogramm[grenze] <= 250) { summe += histogramm[grenze]; grenze++ }
        funktionswortQ = grenze - 1

        folgen = arrayOfNulls(schluessel.size)
        for (zeile in folgenZeilen) {
            val tab = zeile.indexOf('\t')
            if (tab < 0) continue
            val vorher = zeile.substring(0, tab)
            val liste2 = zeile.substring(tab + 1).split(' ').mapNotNull { eintrag ->
                val dp = eintrag.lastIndexOf(':')
                if (dp < 0) return@mapNotNull null
                val gross = eintrag.endsWith("^")
                val nr = index(eintrag.substring(0, dp))
                val qb = eintrag.substring(dp + 1).trimEnd('^').toIntOrNull() ?: return@mapNotNull null
                if (nr < 0) null else (nr shl 5) or (if (gross) 16 else 0) or qb.coerceIn(0, 15)
            }.toIntArray()
            if (vorher == SATZANFANG) satzanfang = liste2
            else index(vorher).let { if (it >= 0) folgen[it] = liste2 }
        }
    }

    /** Nummer des Wortes (Kleinschreibung) oder -1. */
    fun index(klein: String): Int {
        var lo = 0
        var hi = schluessel.size - 1
        while (lo <= hi) {
            val mitte = (lo + hi) ushr 1
            val c = schluessel[mitte].compareTo(klein)
            when {
                c < 0 -> lo = mitte + 1
                c > 0 -> hi = mitte - 1
                else -> return mitte
            }
        }
        return -1
    }

    fun schreibweise(i: Int): String = schreibweisen[i]

    fun istSubstantiv(i: Int): Boolean = schreibweisen[i][0].isUpperCase()

    /** Wie oft das Wort mitten im Satz großgeschrieben steht, in Prozent (0 = nie, 100 = immer). */
    fun grossAnteil(i: Int): Int = gross[i].toInt()

    fun istFunktionswort(i: Int): Boolean = q[i] <= funktionswortQ

    /** Natürlicher Logarithmus der Einzelwort-Wahrscheinlichkeit. */
    fun lnP(i: Int): Double = -q[i] * LN2_HALB

    /** Erster Index, dessen Wort nicht kleiner ist als [klein]. */
    private fun untereGrenze(klein: String): Int {
        var lo = 0
        var hi = schluessel.size
        while (lo < hi) {
            val mitte = (lo + hi) ushr 1
            if (schluessel[mitte] < klein) lo = mitte + 1 else hi = mitte
        }
        return lo
    }

    /** Alle Wörter, die mit [praefix] (klein) beginnen, als Bereich von Nummern. */
    fun praefixBereich(praefix: String): IntRange {
        val von = untereGrenze(praefix)
        var bis = von
        while (bis < schluessel.size && schluessel[bis].startsWith(praefix)) bis++
        return von until bis
    }

    /**
     * Häufigste Nachfolger von [vorher] (Wortnummer) oder vom Satzanfang (-2).
     * Gepackt: nummer shl 5, Bit 16 = steht dort meist großgeschrieben, Bits 0-15 = q.
     */
    fun nachfolger(vorher: Int): IntArray? = when {
        vorher == SATZANFANG_NR -> satzanfang
        vorher >= 0 -> folgen[vorher]
        else -> null
    }

    /** ln P(i | vorher), wenn i unter den gespeicherten Nachfolgern steht. */
    fun folgeLnP(vorher: Int, i: Int): Double? {
        val liste = nachfolger(vorher) ?: return null
        for (e in liste) if (folgeNummer(e) == i) return -folgeQ(e) * LN2
        return null
    }

    companion object {
        fun folgeNummer(e: Int) = e ushr 5
        fun folgeQ(e: Int) = e and 15
        fun folgeGross(e: Int) = (e and 16) != 0

        const val SATZANFANG = "<s>"
        const val SATZANFANG_NR = -2
        const val LN2 = 0.6931471805599453
        const val LN2_HALB = LN2 / 2
    }
}
