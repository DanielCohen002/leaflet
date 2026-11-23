package com.djcohen.offlinereader

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

private const val LEAF = 0xFF6CC070.toInt()
private const val LEAF_PRESSED = 0xFF55A85A.toInt()
private const val FOREST = 0xFF1E3A2A.toInt()

/**
 * Show a dialog with Leaflet-colored buttons. The system draws dialog buttons in its own accent (blue on this phone)
 * regardless of the app theme, so they are restyled here: a leaf-green pill for the main button, green text for the rest.
 */
fun AlertDialog.Builder.showLeaf(): AlertDialog {
    val dialog = create()
    dialog.setOnShowListener {
        // The system draws the title and list rows in its own dark text colour whatever the theme says.
        val titleId = dialog.context.resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<android.widget.TextView>(titleId)?.setTextColor(0xFFFFF8E7.toInt())
        // Same for the message text (e.g. release notes), which was dark on the dark dialog.
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(0xFFFFF8E7.toInt())
        dialog.listView?.let { lv ->
            lv.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: android.view.View?, child: android.view.View?) { tint(child) }
                override fun onChildViewRemoved(parent: android.view.View?, child: android.view.View?) {}
            })
            for (i in 0 until lv.childCount) tint(lv.getChildAt(i))
        }
        val density = dialog.context.resources.displayMetrics.density
        fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 24 * density }
        val pressed = intArrayOf(android.R.attr.state_pressed)
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            val button = dialog.getButton(which) ?: continue
            button.isAllCaps = false
            if (which == AlertDialog.BUTTON_POSITIVE) {
                button.setTextColor(FOREST)
                button.background = StateListDrawable().apply {
                    addState(pressed, pill(LEAF_PRESSED))
                    addState(intArrayOf(), pill(LEAF))
                }
            } else {
                button.setTextColor(LEAF)
                button.background = StateListDrawable().apply {
                    addState(pressed, pill(0x336CC070))
                    addState(intArrayOf(), pill(Color.TRANSPARENT))
                }
            }
        }
    }
    dialog.show()
    return dialog
}

private fun tint(v: android.view.View?) {
    if (v is android.widget.TextView) v.setTextColor(0xFFFFF8E7.toInt())
    else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) tint(v.getChildAt(i))
}

private const val CARD = 0xFFFFF8E7.toInt()
private const val CARD_PRESSED = 0xFFEADFC0.toInt()
private const val INK = 0xFF22301F.toInt()
private const val MUTED = 0xFF5E7F61.toInt()

/** One choice in [showChoices]: a title, an optional line of explanation, and what happens when it is tapped. */
class Choice(val title: String, val subtitle: String? = null, val onPick: () -> Unit)

/**
 * A dialog whose choices are cream rounded cards (like the books on the shelf) instead of bare lines of text,
 * so they look tappable, with room between them.
 */
fun android.app.Activity.showChoices(title: String, choices: List<Choice>): AlertDialog {
    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius * density }
    var dialog: AlertDialog? = null
    val column = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    for (c in choices) {
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(CARD_PRESSED, 18))
                addState(intArrayOf(), rounded(CARD, 18))
            }
            setPadding(dp(16), dp(16), dp(18), dp(16))
            addView(android.view.View(context).apply { background = rounded(LEAF, 3) },
                android.widget.LinearLayout.LayoutParams(dp(5), dp(40)).apply { marginEnd = dp(14) })
            addView(android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(android.widget.TextView(context).apply {
                    text = c.title; textSize = 17f; setTextColor(INK)
                    typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
                })
                if (c.subtitle != null) addView(android.widget.TextView(context).apply {
                    text = c.subtitle; textSize = 13f; setTextColor(MUTED); setPadding(0, dp(3), 0, 0)
                })
            }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { dialog?.dismiss(); c.onPick() }
        }
        column.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    dialog = AlertDialog.Builder(this).setTitle(title)
        .setView(android.widget.ScrollView(this).apply { addView(column) })
        .setNegativeButton("Cancel", null)
        .showLeaf()
    return dialog
}
