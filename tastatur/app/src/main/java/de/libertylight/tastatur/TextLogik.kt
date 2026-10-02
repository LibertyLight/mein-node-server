package de.libertylight.tastatur

/**
 * Reine Textregeln ohne Android-Abhaengigkeiten -- deshalb auf dem Rechner testbar.
 */
object TextLogik {

    /** Zeichen, die zu einem Wort gehoeren (Buchstaben, Ziffern, Apostroph, Bindestrich). */
    fun istWortzeichen(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '-'

    /** Das Wort, das direkt vor dem Cursor steht (leer, wenn davor ein Leer- oder Satzzeichen ist). */
    fun aktuellesWort(vorCursor: CharSequence): String {
        var i = vorCursor.length
        while (i > 0 && istWortzeichen(vorCursor[i - 1])) i--
        return vorCursor.subSequence(i, vorCursor.length).toString().trimStart('\'', '-')
    }

    /** Das letzte abgeschlossene Wort vor dem aktuellen -- fuer Folgewort-Vorschlaege. */
    fun vorherigesWort(vorCursor: CharSequence): String {
        val ohneAktuelles = vorCursor.subSequence(0, vorCursor.length - aktuellesWortRoh(vorCursor).length)
        val text = ohneAktuelles.trimEnd()
        // Nach einem Satzende gibt es kein sinnvolles Vorgaengerwort.
        if (text.isEmpty() || !istWortzeichen(text.last())) return ""
        return aktuellesWort(text)
    }

    private fun aktuellesWortRoh(vorCursor: CharSequence): String {
        var i = vorCursor.length
        while (i > 0 && istWortzeichen(vorCursor[i - 1])) i--
        return vorCursor.subSequence(i, vorCursor.length).toString()
    }

    /** Doppelte Leertaste wird zu ". ", wenn direkt davor ein Wort mit einem Leerzeichen endet. */
    fun doppelLeerzeichenPunkt(vorCursor: CharSequence): Boolean {
        val n = vorCursor.length
        return n >= 2 && vorCursor[n - 1] == ' ' && vorCursor[n - 2].isLetterOrDigit()
    }

    /** Satzzeichen, vor denen ein automatisch gesetztes Leerzeichen wieder verschwindet. */
    fun schliesstAn(text: String): Boolean = text.length == 1 && text[0] in ".,!?:;)"

    /** Uebernimmt die Grossschreibung des Getippten in den Vorschlag. */
    fun passeSchreibungAn(vorschlag: String, getippt: String): String = when {
        getippt.length > 1 && getippt.all { !it.isLetter() || it.isUpperCase() } -> vorschlag.uppercase()
        getippt.firstOrNull()?.isUpperCase() == true -> vorschlag.replaceFirstChar { it.uppercaseChar() }
        else -> vorschlag
    }

    /** Grossbuchstabe zu einem Tastenzeichen; "ß" bleibt (sonst wuerde "SS" daraus). */
    fun gross(zeichen: String): String = if (zeichen == "ß") zeichen else zeichen.uppercase()

    /** Levenshtein-Abstand, abgebrochen sobald er groesser als [grenze] wird. */
    fun abstand(a: String, b: String, grenze: Int = 2): Int {
        if (kotlin.math.abs(a.length - b.length) > grenze) return grenze + 1
        var vorher = IntArray(b.length + 1) { it }
        var jetzt = IntArray(b.length + 1)
        for (i in 1..a.length) {
            jetzt[0] = i
            var zeilenMin = jetzt[0]
            for (j in 1..b.length) {
                val kosten = if (a[i - 1] == b[j - 1]) 0 else 1
                jetzt[j] = minOf(vorher[j] + 1, jetzt[j - 1] + 1, vorher[j - 1] + kosten)
                zeilenMin = minOf(zeilenMin, jetzt[j])
            }
            if (zeilenMin > grenze) return grenze + 1
            val tausch = vorher; vorher = jetzt; jetzt = tausch
        }
        return vorher[b.length]
    }
}
