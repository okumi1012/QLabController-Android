package com.okumi.qlabcontroller

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object ShowHudUi {
    fun riskColor(risk: String): Int {
        val normalized = risk.lowercase()
        return when {
            normalized.contains("high") || normalized.contains("danger") ||
                normalized.contains("blackout") || normalized.contains("automation") -> R.color.show_danger
            normalized.contains("medium") || normalized.contains("warning") ||
                normalized.contains("attention") || normalized.contains("timing") -> R.color.show_prepare
            else -> R.color.show_ok
        }
    }

    fun statusColor(status: String): Int {
        val normalized = status.lowercase()
        return when {
            normalized.contains("ok") || normalized.contains("running") ||
                normalized.contains("online") || normalized.contains("done") -> R.color.show_ok
            normalized.contains("mismatch") || normalized.contains("warning") ||
                normalized.contains("attention") -> R.color.show_prepare
            normalized.contains("error") || normalized.contains("failed") ||
                normalized.contains("danger") -> R.color.show_danger
            normalized.contains("disconnect") || normalized.contains("offline") -> R.color.show_disconnected
            else -> R.color.show_info
        }
    }

    fun setRiskBadge(textView: TextView, risk: String) {
        textView.text = risk.ifBlank { "low" }.uppercase()
        textView.setTextColor(textView.context.getColor(R.color.show_bg))
        textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        textView.maxLines = 1
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.background = roundedFill(textView.context, riskColor(risk), 12f)
        textView.setPadding(textView.context.dp(10), textView.context.dp(4), textView.context.dp(10), textView.context.dp(4))
    }

    fun setStatusBadge(textView: TextView, status: String) {
        textView.text = status.ifBlank { "unknown" }.uppercase()
        textView.setTextColor(textView.context.getColor(R.color.show_bg))
        textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        textView.maxLines = 1
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.background = roundedFill(textView.context, statusColor(status), 12f)
        textView.setPadding(textView.context.dp(10), textView.context.dp(4), textView.context.dp(10), textView.context.dp(4))
    }

    fun setStatusChip(textView: TextView, label: String, state: String) {
        textView.text = label.uppercase()
        textView.setTextColor(textView.context.getColor(R.color.show_bg))
        textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        textView.maxLines = 1
        textView.ellipsize = TextUtils.TruncateAt.END
        val color = when (state.lowercase()) {
            "normal", "ready" -> R.color.show_ok
            "warning" -> R.color.show_prepare
            "danger" -> R.color.show_danger
            "disconnected" -> R.color.show_disconnected
            "correcting" -> R.color.show_info
            "inactive" -> R.color.show_panel_alt
            else -> statusColor(state)
        }
        textView.background = roundedFill(textView.context, color, 12f)
        textView.setPadding(textView.context.dp(9), textView.context.dp(4), textView.context.dp(9), textView.context.dp(4))
    }

    fun fillRows(
        container: LinearLayout,
        rows: List<Pair<String, String>>,
        colorSelector: (String, String) -> Int = { _, _ -> R.color.show_panel_alt }
    ) {
        container.removeAllViews()
        rows.forEach { (title, body) ->
            container.addView(rowView(container.context, title, body, colorSelector(title, body)))
        }
    }

    fun fillPlainRows(container: LinearLayout, rows: List<String>) {
        container.removeAllViews()
        rows.forEach { row ->
            container.addView(rowView(container.context, row, "", R.color.show_panel_alt))
        }
    }

    fun rowView(context: Context, title: String, body: String, colorRes: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedStroke(context, colorRes, R.color.show_border, 8f)
            setPadding(context.dp(9), context.dp(7), context.dp(9), context.dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = context.dp(6)
            }
        }

        row.addView(TextView(context).apply {
            text = title
            setTextColor(context.getColor(R.color.show_text))
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        if (body.isNotBlank()) {
            row.addView(TextView(context).apply {
                text = body
                setTextColor(context.getColor(R.color.show_muted))
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, context.dp(2), 0, 0)
            })
        }
        return row
    }

    fun roundedFill(context: Context, colorRes: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(context.getColor(colorRes))
            cornerRadius = context.dp(radiusDp).toFloat()
        }
    }

    private fun roundedStroke(context: Context, fillRes: Int, strokeRes: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(context.getColor(fillRes))
            setStroke(context.dp(1), context.getColor(strokeRes))
            cornerRadius = context.dp(radiusDp).toFloat()
        }
    }

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
