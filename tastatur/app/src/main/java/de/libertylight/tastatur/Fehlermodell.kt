package de.libertylight.tastatur

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Alle Stellschrauben der Autokorrektur an einem Ort. Kosten in "nats" (natürlicher Logarithmus):
 * je größer, desto unwahrscheinlicher ist der Fehler. Die Vorgaben stammen aus der Abstimmung
 * mit simulierten Tippfehlern (siehe KorrekturBewertungTest und AUTOKORREKTUR.md).
 */
data class Parameter(
    // --- Fehlermodell: wie wird getippt?
    /** Streuung der Fingerdrücke um die Tastenmitte, in Tastenbreiten. */
    val sigma: Float = 0.36f,
    /** Dasselbe, wenn die Berührungsstelle nicht bekannt ist (Unsicherheit der Position kommt dazu). */
    val sigmaOhneBeruehrung: Float = 0.35f,
    val kostenAuslassen: Float = 6.0f,
    val kostenDoppeltAuslassen: Float = 4.2f,
    val kostenEinfuegen: Float = 7.6f,
    val kostenDoppeltEinfuegen: Float = 4.2f,
    val kostenTausch: Float = 5.0f,
    /** Grundkosten, dass statt der gemeinten Taste eine andere getroffen wurde (zusätzlich zum Abstand). */
    val kostenErsetzen: Float = 1.9f,
    /** u statt ü, o statt ö, a statt ä */
    val kostenUmlaut: Float = 0.4f,
    /** ss statt ß, ue statt ü, ae statt ä, oe statt ö */
    val kostenDigraph: Float = 1.2f,
    /** Aufschlag, wenn der erste Buchstabe nicht stimmt: den trifft man meist richtig. */
    val ersterBuchstabe: Float = 1.5f,
    val kostenMax: Float = 9.0f,
    // --- Entscheidung
    /**
     * ln-Wahrscheinlichkeit, dass das Getippte ein echtes Wort ist, das nur nicht in der Wortliste steht
     * (Name, Zusammensetzung, Fachwort). Ein Kandidat muss besser sein als dieser Wert plus [rand].
     */
    val unbekanntLnP: Double = -19.0,
    /** Ab diesem Anteil (Prozent) an Großschreibung mitten im Satz wird ein kleingetipptes Wort großgeschrieben. */
    val substantivSchwelle: Double = 90.0,
    val kompositumBonus: Double = 1.7,
    /** Gebeugte Form eines bekannten Wortes (Gegenstands, Fressfeinden). */
    val beugungsBonus: Double = 3.4,
    /** Großgeschrieben mitten im Satz: oft ein Name. */
    val namenBonus: Double = 0.4,
    /** Wie viel besser der beste Kandidat sein muss, bevor korrigiert wird. */
    val rand: Double = 0.5,
    /** Kosten für ein vergessenes Leerzeichen ("ichhabe" -> "ich habe"). */
    val kostenLeerzeichen: Double = 4.5,
    // --- Sprachmodell
    val folgeGewicht: Double = 0.7,
    val folgeMax: Double = 5.0,
    val nutzerWort: Double = 1.5,
    val nutzerWortLog: Double = 0.6,
    val nutzerWortMax: Double = 4.0,
    val nutzerFolge: Double = 1.5,
    /** Basiswahrscheinlichkeit eines gelernten Wortes, das nicht in der Wortliste steht (ln). */
    val nutzerUnbekanntLnP: Double = -13.0,
    /** Kürzeste Wortlänge, die korrigiert wird. */
    val minLaenge: Int = 3,
    /** Höchste Fehlerkosten, nach denen die Wortsuche überhaupt noch sucht. */
    val suchGrenze: Float = 12.0f,
)

/** Wo die Tasten liegen (in Tastenbreiten). Grundlage für "welche Taste war gemeint". */
class Tastenkarte(private val mitten: Map<Char, Pair<Float, Float>>) {

    fun mitte(c: Char): Pair<Float, Float>? = mitten[c]

