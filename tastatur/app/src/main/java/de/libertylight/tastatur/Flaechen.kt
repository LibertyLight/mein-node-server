package de.libertylight.tastatur

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Die Flaechen, die anstelle der Tasten erscheinen koennen: Emojis, Zwischenablage,
 * Textbearbeitung. Jede liefert eine View in der Hoehe des Tastenfelds.
 */

/** Gemeinsame Fusszeile: zurueck zur Tastatur, Leerzeichen, Loeschen. */
private fun fusszeile(b: Bausteine, dienst: TastaturDienst): View = b.waagerecht().apply {
    setPadding(b.dp(4), b.dp(4), b.dp(4), b.dp(4))
    addView(b.knopf("ABC") { dienst.zeigeTastatur() }, b.abstand(rechts = 4, hoehe = b.dp(42), gewicht = 1.5f, breite = 0))
    addView(b.knopf("␣") { dienst.schreibe(" ") }, b.abstand(rechts = 4, hoehe = b.dp(42), gewicht = 5f, breite = 0))
    addView(b.knopf("⌫") { dienst.loesche() }, b.abstand(hoehe = b.dp(42), gewicht = 1.5f, breite = 0))
}

// ---------------------------------------------------------------- Emojis

object EmojiListen {
    val kategorien: List<Pair<String, String>> = listOf(
        "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖",
        "👍" to "👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 👷 🧑‍🏫 🧑‍⚕️ 🧑‍💻 🏃 🚶 💃 🕺 👫 👪",
        "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☯️ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎ ✅ ❌ ❎ ⭕ 🛑 ⛔ 🚫 💯 💢 ♨️ ❗ ❕ ❓ ❔ ‼️ ⁉️ ⚠️ 🔆 ♻️ ✳️ ❇️ 💠 🔰 ➕ ➖ ➗ ✖️ 🟰 💲 💱 ™️ ©️ ®️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪",
        "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🕷️ 🐢 🐍 🦎 🐙 🦑 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐈 🐓 🦃 🕊️ 🐇 🐿️ 🦔 🌲 🌳 🌴 🌵 🌿 🍀 🍁 🍂 🍃 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌙 ⭐ 🌟 ⚡ 🔥 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 💧 🌊",
        "🍕" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍰 🎂 🧁 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊",
        "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 ⛹️ 🤾 🚴 🏊 🧗 🏆 🥇 🥈 🥉 🏅 🎖️ 🎗️ 🎫 🎟️ 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟️ 🎯 🎳 🎮 🕹️ 🧩 🎉 🎊 🎈 🎁 🎀 🎄 🎃",
        "🚗" to "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🛴 🚲 🛵 🏍️ 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🚀 🛸 🚁 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🏕️ 🏠 🏡 🏢 🏥 🏦 🏨 🏪 🏫 ⛪ 🕌 🌅 🌄 🌃 🌉",
        "💡" to "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💾 💿 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙️ ⏰ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💶 💳 💎 ⚖️ 🔧 🔨 🛠️ ⚙️ 🔩 🧲 🔫 💣 🔪 🛡️ 🔮 💊 💉 🩺 🧬 🦠 🧹 🧺 🧻 🚽 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎁 ✉️ 📩 📦 🏷️ 📜 📄 📅 📆 📇 📈 📉 📊 📋 📌 📍 📎 🖇️ 📏 📐 ✂️ 🗑️ 🔒 🔓 🖊️ ✏️ 📝 🔍 🔎",
        "🏳️" to "🏁 🚩 🏳️ 🏴 🏳️‍🌈 🏳️‍⚧️ 🇩🇪 🇦🇹 🇨🇭 🇪🇺 🇬🇧 🇺🇸 🇫🇷 🇪🇸 🇮🇹 🇳🇱 🇧🇪 🇩🇰 🇸🇪 🇳🇴 🇫🇮 🇵🇱 🇨🇿 🇺🇦 🇷🇺 🇹🇷 🇬🇷 🇵🇹 🇮🇪 🇮🇸 🇭🇷 🇷🇴 🇧🇬 🇭🇺 🇯🇵 🇰🇷 🇨🇳 🇮🇳 🇧🇷 🇦🇷 🇲🇽 🇨🇦 🇦🇺 🇿🇦 🇪🇬 🇸🇾 🇮🇷 🇮🇶 🇦🇫 🇲🇦 🇳🇬",
    )
}

class EmojiFlaeche(private val dienst: TastaturDienst, private val b: Bausteine) {

    private val raster = GridView(dienst).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = b.dp(44)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        verticalSpacing = b.dp(2)
        isVerticalScrollBarEnabled = false
    }
    private var emojis: List<String> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = emojis.size
        override fun getItem(position: Int) = emojis[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, alt: View?, eltern: ViewGroup?): View =
            ((alt as? TextView) ?: TextView(dienst).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, b.dp(46))
            }).apply { text = emojis[position] }
    }

    fun baue(): View {
        raster.adapter = adapter
        raster.setOnItemClickListener { _, _, position, _ ->
            val emoji = emojis[position]
            dienst.schreibe(emoji)
            val einst = dienst.einstellungen
            einst.zuletztEmojis = listOf(emoji) + einst.zuletztEmojis.filter { it != emoji }
        }

        val reiter = b.waagerecht()
        val zuletzt = dienst.einstellungen.zuletztEmojis
        reiter.addView(b.symbol("🕘", "Zuletzt verwendet", 20f) { zeige(dienst.einstellungen.zuletztEmojis) }, b.abstand(breite = b.dp(44), hoehe = b.dp(40)))
        for ((symbol, liste) in EmojiListen.kategorien) {
            reiter.addView(b.symbol(symbol, "Kategorie", 20f) { zeige(liste.split(' ')) }, b.abstand(breite = b.dp(44), hoehe = b.dp(40)))
        }
        zeige(zuletzt.ifEmpty { EmojiListen.kategorien.first().second.split(' ') })

        return b.senkrecht().apply {
            addView(b.chipReihe(reiter))
            addView(raster, b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f))
            addView(fusszeile(b, dienst))
        }
    }

    private fun zeige(liste: List<String>) {
        emojis = liste.filter { it.isNotBlank() }
        adapter.notifyDataSetChanged()
        raster.setSelection(0)
    }
}

