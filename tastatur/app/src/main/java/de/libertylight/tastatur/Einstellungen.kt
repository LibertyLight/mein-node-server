package de.libertylight.tastatur

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import org.json.JSONArray

/** Alle Einstellungen an einem Ort. Jede Aenderung erhoeht [stand], damit die Tastatur sie bemerkt. */
class Einstellungen(context: Context) {
    private val p = context.getSharedPreferences("tastatur", Context.MODE_PRIVATE)

    private fun text(schluessel: String, vorgabe: String) = p.getString(schluessel, vorgabe) ?: vorgabe
    private fun setze(block: android.content.SharedPreferences.Editor.() -> Unit) =
        p.edit().apply(block).putInt("stand", stand + 1).apply()

    val stand: Int get() = p.getInt("stand", 0)

    var serverUrl: String
        get() = text("server_url", "http://127.0.0.1:3000")
        set(v) = setze { putString("server_url", v.trim().trimEnd('/')) }

    var token: String
        get() = text("token", "")
        set(v) = setze { putString("token", v.trim()) }

    var zahlenreihe: Boolean
        get() = p.getBoolean("zahlenreihe", false)
        set(v) = setze { putBoolean("zahlenreihe", v) }

    /** ü ö ä auf eigenen Tasten (kleinere Tasten) statt per Langdruck. */
    var umlautTasten: Boolean
        get() = p.getBoolean("umlaut_tasten", false)
        set(v) = setze { putBoolean("umlaut_tasten", v) }

    /** Ziffern als kleine Hinweise auf der oberen Reihe. */
    var zifferHinweise: Boolean
        get() = p.getBoolean("ziffer_hinweise", false)
        set(v) = setze { putBoolean("ziffer_hinweise", v) }

    val optionen: Optionen get() = Optionen(zahlenreihe, umlautTasten, zifferHinweise)

    var autoGross: Boolean
        get() = p.getBoolean("auto_gross", true)
        set(v) = setze { putBoolean("auto_gross", v) }

    var doppelLeerPunkt: Boolean
        get() = p.getBoolean("doppel_leer", true)
        set(v) = setze { putBoolean("doppel_leer", v) }

    var vorschlaege: Boolean
        get() = p.getBoolean("vorschlaege", true)
        set(v) = setze { putBoolean("vorschlaege", v) }

    var autokorrektur: Boolean
        get() = p.getBoolean("autokorrektur", true)
        set(v) = setze { putBoolean("autokorrektur", v) }

    /** Buchstabengroesse in Prozent der Vorgabe. */
    var schrift: Int
        get() = p.getInt("schrift", 100)
        set(v) = setze { putInt("schrift", v.coerceIn(70, 160)) }

    var grossBeschriftung: Boolean
        get() = p.getBoolean("gross_beschriftung", false)
        set(v) = setze { putBoolean("gross_beschriftung", v) }

    var vibration: Boolean
        get() = p.getBoolean("vibration", true)
        set(v) = setze { putBoolean("vibration", v) }

    var tastenton: Boolean
        get() = p.getBoolean("tastenton", false)
        set(v) = setze { putBoolean("tastenton", v) }

    /** Hoehe in Prozent der Standardhoehe. */
    var hoehe: Int
        get() = p.getInt("hoehe", 100)
        set(v) = setze { putInt("hoehe", v.coerceIn(70, 140)) }

    /** "system", "hell" oder "dunkel" */
    var thema: String
        get() = text("thema", "system")
        set(v) = setze { putString("thema", v) }

    /** 0 = aus, -1 = links, 1 = rechts */
    var einhand: Int
        get() = p.getInt("einhand", 0)
        set(v) = setze { putInt("einhand", v) }

    /** Wird hochgezaehlt, wenn gelernte Woerter geloescht werden sollen. */
    var woerterLoeschen: Int
        get() = p.getInt("woerter_loeschen", 0)
        set(v) = setze { putInt("woerter_loeschen", v) }

    var zielSprache: String
        get() = text("ziel_sprache", "en")
        set(v) = p.edit().putString("ziel_sprache", v).apply()

    var zuletztEmojis: List<String>
        get() = liste("emojis_zuletzt")
        set(v) = p.edit().putString("emojis_zuletzt", JSONArray(v.take(32)).toString()).apply()

    var zwischenablage: List<String>
        get() = liste("zwischenablage")
        set(v) = p.edit().putString("zwischenablage", JSONArray(v.take(MAX_ABLAGE)).toString()).apply()

    private fun liste(schluessel: String): List<String> = try {
        val a = JSONArray(text(schluessel, "[]"))
        List(a.length()) { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun istDunkel(context: Context): Boolean = when (thema) {
        "hell" -> false
        "dunkel" -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val MAX_ABLAGE = 30
    }
}

/** Farben der Tastatur, hell und dunkel -- angelehnt an das Samsung-Keyboard. */
data class Thema(
    val hintergrund: Int,
    val taste: Int,
    val sonder: Int,
    val gedrueckt: Int,
    val schatten: Int,
    val text: Int,
    val textLeise: Int,
    val akzent: Int,
    val akzentText: Int,
) {
    companion object {
        // Hell: Werte aus dem Referenz-Screenshot (Hintergrund 241, Tasten 255, Sondertasten 250, Schrift 41)
        val HELL = Thema(
            hintergrund = Color.parseColor("#F1F1F1"),
            taste = Color.parseColor("#FFFFFF"),
            sonder = Color.parseColor("#FAFAFA"),
            gedrueckt = Color.parseColor("#D6D8DE"),
            schatten = Color.parseColor("#26000000"),
            text = Color.parseColor("#292929"),
            textLeise = Color.parseColor("#777777"),
            akzent = Color.parseColor("#3E7BFA"),
            akzentText = Color.WHITE,
        )
        val DUNKEL = Thema(
            hintergrund = Color.parseColor("#1B1B1D"),
            taste = Color.parseColor("#2E2F33"),
            sonder = Color.parseColor("#27282B"),
            gedrueckt = Color.parseColor("#4A4D55"),
            schatten = Color.parseColor("#66000000"),
            text = Color.parseColor("#ECECEE"),
            textLeise = Color.parseColor("#9A9DA6"),
            akzent = Color.parseColor("#5C8DFF"),
            akzentText = Color.WHITE,
        )
    }
}
