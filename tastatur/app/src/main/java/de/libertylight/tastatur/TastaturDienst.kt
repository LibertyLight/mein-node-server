package de.libertylight.tastatur

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Der Eingabedienst: verbindet Tastenfeld, Leiste und Zusatzflaechen mit dem Textfeld der App.
 */
class TastaturDienst : InputMethodService(), TastenfeldView.Zuhoerer {

    /** Eine Autokorrektur, die ein sofortiges ⌫ wieder rueckgaengig macht. */
    private data class Korrigiert(val original: String, val ersetzt: String, val trenner: String = "")

    /** Text, den die Schreibhilfe bearbeitet: die Markierung oder das ganze Feld. */
    data class Quelle(val text: String, val markiert: Boolean)

    lateinit var einstellungen: Einstellungen
        private set
    lateinit var ki: KiClient
        private set
    private lateinit var woerterbuch: Woerterbuch
    private lateinit var b: Bausteine
    private lateinit var tastenfeld: TastenfeldView
    private lateinit var inhalt: FrameLayout
    private lateinit var werkzeuge: View
    private lateinit var vorschlagLeiste: LinearLayout
    private lateinit var statusLeiste: View
    private lateinit var statusText: TextView

    private var stand = -1
    private var leistenHoehe = 0
    private var woerterLoeschen = 0
    private var seite = Seite.BUCHSTABEN
    private var tastenfeldSichtbar = true
    private var letzteShiftZeit = 0L
    private var letztesLeer = 0L
    private var automatischesLeer = false
    private var letzteKorrektur: Korrigiert? = null
    private var werkzeugeErzwungen = false
    private var geschuetzt = false
    private var keinLernen = false
    private var keineVorschlaege = false
    private var erkenner: SpeechRecognizer? = null
    private val haupt = Handler(Looper.getMainLooper())

    private val ablage by lazy { getSystemService(ClipboardManager::class.java) }
    private val ablageBeobachter = ClipboardManager.OnPrimaryClipChangedListener { merkeAblage() }

    // ================================================================ Lebenszyklus

    override fun onCreate() {
        super.onCreate()
        einstellungen = Einstellungen(this)
        ki = KiClient(einstellungen)
        // 65.000 Woerter zu laden dauert einen Moment -- im Hintergrund, damit die Tastatur
        // sofort erscheint. Bis dahin ein leeres Woerterbuch, das nichts speichert (sonst
        // wuerde es die Datei mit den gelernten Woertern ueberschreiben).
        woerterbuch = Woerterbuch(emptyList(), object : Woerterbuch.Speicher {
            override fun lies(): String? = null
            override fun schreib(inhalt: String) {}
        })
        Thread {
            val grund = resources.openRawResource(R.raw.woerter_de).bufferedReader().use { it.readLines() }
            val datei = File(filesDir, "gelernt.tsv")
            val geladen = Woerterbuch(grund, object : Woerterbuch.Speicher {
                override fun lies() = if (datei.exists()) datei.readText() else null
                override fun schreib(inhalt: String) = datei.writeText(inhalt)
            })
            haupt.post { woerterbuch = geladen }
        }.start()
        woerterLoeschen = einstellungen.woerterLoeschen
        ablage?.addPrimaryClipChangedListener(ablageBeobachter)
    }

    override fun onDestroy() {
        woerterbuch.speichere()
        ablage?.removePrimaryClipChangedListener(ablageBeobachter)
        erkenner?.destroy()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = baueAnsicht()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (einstellungen.stand != stand) setInputView(baueAnsicht())
        if (einstellungen.woerterLoeschen != woerterLoeschen) {
            woerterLoeschen = einstellungen.woerterLoeschen
            woerterbuch.vergissAlles()
        }

        val klasse = info.inputType and InputType.TYPE_MASK_CLASS
        val variante = info.inputType and InputType.TYPE_MASK_VARIATION
        geschuetzt = when (klasse) {
            InputType.TYPE_CLASS_TEXT -> variante == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variante == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variante == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variante == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        keinLernen = geschuetzt || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        keineVorschlaege = geschuetzt || klasse != InputType.TYPE_CLASS_TEXT ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            variante == InputType.TYPE_TEXT_VARIATION_URI ||
            variante == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variante == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS

        seite = when (klasse) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Seite.ZIFFERN
            InputType.TYPE_CLASS_PHONE -> Seite.TELEFON
            else -> Seite.BUCHSTABEN
        }
        tastenfeld.enterArt = enterArtFuer(info)
        if (!restarting) tastenfeld.umschalt = Umschalt.AUS
        automatischesLeer = false
        letzteKorrektur = null
        werkzeugeErzwungen = false
        zeigeTastatur()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        woerterbuch.speichere()
        stoppeStimme()
        ki.abbrechen()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Kann eintreffen, bevor die Ansicht gebaut ist
        if (::tastenfeld.isInitialized && tastenfeldSichtbar) {
            autoUmschalt()
            aktualisiereVorschlaege()
        }
    }