// ---------------------------------------------------------------- Zwischenablage

class AblageFlaeche(private val dienst: TastaturDienst, private val b: Bausteine) {
    private val liste = b.senkrecht()

    fun baue(): View {
        val kopf = b.waagerecht().apply {
            setPadding(b.dp(10), b.dp(6), b.dp(6), b.dp(4))
            addView(b.beschriftung("📋 Zwischenablage", 15f, fett = true), b.abstand(gewicht = 1f, breite = 0))
            addView(b.knopf("Alles löschen", 13f) {
                dienst.einstellungen.zwischenablage = emptyList()
                fuelle()
            })
        }
        fuelle()
        return b.senkrecht().apply {
            addView(kopf)
            addView(ScrollView(dienst).apply { addView(liste) }, b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f))
            addView(fusszeile(b, dienst))
        }
    }

    private fun fuelle() {
        liste.removeAllViews()
        val eintraege = dienst.einstellungen.zwischenablage
        if (eintraege.isEmpty()) {
            liste.addView(b.beschriftung("Noch nichts kopiert. Kopierter Text erscheint hier, solange die Tastatur aktiv ist.").apply {
                setPadding(b.dp(14), b.dp(14), b.dp(14), b.dp(14))
            })
            return
        }
        for (eintrag in eintraege) {
            val zeile = b.waagerecht().apply {
                background = b.hintergrund(b.thema.taste)
                setPadding(b.dp(12), b.dp(8), b.dp(4), b.dp(8))
                addView(TextView(dienst).apply {
                    text = eintrag
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(b.thema.text)
                    textSize = 14f
                }, b.abstand(gewicht = 1f, breite = 0))
                addView(b.symbol("✕", "Eintrag entfernen", 16f) {
                    dienst.einstellungen.zwischenablage = dienst.einstellungen.zwischenablage.filter { it != eintrag }
                    fuelle()
                }, b.abstand(breite = b.dp(40), hoehe = b.dp(40)))
                setOnClickListener { dienst.schreibe(eintrag) }
            }
            liste.addView(zeile, b.abstand(links = 6, oben = 4, rechts = 6, breite = ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}

// ---------------------------------------------------------------- Textbearbeitung

class BearbeitenFlaeche(private val dienst: TastaturDienst, private val b: Bausteine) {
    private var markieren = false
    private lateinit var markierKnopf: TextView

    fun baue(): View {
        markierKnopf = b.knopf("Markieren") {
            markieren = !markieren
            markierKnopf.background = b.hintergrund(if (markieren) b.thema.akzent else b.thema.taste)
            markierKnopf.setTextColor(if (markieren) b.thema.akzentText else b.thema.text)
        }

        fun pfeil(text: String, code: Int) = b.knopf(text, 20f) { dienst.taste(code, markieren) }
        fun reihe(vararg knoepfe: View) = b.waagerecht().apply {
            knoepfe.forEach { addView(it, b.abstand(links = 3, rechts = 3, breite = 0, hoehe = ViewGroup.LayoutParams.MATCH_PARENT, gewicht = 1f)) }
        }
        fun menue(text: String, id: Int) = b.knopf(text, 14f) { dienst.kontextAktion(id) }

        val gitter = b.senkrecht().apply {
            setPadding(b.dp(4), b.dp(6), b.dp(4), b.dp(2))
            val zeile = { v: View -> addView(v, b.abstand(oben = 3, unten = 3, breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f)) }
            zeile(reihe(b.knopf("⇤ Anfang", 14f) { dienst.taste(KeyEvent.KEYCODE_MOVE_HOME, markieren, strg = true) },
                pfeil("▲", KeyEvent.KEYCODE_DPAD_UP),
                b.knopf("Ende ⇥", 14f) { dienst.taste(KeyEvent.KEYCODE_MOVE_END, markieren, strg = true) }))
            zeile(reihe(pfeil("◀", KeyEvent.KEYCODE_DPAD_LEFT), markierKnopf, pfeil("▶", KeyEvent.KEYCODE_DPAD_RIGHT)))
            zeile(reihe(b.knopf("Alles", 14f) { dienst.kontextAktion(android.R.id.selectAll) },
                pfeil("▼", KeyEvent.KEYCODE_DPAD_DOWN),
                b.knopf("⌫", 18f) { dienst.loesche() }))
            zeile(reihe(menue("Ausschneiden", android.R.id.cut), menue("Kopieren", android.R.id.copy), menue("Einfügen", android.R.id.paste)))
        }
        return b.senkrecht().apply {
            addView(gitter, b.abstand(breite = ViewGroup.LayoutParams.MATCH_PARENT, hoehe = 0, gewicht = 1f))
            addView(b.waagerecht().apply {
                setPadding(b.dp(4), b.dp(2), b.dp(4), b.dp(4))
                addView(b.knopf("ABC") { dienst.zeigeTastatur() }, b.abstand(breite = 0, hoehe = b.dp(42), gewicht = 1f))
            })
        }
    }
}

/** Hoehe einer Flaeche fest auf die Hoehe des Tastenfelds setzen. */
fun View.mitHoehe(hoehe: Int): View = apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hoehe)
}
