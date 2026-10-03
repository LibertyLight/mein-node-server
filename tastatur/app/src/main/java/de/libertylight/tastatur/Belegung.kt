package de.libertylight.tastatur

/** Was eine Taste tut. ZEICHEN schreibt ihre Beschriftung, alles andere ist eine Aktion. */
enum class Art { ZEICHEN, SHIFT, LOESCHEN, ENTER, LEER, SYMBOLE, SYMBOLE2, BUCHSTABEN, EMOJI, ABSTAND }

/** Symbol der Enter-Taste, je nachdem, was das Textfeld von ihr erwartet. */
enum class EnterArt { ZEILE, SENDEN, SUCHEN, LOS, WEITER, FERTIG }

data class Taste(
    val text: String,
    val art: Art = Art.ZEICHEN,
    /** Breite in Spalten; zehn Spalten sind die ganze Tastaturbreite. */
    val breite: Float = 1f,
    /** Zeichen, die beim langen Druecken zur Auswahl stehen. */
    val alternativen: List<String> = emptyList(),
    /** Kleines Zeichen oben rechts auf der Taste. */
    val hinweis: String = "",
)

enum class Seite { BUCHSTABEN, SYMBOLE, SYMBOLE2, ZIFFERN, TELEFON }

/** Was sich an der Buchstabenseite einstellen laesst. */
data class Optionen(
    /** Eigene Zahlenreihe ueber den Buchstaben. */
    val zahlenreihe: Boolean = false,
    /** ü ö ä auf eigenen Tasten (11 pro Reihe) statt per Langdruck (10 und 9 pro Reihe). */
    val umlautTasten: Boolean = false,
    /** Ziffern als kleine Hinweise auf der oberen Reihe; Langdruck schreibt dann die Ziffer. */
    val zifferHinweise: Boolean = false,
)

/**
 * Tastenbelegungen. Reine Daten, ohne Android -- deshalb testbar.
 *
 * Das Raster stammt aus dem Referenz-Screenshot einer Tastatur mit 10 / 9 / 7 Tasten:
 * Reihe 2 ist um eine halbe Taste eingerueckt, Umschalt und Loeschen sind 1,5 Tasten
 * breit, die Leertaste 4,5.
 */
object Belegung {

    private val akzente = mapOf(
        "a" to "ä à á â ã å æ ā", "e" to "é è ê ë ē", "i" to "í ì î ï", "o" to "ö ò ó ô õ ø œ",
        "u" to "ü ù ú û ū", "s" to "ß ś š", "n" to "ñ ń", "c" to "ç ć č", "y" to "ý ÿ",
        "z" to "ž ź ż", "l" to "ł", "d" to "ð", "ü" to "ú ù û ū", "ö" to "ò ó ô õ ø œ", "ä" to "à á â ã å æ",
    )

    private val symbole = mapOf(
        "," to ", ; : _ -", "." to ". ? ! … ' \" ( ) -",
        "1" to "¹ ½ ⅓ ¼", "2" to "² ⅔", "3" to "³ ¾", "0" to "° ∅",
        "-" to "– — _", "?" to "¿", "!" to "¡", "%" to "‰", "\"" to "„ “ ” « »", "'" to "‚ ‘ ’ ‹ ›",
        "€" to "$ £ ¥ ¢", "=" to "≈ ≠ ≤ ≥", "+" to "± ×", "<" to "‹ ≤", ">" to "› ≥",
    )

    private val ziffern = "qwertzuiop".mapIndexed { i, c -> c.toString() to ((i + 1) % 10).toString() }.toMap()

    private fun liste(text: String?) = text?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()

    private fun taste(text: String, o: Optionen, breite: Float = 1f, hinweis: String = ""): Taste {
        var alt = liste(akzente[text] ?: symbole[text])
        // Mit eigenen Umlauttasten braucht a/o/u keinen Umlaut mehr im Langdruck
        if (o.umlautTasten && text in setOf("a", "o", "u")) alt = alt.drop(1)
        var kleiner = hinweis
        ziffern[text]?.let { ziffer ->
            if (!o.zahlenreihe) {
                alt = if (o.zifferHinweise) listOf(ziffer) + alt else alt + ziffer
                if (o.zifferHinweise) kleiner = ziffer
            }
        }
        return Taste(text, Art.ZEICHEN, breite, alt, kleiner)
    }

    private fun reihe(zeichen: String, o: Optionen, breite: Float = 1f) =
        zeichen.split(' ').map { taste(it, o, breite) }

