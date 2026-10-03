package de.libertylight.tastatur

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Die Schreibhilfe -- das Gegenstueck zu Galaxy AI auf der Samsung-Tastatur:
 * Ton aendern, Rechtschreibung, Zusammenfassen, Stichpunkte, Uebersetzen und
 * Verfassen. Bearbeitet wird der markierte Text oder, ohne Markierung, der
 * ganze Text im Eingabefeld. Das Ergebnis erscheint erst als Vorschau.
 */
class KiFlaeche(private val dienst: TastaturDienst, private val b: Bausteine) {

    private data class Auftrag(val titel: String, val parameter: Map<String, String>)

    private val auswahl = b.senkrecht()
    private val ergebnisBereich = b.senkrecht()
    private val ergebnisTitel = b.beschriftung("", 13f, fett = true)
    private val ergebnisText = TextView(dienst).apply {
        textSize = 15f
        setTextColor(b.thema.text)
        setTextIsSelectable(false)
    }
    private val knopfLeiste = b.waagerecht()
    private var letzterAuftrag: Auftrag? = null
    private var quelle = TastaturDienst.Quelle("", markiert = false)
    private var ergebnis: String? = null

    companion object {
        val TOENE = listOf(
            "professionell" to "💼 Professionell",
            "locker" to "😎 Locker",
            "hoeflich" to "🙏 Höflich",
            "social" to "📣 Social Media",
            "emoji" to "😊 Mit Emojis",
        )
        val SPRACHEN = listOf(
            "en" to "🇬🇧 Englisch", "de" to "🇩🇪 Deutsch", "tr" to "🇹🇷 Türkisch", "pl" to "🇵🇱 Polnisch",
            "uk" to "🇺🇦 Ukrainisch", "ru" to "🇷🇺 Russisch", "ar" to "🇸🇦 Arabisch", "es" to "🇪🇸 Spanisch",
            "fr" to "🇫🇷 Französisch", "it" to "🇮🇹 Italienisch", "nl" to "🇳🇱 Niederländisch", "da" to "🇩🇰 Dänisch",
        )
        val ARTEN = listOf("nachricht" to "Nachricht", "email" to "E-Mail", "post" to "Social-Post")
    }

