package de.libertylight.tastatur

/** Das Textfeld, in das getippt wird. Im Dienst ist das die InputConnection, in Tests ein einfacher Text. */
interface TextFeld {
    fun vorCursor(anzahl: Int): CharSequence
    fun loescheVorCursor(anzahl: Int)
    fun schreibe(text: String)
    /** Löscht wie die Löschtaste: ein Zeichen oder die Markierung. */
    fun loescheTaste()
    /** Mehrere Änderungen als eine (Batch-Edit). */
    fun gruppe(block: () -> Unit) = block()
}

/** Was die Vorschlagsleiste anzeigen soll. */
data class Leiste(
    val wort: String,
    val liste: List<String>,
    /** Nummer des fett gesetzten Eintrags: was die Leertaste einsetzen würde. */
    val hervorgehoben: Int,
    /** Das getippte Wort steht nicht in der Wortliste (wird in Anführungszeichen gezeigt). */
    val unbekannt: Boolean,
)

/**
 * Die Regeln beim Tippen: Wort abschließen und korrigieren, Zurücknehmen mit ⌫, Doppel-Leertaste,
 * Vorschläge antippen -- und die Berührungsstellen des aktuellen Wortes führen.
 * Reine Logik ohne Android, damit sie sich am simulierten Textfeld testen lässt.
 */