    // ================================================================ Aufbau

    private fun baueAnsicht(): View {
        stand = einstellungen.stand
        val thema = if (einstellungen.istDunkel(this)) Thema.DUNKEL else Thema.HELL
        b = Bausteine(this, thema)
        // Referenz-Screenshot: Leiste 149 px hoch bei 1080 px Breite, Schrift darin etwa 45 % der Hoehe
        val schirmBreite = resources.displayMetrics.widthPixels
        leistenHoehe = (0.139f * schirmBreite).coerceIn(b.dp(44).toFloat(), b.dp(60).toFloat()).toInt()
        tastenfeld = TastenfeldView(this, this).apply {
            this.thema = thema
            hoehenFaktor = einstellungen.hoehe / 100f
            vibration = einstellungen.vibration
            schriftFaktor = einstellungen.schrift / 100f
            grossBeschriftung = einstellungen.grossBeschriftung
        }
        inhalt = FrameLayout(this)
        inhalt.addView(tastenfeld)

        val spalte = b.senkrecht().apply {
            setBackgroundColor(thema.hintergrund)
            addView(baueLeiste(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, leistenHoehe))
            addView(inhalt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val einhand = einstellungen.einhand
        if (einhand == 0) return spalte

        // Einhandmodus: Tastatur auf 80 % Breite, daneben Knoepfe zum Wechseln und Beenden.
        val seitenleiste = b.senkrecht().apply {
            gravity = Gravity.CENTER
            addView(b.symbol(if (einhand < 0) "▶" else "◀", "Seite wechseln") { einstellungen.einhand = -einhand; neuAufbauen() },
                b.abstand(breite = b.dp(48), hoehe = b.dp(56)))
            addView(b.symbol("⤢", "Einhandmodus beenden") { einstellungen.einhand = 0; neuAufbauen() },
                b.abstand(breite = b.dp(48), hoehe = b.dp(56)))
        }
        return b.waagerecht().apply {
            setBackgroundColor(thema.hintergrund)
            gravity = Gravity.BOTTOM
            val breit = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.82f)
            val schmal = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.18f)
            if (einhand < 0) { addView(spalte, breit); addView(seitenleiste, schmal) }
            else { addView(seitenleiste, schmal); addView(spalte, breit) }
        }
    }

    private fun neuAufbauen() {
        setInputView(baueAnsicht())
        zeigeTastatur()
    }

    private fun baueLeiste(): View {
        val rahmen = FrameLayout(this)

        werkzeuge = b.waagerecht().apply {
            val eintraege = listOf(
                "✨" to "Schreibhilfe" to { zeigeFlaeche(KiFlaeche(this@TastaturDienst, b).baue()) },
                "😊" to "Emojis" to { zeigeFlaeche(EmojiFlaeche(this@TastaturDienst, b).baue()) },
                "📋" to "Zwischenablage" to { zeigeFlaeche(AblageFlaeche(this@TastaturDienst, b).baue()) },
                "⌶" to "Text bearbeiten" to { zeigeFlaeche(BearbeitenFlaeche(this@TastaturDienst, b).baue()) },
                "🎤" to "Spracheingabe" to { starteStimme() },
                "◧" to "Einhandmodus" to { einstellungen.einhand = if (einstellungen.einhand == 0) 1 else 0; neuAufbauen() },
                "⚙️" to "Einstellungen" to { oeffneEinstellungen() },
                "⌄" to "Tastatur ausblenden" to { requestHideSelf(0) },
            )
            for ((paar, klick) in eintraege) {
                addView(b.symbolPx(paar.first, paar.second, 0.4f * leistenHoehe, klick), b.abstand(breite = 0, hoehe = ViewGroup.LayoutParams.MATCH_PARENT, gewicht = 1f))
            }
        }

        vorschlagLeiste = b.waagerecht()

        statusText = b.beschriftung("", 14f, fett = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        statusLeiste = b.waagerecht().apply {
            setPadding(b.dp(12), 0, b.dp(4), 0)
            addView(statusText, b.abstand(breite = 0, hoehe = ViewGroup.LayoutParams.MATCH_PARENT, gewicht = 1f))
            addView(b.symbol("■", "Spracheingabe beenden", 18f) { stoppeStimme() }, b.abstand(breite = b.dp(44), hoehe = ViewGroup.LayoutParams.MATCH_PARENT))
            visibility = View.GONE
        }

        rahmen.addView(werkzeuge)
        rahmen.addView(vorschlagLeiste)
        rahmen.addView(statusLeiste)
        return rahmen
    }

    private fun zeigeLeiste(welche: View) {
        for (v in listOf(werkzeuge, vorschlagLeiste, statusLeiste)) v.visibility = if (v === welche) View.VISIBLE else View.GONE
    }

    private fun flaechenHoehe(): Int = maxOf(tastenfeld.height, b.dp(4 * 66))

    private fun zeigeFlaeche(flaeche: View) {
        val hoehe = flaechenHoehe()
        tastenfeldSichtbar = false
        inhalt.removeAllViews()
        inhalt.addView(flaeche, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hoehe))
        zeigeLeiste(werkzeuge)
    }