    fun baue(): View {
        quelle = dienst.holeText()
        baueAuswahl()
        baueErgebnis()
        ergebnisBereich.visibility = View.GONE

        return b.senkrecht().apply {
            addView(ScrollView(dienst).apply { addView(auswahl) },
                b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f))
            addView(ergebnisBereich, b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f))
        }.also { ergebnisBereich.visibility = View.GONE }
    }

    private fun abschnitt(titel: String) =
        auswahl.addView(b.beschriftung(titel, 12f), b.abstand(links = 12, oben = 8, unten = 4))

    private fun reihe(vararg chips: View) =
        auswahl.addView(b.chipReihe(*chips).apply { setPadding(b.dp(8), 0, b.dp(8), 0) },
            b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = b.dp(40)))

    private fun baueAuswahl() {
        val kopf = b.waagerecht().apply {
            setPadding(b.dp(12), b.dp(8), b.dp(8), 0)
            addView(b.beschriftung("✨ Schreibhilfe", 16f, fett = true), b.abstand(gewicht = 1f, breite = 0))
            addView(b.knopf("ABC", 13f) { dienst.zeigeTastatur() })
        }
        auswahl.addView(kopf)

        if (dienst.istGeschuetzt()) {
            auswahl.addView(hinweis("In Passwort- und privaten Feldern ist die Schreibhilfe aus – dein Text verlässt das Gerät hier nicht."))
            return
        }
        if (quelle.text.isBlank()) {
            auswahl.addView(hinweis("Schreib zuerst etwas oder markiere Text. Dann kann die Schreibhilfe ihn umformulieren, " +
                "korrigieren, zusammenfassen oder übersetzen. Beim Verfassen reichen ein paar Stichworte."))
            return
        }
        val art = if (quelle.markiert) "Markierter Text" else "Ganzer Text"
        auswahl.addView(b.beschriftung("$art · ${quelle.text.length} Zeichen", 12f), b.abstand(links = 12, oben = 2))

        abschnitt("TON ÄNDERN")
        reihe(*TOENE.map { (ton, titel) -> chip(titel) { starte(Auftrag(titel, mapOf("aktion" to "stil", "ton" to ton))) } }.toTypedArray())

        abschnitt("WERKZEUGE")
        reihe(
            chip("✔️ Rechtschreibung") { starte(Auftrag("Rechtschreibung & Grammatik", mapOf("aktion" to "korrektur"))) },
            chip("📝 Zusammenfassen") { starte(Auftrag("Zusammenfassung", mapOf("aktion" to "zusammenfassen"))) },
            chip("• Stichpunkte") { starte(Auftrag("Stichpunkte", mapOf("aktion" to "stichpunkte"))) },
        )

        abschnitt("VERFASSEN AUS STICHWORTEN")
        reihe(*ARTEN.map { (art, titel) ->
            chip("🪄 $titel") { starte(Auftrag("Verfasst: $titel", mapOf("aktion" to "verfassen", "art" to art))) }
        }.toTypedArray())

        abschnitt("ÜBERSETZEN NACH")
        val ziel = dienst.einstellungen.zielSprache
        val sortiert = SPRACHEN.sortedByDescending { it.first == ziel }
        reihe(*sortiert.map { (code, titel) ->
            chip(titel, hervorgehoben = code == ziel) {
                dienst.einstellungen.zielSprache = code
                starte(Auftrag("Übersetzung: ${titel.substringAfter(' ')}", mapOf("aktion" to "uebersetzen", "ziel" to code)))
            }
        }.toTypedArray())
    }

    private fun chip(text: String, hervorgehoben: Boolean = false, klick: () -> Unit) =
        b.knopf(text, 14f, hervorgehoben, klick).apply { background = b.hintergrund(if (hervorgehoben) b.thema.akzent else b.thema.taste, 18) }

    private fun hinweis(text: String) = b.beschriftung(text, 14f).apply { setPadding(b.dp(14), b.dp(14), b.dp(14), b.dp(14)) }

    private fun baueErgebnis() {
        ergebnisBereich.setPadding(b.dp(10), b.dp(8), b.dp(10), b.dp(6))
        ergebnisBereich.addView(ergebnisTitel, b.abstand(unten = 4))
        ergebnisBereich.addView(ScrollView(dienst).apply {
            background = b.hintergrund(b.thema.taste)
            setPadding(b.dp(10), b.dp(8), b.dp(10), b.dp(8))
            addView(ergebnisText)
        }, b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f))
        ergebnisBereich.addView(knopfLeiste, b.abstand(oben = 6, breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = b.dp(42)))
    }

    private fun knoepfe(vararg paare: Pair<View, Float>) {
        knopfLeiste.removeAllViews()
        paare.forEach { (v, g) -> knopfLeiste.addView(v, b.abstand(rechts = 4, breite = 0, hoehe = ViewGroup.LayoutParams.MATCH_PARENT, gewicht = g)) }
    }

    private fun starte(auftrag: Auftrag) {
        letzterAuftrag = auftrag
        ergebnis = null
        (auswahl.parent as View).visibility = View.GONE
        ergebnisBereich.visibility = View.VISIBLE
        ergebnisTitel.text = "✨ ${auftrag.titel}"
        ergebnisText.text = "Claude schreibt …"
        ergebnisText.setTextColor(b.thema.textLeise)
        knoepfe(b.knopf("Abbrechen", 14f) { dienst.ki.abbrechen(); zurueck() } to 1f)

        dienst.ki.frage(auftrag.parameter + ("text" to quelle.text)) { antwort ->
            when (antwort) {
                is KiClient.Ergebnis.Text -> zeigeErgebnis(antwort.text)
                is KiClient.Ergebnis.Fehler -> zeigeFehler(antwort.meldung)
            }
        }
    }

    private fun zeigeErgebnis(text: String) {
        ergebnis = text
        ergebnisText.text = text
        ergebnisText.setTextColor(b.thema.text)
        knoepfe(
            b.knopf("←", 16f) { zurueck() } to 0.6f,
            b.knopf("↻", 16f) { letzterAuftrag?.let { starte(it) } } to 0.6f,
            b.knopf("Kopieren", 14f) { dienst.kopiere(text) } to 1.2f,
            b.knopf("Einfügen", 14f) { dienst.schreibe(text); dienst.zeigeTastatur() } to 1.2f,
            b.knopf("Ersetzen", 14f, hervorgehoben = true) { dienst.ersetze(quelle, text); dienst.zeigeTastatur() } to 1.4f,
        )
    }

    private fun zeigeFehler(meldung: String) {
        ergebnisText.text = "⚠️ $meldung"
        ergebnisText.setTextColor(b.thema.text)
        knoepfe(
            b.knopf("← Zurück", 14f) { zurueck() } to 1f,
            b.knopf("Nochmal", 14f, hervorgehoben = true) { letzterAuftrag?.let { starte(it) } } to 1f,
        )
    }

    private fun zurueck() {
        ergebnisBereich.visibility = View.GONE
        (auswahl.parent as View).visibility = View.VISIBLE
    }
}
