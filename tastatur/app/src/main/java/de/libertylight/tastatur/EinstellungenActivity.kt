package de.libertylight.tastatur

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * Einrichtung und Einstellungen. Ohne XML-Layouts, damit alles an einer Stelle steht.
 */
class EinstellungenActivity : Activity() {

    private lateinit var e: Einstellungen
    private lateinit var status: TextView
    private lateinit var liste: LinearLayout
    private val dichte by lazy { resources.displayMetrics.density }
    private fun dp(wert: Int) = (wert * dichte).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        e = Einstellungen(this)
        liste = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(liste)
        })
        baue()
    }

    override fun onResume() {
        super.onResume()
        aktualisiereStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Nach der Auswahl im System-Dialog den Status sofort auffrischen
        if (hasFocus) aktualisiereStatus()
    }

    private fun text(inhalt: String, groesse: Float = 15f, fett: Boolean = false) = TextView(this).apply {
        text = inhalt
        textSize = groesse
        if (fett) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun ueberschrift(inhalt: String) = liste.addView(text(inhalt, 18f, fett = true).apply { setPadding(0, dp(22), 0, dp(4)) })

    private fun knopf(inhalt: String, klick: () -> Unit) = liste.addView(android.widget.Button(this).apply {
        text = inhalt
        isAllCaps = false
        setOnClickListener { klick() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    @Suppress("DEPRECATION") // android.widget.Switch reicht hier; Material-Bibliothek waere Ballast
    private fun schalter(inhalt: String, wert: Boolean, setze: (Boolean) -> Unit) = liste.addView(Switch(this).apply {
        text = inhalt
        textSize = 15f
        isChecked = wert
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, an -> setze(an) }
    })

    private fun eingabe(hinweis: String, wert: String, geheim: Boolean = false): EditText = EditText(this).apply {
        hint = hinweis
        setText(wert)
        isSingleLine = true
        inputType = if (geheim) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        liste.addView(this)
    }

    private fun baue() {
        liste.addView(text("Tastatur+", 26f, fett = true))
        liste.addView(text("Deutsche QWERTZ-Tastatur mit Wortvorschlägen, Emojis, Zwischenablage, Spracheingabe und einer KI-Schreibhilfe nach dem Vorbild von Galaxy AI."))

        ueberschrift("1 · Einrichten")
        status = text("")
        liste.addView(status)
        knopf("Tastatur in den Android-Einstellungen aktivieren") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        knopf("Als aktuelle Tastatur auswählen") {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        knopf("Mikrofon für Spracheingabe erlauben") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        ueberschrift("2 · Schreibhilfe (Claude)")
        liste.addView(text("Die KI-Funktionen laufen über deinen Node-Server. Der API-Schlüssel bleibt dort; hier steht nur das Zugangswort aus TASTATUR_TOKEN. Läuft der Server in Termux auf diesem Handy, passt die Vorgabe http://127.0.0.1:3000.", 13f))
        val url = eingabe("Server-Adresse", e.serverUrl)
        val token = eingabe("Zugangswort (TASTATUR_TOKEN)", e.token, geheim = true)
        val ergebnis = text("", 13f)
        knopf("Speichern & Verbindung testen") {
            e.serverUrl = url.text.toString()
            e.token = token.text.toString()
            ergebnis.text = "Prüfe …"
            KiClient(e).pruefeVerbindung { antwort ->
                ergebnis.text = when (antwort) {
                    is KiClient.Ergebnis.Text -> "✅ ${antwort.text}"
                    is KiClient.Ergebnis.Fehler -> "⚠️ ${antwort.meldung}"
                }
            }
        }
        liste.addView(ergebnis)

        ueberschrift("3 · Tippen")
        schalter("Zahlenreihe anzeigen", e.zahlenreihe) { e.zahlenreihe = it }
        schalter("Wortvorschläge", e.vorschlaege) { e.vorschlaege = it }
        schalter("Automatische Großschreibung", e.autoGross) { e.autoGross = it }
        schalter("Doppelte Leertaste setzt Punkt", e.doppelLeerPunkt) { e.doppelLeerPunkt = it }
        schalter("Vibration beim Tippen", e.vibration) { e.vibration = it }
        schalter("Tastenton", e.tastenton) { e.tastenton = it }

        ueberschrift("4 · Aussehen")
        val hoeheText = text("Höhe: ${e.hoehe} %")
        liste.addView(hoeheText)
        liste.addView(SeekBar(this).apply {
            max = 70
            progress = e.hoehe - 70
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, wert: Int, vomNutzer: Boolean) { hoeheText.text = "Höhe: ${wert + 70} %" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { e.hoehe = progress + 70 }
            })
        })
        val themen = listOf("system" to "Wie das System", "hell" to "Hell", "dunkel" to "Dunkel")
        liste.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            themen.forEachIndexed { i, (wert, titel) ->
                addView(RadioButton(this@EinstellungenActivity).apply {
                    id = View.generateViewId()
                    text = titel
                    isChecked = e.thema == wert
                    setOnClickListener { e.thema = wert }
                    if (i < themen.size - 1) setPadding(0, 0, dp(12), 0)
                })
            }
        })
        schalter("Einhandmodus", e.einhand != 0) { e.einhand = if (it) 1 else 0 }

        ueberschrift("5 · Datenschutz")
        liste.addView(text("Gelernte Wörter und die Zwischenablage bleiben auf dem Gerät. In Passwortfeldern lernt die Tastatur nichts, speichert nichts aus der Zwischenablage und die Schreibhilfe ist aus. Text geht nur dann an deinen Server (und von dort an Claude), wenn du in der Schreibhilfe eine Aktion antippst.", 13f))
        knopf("Gelernte Wörter löschen") {
            File(filesDir, "gelernt.tsv").delete()
            e.woerterLoeschen += 1 // die laufende Tastatur vergisst dann auch ihre Kopie im Speicher
            android.widget.Toast.makeText(this, "Gelernte Wörter gelöscht", android.widget.Toast.LENGTH_SHORT).show()
        }
        knopf("Zwischenablage-Verlauf löschen") {
            e.zwischenablage = emptyList()
            android.widget.Toast.makeText(this, "Verlauf gelöscht", android.widget.Toast.LENGTH_SHORT).show()
        }

        ueberschrift("Ausprobieren")
        liste.addView(EditText(this).apply {
            hint = "Hier tippen …"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        })
    }

    private fun aktualisiereStatus() {
        if (!::status.isInitialized) return
        val imm = getSystemService(InputMethodManager::class.java)
        val aktiviert = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val gewaehlt = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val mikro = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = listOf(
            (if (aktiviert) "✅" else "⬜") + " Tastatur aktiviert",
            (if (gewaehlt) "✅" else "⬜") + " Als aktuelle Tastatur gewählt",
            (if (mikro) "✅" else "⬜") + " Mikrofon erlaubt (für Spracheingabe)",
        ).joinToString("\n")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        aktualisiereStatus()
    }
}