    fun zeigeTastatur() {
        tastenfeld.reihen = Belegung.reihen(seite, einstellungen.optionen)
        if (!tastenfeldSichtbar || tastenfeld.parent == null) {
            inhalt.removeAllViews()
            inhalt.addView(tastenfeld)
            tastenfeldSichtbar = true
        }
        autoUmschalt()
        aktualisiereVorschlaege()
    }

    // ================================================================ Vorschlaege

    private fun aktualisiereVorschlaege() {
        if (statusLeiste.visibility == View.VISIBLE && erkenner != null && statusText.tag == "stimme") return
        val ic = currentInputConnection
        if (ic == null || keineVorschlaege || werkzeugeErzwungen || !einstellungen.vorschlaege || seite != Seite.BUCHSTABEN) {
            zeigeLeiste(werkzeuge)
            return
        }
        val vor = ic.getTextBeforeCursor(80, 0) ?: ""
        val wort = TextLogik.aktuellesWort(vor)
        val vorher = TextLogik.vorherigesWort(vor)
        val liste = woerterbuch.vorschlaege(wort, vorher)
        if (liste.isEmpty()) {
            zeigeLeiste(werkzeuge)
            return
        }

        vorschlagLeiste.removeAllViews()
        vorschlagLeiste.addView(b.symbol("✨", "Schreibhilfe", 18f) { zeigeFlaeche(KiFlaeche(this, b).baue()) },
            b.abstand(breite = b.dp(44), hoehe = ViewGroup.LayoutParams.MATCH_PARENT))
        // Fett: was die Leertaste einsetzen wird -- die Autokorrektur oder sonst das Getippte selbst.
        val korrektur = if (wort.isNotEmpty() && darfKorrigieren(vor, wort)) woerterbuch.korrektur(wort, vorher) else null
        val hervorheben = when {
            korrektur != null -> liste.indexOf(korrektur)
            wort.isEmpty() -> 0
            else -> -1
        }
        val groesse = 17f * (einstellungen.schrift / 100f).coerceIn(0.9f, 1.3f)
        liste.forEachIndexed { i, vorschlag ->
            if (i > 0) vorschlagLeiste.addView(View(this).apply { setBackgroundColor(b.thema.textLeise) },
                b.abstand(breite = 1, hoehe = b.dp(20)))
            vorschlagLeiste.addView(TextView(this).apply {
                text = vorschlag
                textSize = groesse
                gravity = Gravity.CENTER
                isSingleLine = true
                setTextColor(b.thema.text)
                if (i == hervorheben) typeface = Typeface.DEFAULT_BOLD
                setOnClickListener { waehle(vorschlag, wort) }
                setOnLongClickListener {
                    if (i > 0 || wort.isEmpty()) { woerterbuch.vergiss(vorschlag); aktualisiereVorschlaege() }
                    true
                }
            }, b.abstand(breite = 0, hoehe = ViewGroup.LayoutParams.MATCH_PARENT, gewicht = 1f))
        }
        vorschlagLeiste.addView(b.symbol("⋯", "Werkzeuge", 18f) { werkzeugeErzwungen = true; zeigeLeiste(werkzeuge) },
            b.abstand(breite = b.dp(44), hoehe = ViewGroup.LayoutParams.MATCH_PARENT))
        zeigeLeiste(vorschlagLeiste)
    }

