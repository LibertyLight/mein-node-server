package de.libertylight.tastatur

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Spricht mit der Schreibhilfe auf dem Node-Server (/api/tastatur).
 *
 * Der API-Schluessel fuer Claude liegt nur auf dem Server; die Tastatur schickt
 * ihr eigenes Zugangswort mit. Antworten kommen auf dem Haupt-Thread zurueck.
 */
class KiClient(private val einstellungen: Einstellungen) {

    sealed class Ergebnis {
        data class Text(val text: String) : Ergebnis()
        data class Fehler(val meldung: String) : Ergebnis()
    }

    private val arbeiter = Executors.newSingleThreadExecutor()
    private val haupt = Handler(Looper.getMainLooper())

    /** Jede neue Anfrage macht aeltere ungueltig -- deren Antworten werden verworfen. */
    @Volatile private var generation = 0

    fun abbrechen() {
        generation++
    }

    fun frage(parameter: Map<String, String>, fertig: (Ergebnis) -> Unit) {
        val meine = ++generation
        arbeiter.execute {
            val ergebnis = anfrage("POST", "/api/tastatur/ki", JSONObject(parameter as Map<*, *>)) { json ->
                Ergebnis.Text(json.getString("text"))
            }
            haupt.post { if (meine == generation) fertig(ergebnis) }
        }
    }

    fun pruefeVerbindung(fertig: (Ergebnis) -> Unit) {
        arbeiter.execute {
            val ergebnis = anfrage("GET", "/api/tastatur/status", null) { json ->
                Ergebnis.Text("Verbunden – Modell: ${json.optString("modell")}")
            }
            haupt.post { fertig(ergebnis) }
        }
    }

    private fun anfrage(
        methode: String,
        pfad: String,
        koerper: JSONObject?,
        auswerten: (JSONObject) -> Ergebnis,
    ): Ergebnis {
        if (einstellungen.token.isEmpty()) {
            return Ergebnis.Fehler("Kein Zugangswort eingetragen. Bitte in den Einstellungen der Tastatur ergänzen.")
        }
        var verbindung: HttpURLConnection? = null
        return try {
            verbindung = (URL(einstellungen.serverUrl + pfad).openConnection() as HttpURLConnection).apply {
                requestMethod = methode
                connectTimeout = 5_000
                readTimeout = 90_000
                setRequestProperty("X-Tastatur-Token", einstellungen.token)
                setRequestProperty("Accept", "application/json")
                if (koerper != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(koerper.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = verbindung.responseCode
            val strom = if (status in 200..299) verbindung.inputStream else verbindung.errorStream
            val antwort = strom?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(antwort) } catch (_: Exception) { JSONObject() }
            when {
                status in 200..299 -> auswerten(json)
                status == 401 -> Ergebnis.Fehler("Das Zugangswort stimmt nicht mit TASTATUR_TOKEN auf dem Server überein.")
                status == 404 -> Ergebnis.Fehler("Die Schreibhilfe ist auf dem Server nicht aktiv. Ist TASTATUR_TOKEN gesetzt?")
                else -> Ergebnis.Fehler(json.optString("fehler").ifEmpty { "Der Server meldet Fehler $status." })
            }
        } catch (_: ConnectException) {
            Ergebnis.Fehler("Server nicht erreichbar (${einstellungen.serverUrl}). Läuft „npm run start:app“ in Termux?")
        } catch (_: SocketTimeoutException) {
            Ergebnis.Fehler("Zeitüberschreitung – der Server hat zu lange nicht geantwortet.")
        } catch (e: IOException) {
            Ergebnis.Fehler("Verbindungsfehler: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            Ergebnis.Fehler("Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            verbindung?.disconnect()
        }
    }
}
