package de.libertylight.tastatur

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Kleine Helfer, um die Flaechen der Tastatur ohne XML aufzubauen. */
class Bausteine(private val context: Context, val thema: Thema) {
    private val dichte = context.resources.displayMetrics.density
    fun dp(wert: Number): Int = (wert.toFloat() * dichte).toInt()

    fun hintergrund(farbe: Int, rund: Number = 8): GradientDrawable = GradientDrawable().apply {
        setColor(farbe)
        cornerRadius = dp(rund).toFloat()
    }

    /** Ein flacher Knopf mit Text oder Emoji. */
    fun knopf(text: String, groesse: Float = 15f, hervorgehoben: Boolean = false, klick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = groesse
            gravity = Gravity.CENTER
            setTextColor(if (hervorgehoben) thema.akzentText else thema.text)
            background = hintergrund(if (hervorgehoben) thema.akzent else thema.taste)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { klick() }
        }

    /** Knopf ohne Hintergrund, z. B. fuer die Werkzeugleiste. */
    fun symbol(text: String, beschreibung: String, groesse: Float = 20f, klick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = groesse
            gravity = Gravity.CENTER
            setTextColor(thema.text)
            contentDescription = beschreibung
            setOnClickListener { klick() }
        }

    fun beschriftung(text: String, groesse: Float = 13f, fett: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = groesse
        setTextColor(if (fett) thema.text else thema.textLeise)
        if (fett) typeface = Typeface.DEFAULT_BOLD
    }

    /** Eine waagerecht scrollbare Reihe von Chips. */
    fun chipReihe(vararg chips: View): HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        val reihe = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEach { reihe.addView(it, abstand(rechts = 6)) }
        addView(reihe)
    }

    fun abstand(
        links: Int = 0, oben: Int = 0, rechts: Int = 0, unten: Int = 0,
        breite: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        hoehe: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        gewicht: Float = 0f,
    ) = LinearLayout.LayoutParams(breite, hoehe, gewicht).apply { setMargins(dp(links), dp(oben), dp(rechts), dp(unten)) }

    fun senkrecht(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun waagerecht(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
}