class Eingabe(
    private val feld: TextFeld,
    private val woerterbuch: () -> Woerterbuch,
    private val zeit: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Autokorrektur erlaubt (Einstellung, Textfeld-Art, Buchstabenseite). */
    var korrigieren = true
    /** Gelernt werden darf (nicht in Passwortfeldern). */
    var lernen = true
    var doppelLeerPunkt = true

    private class Korrigiert(val original: String, val ersetzt: String, val trenner: String = "")

    private val spur = ArrayList<Anschlag?>()
    private var letzteKorrektur: Korrigiert? = null
    private var automatischesLeer = false
    private var letztesLeer = 0L

    /** Nach einem gewählten Vorschlag steht das Leerzeichen schon da. */
    val leerzeichenSchonDa: Boolean get() = automatischesLeer

    // ================================================================ Hilfen

    /** Die Berührungsstellen des aktuellen Wortes -- nur, wenn sie wirklich zu den Buchstaben im Feld passen. */
    private fun spurFuer(wort: String): List<Anschlag?>? {
        if (spur.size != wort.length) return null
        for (i in wort.indices) {
            val a = spur[i] ?: continue
            if (a.zeichen != wort[i].lowercaseChar()) return null
        }
        return spur
    }

    /** Autokorrektur nur in normalen Textfeldern -- nicht in Adressen, Handles, Passwörtern. */
    private fun darfKorrigieren(vor: CharSequence, wort: String): Boolean {
        if (!korrigieren) return false
        val davor = vor.getOrNull(vor.length - wort.length - 1)
        return davor == null || davor !in "@#/\\\\._:"
    }

    /** Mehrere Wörter (nach einer Trennung "ich habe") der Reihe nach merken. */
    private fun lerneWoerter(text: String, vorher: String) {
        var davor = vorher
        for (w in text.split(' ')) {
            if (w.isEmpty()) continue
            woerterbuch().lerne(w, davor)
            davor = w
        }
    }

    /**
     * Wird vor einem Leer- oder Satzzeichen aufgerufen: korrigiert das gerade beendete Wort
     * (falls nötig) und lernt es. Liefert die Korrektur, damit ⌫ sie zurücknehmen kann.
     */
    private fun schliesseWortAb(): Korrigiert? {
        val vor = feld.vorCursor(80)
        val wort = TextLogik.aktuellesWort(vor)
        if (wort.isEmpty()) return null
        val kontext = TextLogik.kontext(vor)
        val korrektur = if (darfKorrigieren(vor, wort)) woerterbuch().korrektur(wort, kontext, spurFuer(wort)) else null
        if (korrektur == null) {
            if (lernen) woerterbuch().lerne(wort, kontext.wort)
            return null
        }
        feld.loescheVorCursor(wort.length)
        feld.schreibe(korrektur)
        if (lernen) lerneWoerter(korrektur, kontext.wort)
        return Korrigiert(wort, korrektur)
    }

    // ================================================================ Tasten

    /** Ein Zeichen wurde getippt ([text] schon in Groß- oder Kleinschreibung); [anschlag] = wo der Finger war. */
    fun zeichen(text: String, anschlag: Anschlag?) {
        letzteKorrektur = null
        // Satzzeichen beenden ein Wort; Apostroph und Bindestrich gehören zum Wort ("geht's", "E-Mail")
        val trennt = !text[0].isLetterOrDigit() && text[0] != '\'' && text[0] != '-'

        if (automatischesLeer && TextLogik.schliesstAn(text) && feld.vorCursor(1).toString() == " ") {
            // "Hallo " + "," wird zu "Hallo, "
            feld.gruppe {
                feld.loescheVorCursor(1)
                feld.schreibe("$text ")
            }
        } else if (trennt) {
            var korrigiert: Korrigiert? = null
            feld.gruppe {
                korrigiert = schliesseWortAb()
                feld.schreibe(text)
            }
            letzteKorrektur = korrigiert?.let { Korrigiert(it.original, it.ersetzt, text) }
        } else {
            feld.schreibe(text)
        }
        automatischesLeer = false
        // Buchstaben gehören zum aktuellen Wort (mit der Stelle, wo getippt wurde), alles andere beendet es
        if (text.length == 1 && text[0].isLetter()) spur += anschlag else spur.clear()
    }

    fun leer() {
        val jetzt = zeit()
        val vor = feld.vorCursor(2)
        var korrigiert: Korrigiert? = null
        when {
            doppelLeerPunkt && jetzt - letztesLeer < 600 && TextLogik.doppelLeerzeichenPunkt(vor) -> {
                feld.gruppe {
                    feld.loescheVorCursor(1)
                    feld.schreibe(". ")
                }
            }
            // Nach einem gewählten Vorschlag steht das Leerzeichen schon da.
            automatischesLeer -> {}
            else -> {
                feld.gruppe {
                    korrigiert = schliesseWortAb()
                    feld.schreibe(" ")
                }
            }
        }
        letzteKorrektur = korrigiert?.let { Korrigiert(it.original, it.ersetzt, " ") }
        automatischesLeer = false
        spur.clear()
        letztesLeer = jetzt
    }

    /** Vor Enter: das Wort abschließen (korrigieren und lernen). */
    fun wortAbschliessen() {
        schliesseWortAb()
        automatischesLeer = false
        letzteKorrektur = null
        spur.clear()
    }

    /** ⌫: direkt nach einer Autokorrektur holt es das Getippte zurück, sonst löscht es ein Zeichen. */
    fun loesche() {
        automatischesLeer = false
        val k = letzteKorrektur
        letzteKorrektur = null
        if (k != null) {
            val erwartet = k.ersetzt + k.trenner
            if (feld.vorCursor(erwartet.length).toString() == erwartet) {
                feld.gruppe {
                    feld.loescheVorCursor(erwartet.length)
                    feld.schreibe(k.original + k.trenner)
                }
                // Der Nutzer will es so: nie wieder ändern (auch nicht gross/klein)
                if (lernen) woerterbuch().bestaetige(k.original)
                spur.clear()
                return
            }
        }
        // Ein Buchstabe des aktuellen Wortes fällt weg -- seine Berührungsstelle auch
        if (spur.isNotEmpty()) spur.removeAt(spur.size - 1)
        feld.loescheTaste()
    }

    /** Text von außen eingefügt (Emoji, Zwischenablage, Schreibhilfe ...): das Wort ist damit zu Ende. */
    fun schreibeText(text: String) {
        feld.schreibe(text)
        zuruecksetzen()
    }

    /** Der Cursor wurde bewegt, das Feld gewechselt o. Ä.: nichts vom aktuellen Wort gilt mehr. */
    fun zuruecksetzen() {
        automatischesLeer = false
        letzteKorrektur = null
        spur.clear()
    }

    // ================================================================ Vorschläge

    /** Die Vorschlagsleiste für den Text vor dem Cursor, oder null, wenn es nichts anzuzeigen gibt. */
    fun leiste(anzahl: Int = 3): Leiste? {
        val vor = feld.vorCursor(80)
        val wort = TextLogik.aktuellesWort(vor)
        val ergebnis = woerterbuch().vorschlaege(wort, TextLogik.kontext(vor), spurFuer(wort), anzahl)
        if (ergebnis.liste.isEmpty()) return null
        val korrektur = if (wort.isNotEmpty() && darfKorrigieren(vor, wort)) ergebnis.korrektur else null
        val hervorgehoben = if (korrektur != null) ergebnis.liste.indexOf(korrektur) else 0
        return Leiste(wort, ergebnis.liste, hervorgehoben, wort.isNotEmpty() && !woerterbuch().kennt(wort))
    }

    /**
     * Ein Vorschlag wurde angetippt. [buchstaeblich]: es war das Getippte selbst (ganz links).
     * Setzt Leerzeichen dahinter, damit gleich weitergetippt werden kann.
     */
    fun waehle(vorschlag: String, getippt: String, buchstaeblich: Boolean) {
        val vor = feld.vorCursor(80)
        val kontext = TextLogik.kontext(vor)
        val warKorrektur = buchstaeblich && woerterbuch().korrektur(getippt, kontext, spurFuer(getippt)) != null
        feld.gruppe {
            if (getippt.isNotEmpty()) feld.loescheVorCursor(getippt.length)
            feld.schreibe("$vorschlag ")
        }
        if (lernen) {
            // Das Getippte bewusst angetippt, obwohl eine Korrektur bereitstand: nie wieder ändern
            if (warKorrektur) woerterbuch().bestaetige(getippt) else lerneWoerter(vorschlag, kontext.wort)
        }
        automatischesLeer = true
        letzteKorrektur = null
        spur.clear()
    }
}