    fun abstand(a: Char, b: Char): Float? {
        val ma = mitten[a] ?: return null
        val mb = mitten[b] ?: return null
        val dx = ma.first - mb.first
        val dy = ma.second - mb.second
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Die Tastatur wie im Referenz-Screenshot: 10 / 9 / 7 Buchstaben, Umlaute per Langdruck. */
        val STANDARD: Tastenkarte by lazy { von(Raster.mitten(Belegung.reihen(Seite.BUCHSTABEN, Optionen()))) }

        /** Umlaute ohne eigene Taste liegen dort, wo ihr Grundbuchstabe liegt. */
        fun von(mitten: Map<Char, Pair<Float, Float>>): Tastenkarte {
            val k = HashMap(mitten)
            for ((umlaut, grund) in listOf('ä' to 'a', 'ö' to 'o', 'ü' to 'u', 'ß' to 's')) {
                if (umlaut !in k) mitten[grund]?.let { k[umlaut] = it }
            }
            return Tastenkarte(k)
        }
    }
}

/** Kosten für das Vertippen: ersetzt, ausgelassen, eingefügt, vertauscht. */
class Fehlermodell(private val karte: Tastenkarte, val p: Parameter) {

    /**
     * Für jede Stelle des getippten Wortes: was kostet es, dass statt des getippten Zeichens
     * das Zeichen mit Nummer z gemeint war. [spur] kennt, wo der Finger wirklich aufgesetzt hat.
     */
    fun ersetzTabelle(typ: CharArray, spur: List<Anschlag?>?): Array<FloatArray> =
        Array(typ.size) { i ->
            val anschlag = spur?.getOrNull(i)?.takeIf { it.zeichen == typ[i] }
            FloatArray(ZEICHEN) { z -> ersetze(typ[i], zeichenVon(z), anschlag) }
        }

    private fun ersetze(getippt: Char, gemeint: Char?, anschlag: Anschlag?): Float {
        if (gemeint == null) return p.kostenMax
        if (gemeint == getippt) return 0f
        if ((gemeint == 'ä' && getippt == 'a') || (gemeint == 'ö' && getippt == 'o') || (gemeint == 'ü' && getippt == 'u')) return p.kostenUmlaut
        if ((getippt == 'ä' && gemeint == 'a') || (getippt == 'ö' && gemeint == 'o') || (getippt == 'ü' && gemeint == 'u')) return 3f
        val mg = karte.mitte(gemeint) ?: return p.kostenMax
        val mt = karte.mitte(getippt) ?: return p.kostenMax
        val kosten = if (anschlag != null) {
            // Wahrscheinlichkeitsverhältnis aus dem Abstand des Fingers zu beiden Tasten
            val dg = quadrat(anschlag.x - mg.first) + quadrat(anschlag.y - mg.second)
            val dt = quadrat(anschlag.x - mt.first) + quadrat(anschlag.y - mt.second)
            (dg - dt) / (2f * p.sigma * p.sigma)
        } else {
            // ohne Berührungsstelle: wie wahrscheinlich ist es, dass ein Druck auf g bei t landet
            val d = karte.abstand(getippt, gemeint) ?: return p.kostenMax
            val x = d / (2f * p.sigmaOhneBeruehrung)
            if (x < 0.8f) 1.2f else x * x / 2f + ln(x) + 0.92f
        }
        return (kosten + p.kostenErsetzen).coerceIn(0.5f, p.kostenMax)
    }

    private fun quadrat(x: Float) = x * x

    /** Kosten, wenn das getippte Zeichen Nummer i überflüssig war (versehentlich doppelt getippt ist billiger). */
    fun einfuegenKosten(typ: CharArray) =
        FloatArray(typ.size) { i -> if (i > 0 && typ[i] == typ[i - 1]) p.kostenDoppeltEinfuegen else p.kostenEinfuegen }

    companion object {
        /** a-z, ä, ö, ü, ß und "sonstige" */
        const val ZEICHEN = 31

        fun zeichenNr(c: Char): Int = when (c) {
            in 'a'..'z' -> c - 'a'
            'ä' -> 26
            'ö' -> 27
            'ü' -> 28
            'ß' -> 29
            else -> 30
        }

        private fun zeichenVon(z: Int): Char? = when (z) {
            in 0..25 -> 'a' + z
            26 -> 'ä'
            27 -> 'ö'
            28 -> 'ü'
            29 -> 'ß'
            else -> null
        }
    }
}
