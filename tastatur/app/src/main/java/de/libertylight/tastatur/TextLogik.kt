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

    /**
     * Das Wort vor dem aktuellen und ob ein Satz beginnt. Nach Komma und Co. gibt es kein sinnvolles
     * Vorgaengerwort; am Feldanfang und nach . ! ? oder Zeilenumbruch beginnt ein Satz.
     */
    fun kontext(vorCursor: CharSequence): Kontext {
        var i = vorCursor.length
        while (i > 0 && istWortzeichen(vorCursor[i - 1])) i--
        var ende = i
        while (ende > 0 && (vorCursor[ende - 1] == ' ' || vorCursor[ende - 1] == '\t')) ende--
        if (ende == 0) return Kontext.SATZANFANG
        val letztes = vorCursor[ende - 1]
        if (letztes in ".!?\n") return Kontext.SATZANFANG
        if (!istWortzeichen(letztes)) return Kontext.KEINER
        // zwischen den Woertern muss ein Leerzeichen stehen, sonst gehoert das Wort noch zum aktuellen
        if (ende == i) return Kontext.KEINER
        return Kontext(aktuellesWort(vorCursor.subSequence(0, ende)))
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
}