    private fun abstand(breite: Float) = Taste("", Art.ABSTAND, breite)

    private fun unterste(links: Taste, o: Optionen) = listOf(
        links,
        Taste("☺", Art.EMOJI),
        taste(",", o),
        Taste("Deutsch", Art.LEER, 4.5f),
        taste(".", o, hinweis = "!?"),
        Taste("↵", Art.ENTER, 1.5f),
    )

    fun reihen(seite: Seite, o: Optionen = Optionen()): List<List<Taste>> = when (seite) {
        Seite.BUCHSTABEN -> buildList {
            if (o.zahlenreihe) add(reihe("1 2 3 4 5 6 7 8 9 0", o))
            if (o.umlautTasten) {
                val b = 10f / 11f
                add(reihe("q w e r t z u i o p ü", o, b))
                add(reihe("a s d f g h j k l ö ä", o, b))
            } else {
                add(reihe("q w e r t z u i o p", o))
                add(listOf(abstand(0.5f)) + reihe("a s d f g h j k l", o) + abstand(0.5f))
            }
            add(listOf(Taste("⇧", Art.SHIFT, 1.5f)) + reihe("y x c v b n m", o) + Taste("⌫", Art.LOESCHEN, 1.5f))
            add(unterste(Taste("123", Art.SYMBOLE), o))
        }
        Seite.SYMBOLE -> listOf(
            reihe("1 2 3 4 5 6 7 8 9 0", o),
            reihe("+ × ÷ = / _ < > [ ]", o),
            reihe("! @ # € % ^ & * ( )", o),
            listOf(Taste("1/2", Art.SYMBOLE2, 1.5f)) + reihe("- ' \" : ; , ?", o) + Taste("⌫", Art.LOESCHEN, 1.5f),
            unterste(Taste("ABC", Art.BUCHSTABEN), o),
        )
        Seite.SYMBOLE2 -> listOf(
            reihe("` ~ \\ | { } € £ ¥ $", o),
            reihe("° • ○ ● □ ■ ♤ ♡ ◇ ♧", o),
            reihe("☆ ▪ ¤ 《 》 ¡ ¿ § ¶ ©", o),
            listOf(Taste("2/2", Art.SYMBOLE, 1.5f)) + reihe("® ™ « » ‰ … ·", o) + Taste("⌫", Art.LOESCHEN, 1.5f),
            unterste(Taste("ABC", Art.BUCHSTABEN), o),
        )
        Seite.ZIFFERN, Seite.TELEFON -> {
            val tel = seite == Seite.TELEFON
            listOf(
                reihe("1 2 3", o) + Taste("⌫", Art.LOESCHEN),
                reihe("4 5 6", o) + taste(if (tel) "+" else "-", o),
                reihe("7 8 9", o) + taste(if (tel) "*" else ",", o),
                listOf(taste(if (tel) "#" else ".", o), taste("0", o), Taste("␣", Art.LEER), Taste("↵", Art.ENTER)),
            )
        }
    }
}

/**
 * Wo die Tasten liegen -- und damit, wie weit ein Fingerdruck von jeder Taste entfernt ist.
 * Daraus errechnet die Autokorrektur, welcher Buchstabe mit welcher Wahrscheinlichkeit gemeint war.
 * Alle Masse in Tastenbreiten (zehn Spalten = Tastaturbreite).
 */
object Raster {
    /** Zeilenabstand durch Spaltenabstand: 183 px zu 108 px im Referenz-Screenshot. */
    const val ZEILEN_VERHAELTNIS = 1.694f

    /** Mitte jeder Zeichentaste (x, y), y waechst nach unten. */
    fun mitten(reihen: List<List<Taste>>, zeilenVerhaeltnis: Float = ZEILEN_VERHAELTNIS): Map<Char, Pair<Float, Float>> {
        val karte = HashMap<Char, Pair<Float, Float>>()
        reihen.forEachIndexed { r, reihe ->
            val skala = 10f / reihe.sumOf { it.breite.toDouble() }.toFloat()
            var x = 0f
            for (t in reihe) {
                val w = t.breite * skala
                if (t.art == Art.ZEICHEN && t.text.length == 1) {
                    karte[t.text[0]] = (x + w / 2) to ((r + 0.5f) * zeilenVerhaeltnis)
                }
                x += w
            }
        }
        return karte
    }
}

/** Ein Fingerdruck auf eine Zeichentaste: welche Taste getroffen wurde und wo genau. */
data class Anschlag(val zeichen: Char, val x: Float, val y: Float)
