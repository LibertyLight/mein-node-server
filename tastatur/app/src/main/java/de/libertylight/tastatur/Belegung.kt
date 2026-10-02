package de.libertylight.tastatur

/** Was eine Taste tut. ZEICHEN schreibt ihre Beschriftung, alles andere ist eine Aktion. */
enum class Art { ZEICHEN, SHIFT, LOESCHEN, ENTER, LEER, SYMBOLE, SYMBOLE2, BUCHSTABEN }

data class Taste(
    val text: String,
    val art: Art = Art.ZEICHEN,
    val breite: Float = 1f,
    /** Zeichen, die beim langen Druecken zur Auswahl stehen. */
    val alternativen: List<String> = emptyList(),
) {
    /** Kleine Beschriftung oben rechts -- das erste alternative Zeichen, wie bei Samsung. */
    val hinweis: String get() = if (art == Art.ZEICHEN) alternativen.firstOrNull().orEmpty() else ""
}

enum class Seite { BUCHSTABEN, SYMBOLE, SYMBOLE2, ZIFFERN, TELEFON }

/** Tastenbelegungen. Deutsches QWERTZ wie auf Samsung-Geraeten, mit ü, ö, ä auf eigenen Tasten. */
object Belegung {

    private val alt = mapOf(
        "q" to "1", "w" to "2", "e" to "3 é è ê ë €", "r" to "4", "t" to "5", "z" to "6",
        "u" to "7 ú ù û", "i" to "8 í ì î ï", "o" to "9 ó ò ô õ ø œ", "p" to "0", "ü" to "ú",
        "a" to "@ à á â ã å æ", "s" to "* ß ś š $", "d" to "# ð", "f" to "+", "g" to "-",
        "h" to "=", "j" to "(", "k" to ")", "l" to "/ ł", "ö" to "ó", "ä" to "æ",
        "y" to "! ý ÿ", "x" to "\" ×", "c" to "' ç ć č", "v" to ":", "b" to ";", "n" to "? ñ ń", "m" to "%",
        "," to ", ; : _ -", "." to ". ? ! … ' \" ( ) -",
        "1" to "¹ ½ ⅓ ¼", "2" to "² ⅔", "3" to "³ ¾", "0" to "° ∅",
        "-" to "– — _", "€" to "$ £ ¥ ¢", "?" to "¿", "!" to "¡", "%" to "‰", "\"" to "„ “ ” « »", "'" to "‚ ‘ ’ ‹ ›",
    )

    private fun t(text: String, breite: Float = 1f) =
        Taste(text, Art.ZEICHEN, breite, alt[text]?.split(' ')?.filter { it.isNotEmpty() }.orEmpty())

    private fun reihe(zeichen: String) = zeichen.split(' ').map { t(it) }

    private val zahlenreihe = reihe("1 2 3 4 5 6 7 8 9 0")

    private fun unterste(links: Taste) = listOf(
        links,
        t(",", 1f),
        Taste("Deutsch", Art.LEER, 5f),
        t(".", 1f),
        Taste("↵", Art.ENTER, 1.5f),
    )

    fun reihen(seite: Seite, mitZahlenreihe: Boolean): List<List<Taste>> = when (seite) {
        Seite.BUCHSTABEN -> buildList {
            if (mitZahlenreihe) add(zahlenreihe)
            add(reihe("q w e r t z u i o p ü"))
            add(reihe("a s d f g h j k l ö ä"))
            add(listOf(Taste("⇧", Art.SHIFT, 2f)) + reihe("y x c v b n m") + Taste("⌫", Art.LOESCHEN, 2f))
            add(unterste(Taste("!#1", Art.SYMBOLE, 1.5f)).let { r ->
                // Fuer 11 Tasten breite Reihen: Leertaste etwas breiter
                r.map { if (it.art == Art.LEER) it.copy(breite = 6f) else it }
            })
        }
        Seite.SYMBOLE -> listOf(
            zahlenreihe,
            reihe("+ × ÷ = / _ < > [ ]"),
            reihe("! @ # € % ^ & * ( )"),
            listOf(Taste("1/2", Art.SYMBOLE2, 1.5f)) + reihe("- ' \" : ; , ?") + Taste("⌫", Art.LOESCHEN, 1.5f),
            unterste(Taste("ABC", Art.BUCHSTABEN, 1.5f)),
        )
        Seite.SYMBOLE2 -> listOf(
            reihe("` ~ \\ | { } € £ ¥ $"),
            reihe("° • ○ ● □ ■ ♤ ♡ ◇ ♧"),
            reihe("☆ ▪ ¤ 《 》 ¡ ¿ § ¶ ©"),
            listOf(Taste("2/2", Art.SYMBOLE, 1.5f)) + reihe("® ™ « » ‰ … ·") + Taste("⌫", Art.LOESCHEN, 1.5f),
            unterste(Taste("ABC", Art.BUCHSTABEN, 1.5f)),
        )
        Seite.ZIFFERN, Seite.TELEFON -> {
            val tel = seite == Seite.TELEFON
            listOf(
                reihe("1 2 3") + Taste("⌫", Art.LOESCHEN),
                reihe("4 5 6") + t(if (tel) "+" else "-"),
                reihe("7 8 9") + t(if (tel) "*" else ","),
                listOf(t(if (tel) "#" else "."), t("0"), Taste("␣", Art.LEER), Taste("↵", Art.ENTER)),
            )
        }
    }
}