    private fun waehle(vorschlag: String, getippt: String) {
        val ic = currentInputConnection ?: return
        val vor = ic.getTextBeforeCursor(80, 0) ?: ""
        val vorher = TextLogik.vorherigesWort(vor)
        ic.beginBatchEdit()
        if (getippt.isNotEmpty()) ic.deleteSurroundingText(getippt.length, 0)
        ic.commitText("$vorschlag ", 1)
        ic.endBatchEdit()
        // Das Getippte bewusst angetippt: merken, damit es nicht mehr korrigiert wird
        if (!keinLernen) woerterbuch.lerne(vorschlag, vorher, if (vorschlag == getippt) 2 else 1)
        automatischesLeer = true
        letzteKorrektur = null
        if (tastenfeld.umschalt == Umschalt.EINMAL) tastenfeld.umschalt = Umschalt.AUS
    }

    /** Autokorrektur nur in normalen Textfeldern -- nicht in Adressen, Handles, Passwoertern. */
    private fun darfKorrigieren(vor: CharSequence, wort: String): Boolean {
        if (!einstellungen.autokorrektur || keineVorschlaege || seite != Seite.BUCHSTABEN) return false
        val davor = vor.getOrNull(vor.length - wort.length - 1)
        return davor == null || davor !in "@#/\\._:"
    }

    /**
     * Wird vor einem Leer- oder Satzzeichen aufgerufen: korrigiert das gerade beendete Wort
     * (falls noetig) und lernt es. Liefert die Korrektur, damit ⌫ sie zuruecknehmen kann.
     */
    private fun schliesseWortAb(): Korrigiert? {
        val ic = currentInputConnection ?: return null
        val vor = ic.getTextBeforeCursor(80, 0) ?: return null
        val wort = TextLogik.aktuellesWort(vor)
        if (wort.isEmpty()) return null
        val vorher = TextLogik.vorherigesWort(vor)
        val korrektur = if (darfKorrigieren(vor, wort)) woerterbuch.korrektur(wort, vorher) else null
        if (korrektur == null) {
            if (!keinLernen) woerterbuch.lerne(wort, vorher)
            return null
        }
        ic.deleteSurroundingText(wort.length, 0)
        ic.commitText(korrektur, 1)
        if (!keinLernen) woerterbuch.lerne(korrektur, vorher)
        return Korrigiert(wort, korrektur)
    }

    // ================================================================ Tasten

    override fun zeichen(text: String, anschlag: Anschlag?) {
        val ic = currentInputConnection ?: return
        val t = if (tastenfeld.umschalt != Umschalt.AUS && text.length == 1) TextLogik.gross(text) else text
        letzteKorrektur = null
        // Satzzeichen beenden ein Wort; Apostroph und Bindestrich gehoeren zum Wort ("geht's", "E-Mail")
        val trennt = !t[0].isLetterOrDigit() && t[0] != '\'' && t[0] != '-'

        if (automatischesLeer && TextLogik.schliesstAn(t) && ic.getTextBeforeCursor(1, 0) == " ") {
            // "Hallo " + "," wird zu "Hallo, "
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText("$t ", 1)
            ic.endBatchEdit()
        } else if (trennt) {
            ic.beginBatchEdit()
            val korrigiert = schliesseWortAb()
            ic.commitText(t, 1)
            ic.endBatchEdit()
            letzteKorrektur = korrigiert?.copy(trenner = t)
        } else {
            ic.commitText(t, 1)
        }
        automatischesLeer = false
        werkzeugeErzwungen = false
        if (tastenfeld.umschalt == Umschalt.EINMAL) tastenfeld.umschalt = Umschalt.AUS
    }

    override fun aktion(art: Art) {
        when (art) {
            Art.SHIFT -> {
                val jetzt = SystemClock.uptimeMillis()
                tastenfeld.umschalt = when (tastenfeld.umschalt) {
                    Umschalt.AUS -> Umschalt.EINMAL
                    Umschalt.EINMAL -> if (jetzt - letzteShiftZeit < 400) Umschalt.FEST else Umschalt.AUS
                    Umschalt.FEST -> Umschalt.AUS
                }
                letzteShiftZeit = jetzt
            }
            Art.LOESCHEN -> loesche()
            Art.LEER -> leer()
            Art.ENTER -> enter()
            Art.SYMBOLE -> { seite = Seite.SYMBOLE; zeigeTastatur() }
            Art.SYMBOLE2 -> { seite = Seite.SYMBOLE2; zeigeTastatur() }
            Art.BUCHSTABEN -> { seite = Seite.BUCHSTABEN; zeigeTastatur() }
            Art.EMOJI -> zeigeFlaeche(EmojiFlaeche(this, b).baue())
            Art.ZEICHEN, Art.ABSTAND -> {}
        }
    }

