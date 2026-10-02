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

    // ---------------------------------------------------------------- Tippfehler

    private val reihen = listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm")

    /** Position jeder Taste; die Reihen sind wie auf der Tastatur leicht versetzt. */
    private val position: Map<Char, Pair<Float, Float>> = buildMap {
        reihen.forEachIndexed { r, reihe ->
            reihe.forEachIndexed { c, z -> put(z, (c + r * 0.4f) to r.toFloat()) }
        }
    }

    private val umlaute = setOf("aä", "äa", "oö", "öo", "uü", "üu", "sß", "ßs")

    /** Kosten, wenn statt [soll] die Taste [ist] getroffen wurde. */
    fun ersetzKosten(soll: Char, ist: Char): Float {
        if (soll == ist) return 0f
        if ("$soll$ist" in umlaute) return 0.3f // "fur" statt "für"
        val a = position[soll] ?: return 1f
        val b = position[ist] ?: return 1f
        val nachbar = kotlin.math.abs(a.first - b.first) <= 1.1f && kotlin.math.abs(a.second - b.second) <= 1f
        return if (nachbar) 0.6f else 1f
    }

    /**
     * Gewichteter Tippfehler-Abstand (Damerau-Levenshtein, eingeschraenkt):
     * Nachbartasten, vertauschte Buchstaben und fehlende Umlaute kosten weniger.
     * Gross-/Kleinschreibung zaehlt nicht. Bricht ab, sobald [grenze] ueberschritten ist.
     */
    fun tippAbstand(getippt: String, wort: String, grenze: Float = 2f): Float {
        val a = getippt.lowercase()
        val b = wort.lowercase()
        if (kotlin.math.abs(a.length - b.length) > grenze * 2) return grenze + 1
        // Drei rollende Zeilen statt einer ganzen Matrix: laeuft zehntausendfach pro Tastendruck.
        val n = b.length + 1
        if (zeileA.size < n) { zeileA = FloatArray(n); zeileB = FloatArray(n); zeileC = FloatArray(n) }
        var vorvorher = zeileA
        var vorher = zeileB
        var jetzt = zeileC
        for (j in 0 until n) vorher[j] = j.toFloat()
        for (i in 1..a.length) {
            jetzt[0] = i.toFloat()
            var zeilenMin = jetzt[0]
            for (j in 1..b.length) {
                // Doppelbuchstaben vergessen oder verdoppelt ("vileicht", "Hallo" -> "Halllo") ist billig
                val zuViel = if (i > 1 && a[i - 1] == a[i - 2]) 0.5f else 1f
                val vergessen = if (j > 1 && b[j - 1] == b[j - 2]) 0.5f else 1f
                var wert = minOf(
                    vorher[j] + zuViel,
                    jetzt[j - 1] + vergessen,
                    vorher[j - 1] + ersetzKosten(b[j - 1], a[i - 1]),
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    wert = minOf(wert, vorvorher[j - 2] + 0.7f)
                }
                jetzt[j] = wert
                if (wert < zeilenMin) zeilenMin = wert
            }
            if (zeilenMin > grenze) return grenze + 1
            val frei = vorvorher; vorvorher = vorher; vorher = jetzt; jetzt = frei
        }
        return vorher[b.length]
    }

    // Puffer fuer tippAbstand (die Tastatur rechnet nur auf dem Haupt-Thread)
    private var zeileA = FloatArray(40)
    private var zeileB = FloatArray(40)
    private var zeileC = FloatArray(40)

    /** Wie viel Tippfehler ein Wort dieser Laenge haben darf, um noch korrigiert zu werden. */
    fun fehlerGrenze(laenge: Int): Float = when {
        laenge < 3 -> 0f
        laenge <= 4 -> 1f
        laenge <= 7 -> 1.2f
        else -> 1.6f
    }

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