    private fun leer() {
        val ic = currentInputConnection ?: return
        val jetzt = SystemClock.uptimeMillis()
        val vor = ic.getTextBeforeCursor(2, 0) ?: ""
        val korrigiert: Korrigiert?
        when {
            einstellungen.doppelLeerPunkt && jetzt - letztesLeer < 600 && TextLogik.doppelLeerzeichenPunkt(vor) -> {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                korrigiert = null
            }
            // Nach einem gewaehlten Vorschlag steht das Leerzeichen schon da.
            automatischesLeer -> korrigiert = null
            else -> {
                ic.beginBatchEdit()
                korrigiert = schliesseWortAb()
                ic.commitText(" ", 1)
                ic.endBatchEdit()
            }
        }
        letzteKorrektur = korrigiert?.copy(trenner = " ")
        automatischesLeer = false
        letztesLeer = jetzt
        if (seite == Seite.SYMBOLE || seite == Seite.SYMBOLE2) { seite = Seite.BUCHSTABEN; zeigeTastatur() }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        schliesseWortAb()
        automatischesLeer = false
        letzteKorrektur = null
        val info = currentInputEditorInfo
        val aktion = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val mehrzeilig = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        when {
            (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
                aktion != EditorInfo.IME_ACTION_NONE && aktion != EditorInfo.IME_ACTION_UNSPECIFIED ->
                ic.performEditorAction(aktion)
            mehrzeilig -> ic.commitText("\n", 1)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun enterArtFuer(info: EditorInfo): EnterArt {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EnterArt.ZEILE
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> EnterArt.SENDEN
            EditorInfo.IME_ACTION_SEARCH -> EnterArt.SUCHEN
            EditorInfo.IME_ACTION_GO -> EnterArt.LOS
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS -> EnterArt.WEITER
            EditorInfo.IME_ACTION_DONE -> EnterArt.FERTIG
            else -> EnterArt.ZEILE
        }
    }

    private fun autoUmschalt() {
        if (tastenfeld.umschalt == Umschalt.FEST || seite != Seite.BUCHSTABEN) return
        val info = currentInputEditorInfo
        val gross = einstellungen.autoGross && info != null && info.inputType != 0 &&
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        tastenfeld.umschalt = if (gross) Umschalt.EINMAL else Umschalt.AUS
    }

    override fun cursor(schritte: Int) {
        letzteKorrektur = null
        val code = if (schritte < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(schritte)) { sendDownUpKeyEvents(code) }
    }

    override fun leertasteLang() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun rueckmeldung() {
        if (einstellungen.tastenton) getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    // ================================================================ Fuer die Flaechen

    fun schreibe(text: String) {
        currentInputConnection?.commitText(text, 1)
        automatischesLeer = false
        letzteKorrektur = null
    }

    /** Loescht ein Zeichen oder die Markierung. Ueber KEYCODE_DEL, damit Emojis ganz verschwinden. */
    fun loesche() {
        automatischesLeer = false
        val k = letzteKorrektur
        letzteKorrektur = null
        val ic = currentInputConnection
        if (k != null && ic != null) {
            // ⌫ direkt nach einer Autokorrektur: das Getippte zurueckholen und es sich merken
            val erwartet = k.ersetzt + k.trenner
            if (ic.getTextBeforeCursor(erwartet.length, 0)?.toString() == erwartet) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(erwartet.length, 0)
                ic.commitText(k.original + k.trenner, 1)
                ic.endBatchEdit()
                if (!keinLernen) woerterbuch.lerne(k.original, "", 2)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    fun taste(code: Int, umschalt: Boolean, strg: Boolean = false) {
        val ic = currentInputConnection ?: return
        var meta = 0
        if (umschalt) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (strg) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val zeit = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(zeit, zeit, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(zeit, zeit, KeyEvent.ACTION_UP, code, 0, meta))
    }

    fun kontextAktion(id: Int) {
        currentInputConnection?.performContextMenuAction(id)
    }

    fun istGeschuetzt() = geschuetzt

    fun holeText(): Quelle {
        val ic = currentInputConnection ?: return Quelle("", false)
        val markiert = ic.getSelectedText(0)
        if (!markiert.isNullOrEmpty()) return Quelle(markiert.toString(), true)
        val vor = ic.getTextBeforeCursor(MAX_TEXT, 0) ?: ""
        val nach = ic.getTextAfterCursor(MAX_TEXT, 0) ?: ""
        return Quelle("$vor$nach", false)
    }

    /** Ersetzt die Markierung -- oder den ganzen gelesenen Text -- durch [neu]. */
    fun ersetze(quelle: Quelle, neu: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (!quelle.markiert) {
            val vor = ic.getTextBeforeCursor(MAX_TEXT, 0) ?: ""
            val nach = ic.getTextAfterCursor(MAX_TEXT, 0) ?: ""
            ic.deleteSurroundingText(vor.length, nach.length)
        }
        ic.commitText(neu, 1)
        ic.endBatchEdit()
    }

    fun kopiere(text: String) {
        ablage?.setPrimaryClip(ClipData.newPlainText("Tastatur+", text))
        zeigeMeldung("In die Zwischenablage kopiert")
    }

    // ================================================================ Zwischenablage

    private fun merkeAblage() {
        if (geschuetzt) return
        val clip = ablage?.primaryClip ?: return
        // Als vertraulich markierte Inhalte (z. B. aus Passwort-Managern) nicht speichern.
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.take(4000) ?: return
        if (text.isBlank()) return
        einstellungen.zwischenablage = listOf(text) + einstellungen.zwischenablage.filter { it != text }
    }

    // ================================================================ Meldungen & Sprache

    private fun zeigeMeldung(text: String, dauer: Long = 2500) {
        statusText.text = text
        statusText.tag = "meldung"
        zeigeLeiste(statusLeiste)
        haupt.removeCallbacksAndMessages("meldung")
        haupt.postAtTime({ if (statusText.tag == "meldung") { statusText.tag = null; aktualisiereVorschlaege() } },
            "meldung", SystemClock.uptimeMillis() + dauer)
    }

    private fun oeffneEinstellungen() {
        startActivity(Intent(this, EinstellungenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun starteStimme() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            zeigeMeldung("Mikrofon-Berechtigung fehlt – bitte in den Einstellungen erteilen.", 4000)
            oeffneEinstellungen()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            zeigeMeldung("Auf dem Gerät ist keine Spracherkennung installiert (z. B. die Google-App).", 4000)
            return
        }
        stoppeStimme()
        val e = SpeechRecognizer.createSpeechRecognizer(this)
        erkenner = e
        statusText.tag = "stimme"
        statusText.text = "🎤 Einen Moment …"
        zeigeLeiste(statusLeiste)

        e.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { statusText.text = "🎤 Sprich jetzt …" }
            override fun onPartialResults(teil: Bundle?) {
                teil?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { statusText.text = "🎤 $it" }
            }
            override fun onResults(ergebnis: Bundle?) {
                val text = ergebnis?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                stoppeStimme()
                if (text.isNotBlank()) diktiert(text)
            }
            override fun onError(fehler: Int) {
                stoppeStimme()
                zeigeMeldung(when (fehler) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts verstanden – bitte noch einmal."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Spracherkennung braucht gerade Internet."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt."
                    else -> "Spracherkennung fehlgeschlagen ($fehler)."
                })
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { statusText.text = "🎤 Wird erkannt …" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        e.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun diktiert(text: String) {
        val ic = currentInputConnection ?: return
        val vor = ic.getTextBeforeCursor(1, 0) ?: ""
        var t = if (tastenfeld.umschalt != Umschalt.AUS) text.replaceFirstChar { it.uppercaseChar() } else text
        if (vor.isNotEmpty() && !vor[0].isWhitespace()) t = " $t"
        ic.commitText(t, 1)
    }

    private fun stoppeStimme() {
        if (!::statusText.isInitialized) return
        erkenner?.let {
            it.cancel()
            it.destroy()
        }
        erkenner = null
        if (statusText.tag == "stimme") {
            statusText.tag = null
            if (tastenfeldSichtbar) aktualisiereVorschlaege() else zeigeLeiste(werkzeuge)
        }
    }

    companion object {
        /** Passt zur Grenze des Servers (TASTATUR_MAX_ZEICHEN). */
        const val MAX_TEXT = 8000
    }
}
