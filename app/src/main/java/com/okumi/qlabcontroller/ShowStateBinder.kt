package com.okumi.qlabcontroller

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ShowStateBinder(private val activity: Activity) {
    fun bind(state: ShowState) {
        bindHeader(state)
        bindSync(state.sync)
        bindQlab(state.qlab)
        bindMa2(state.ma2)
        bindMemos(state)
        bindRiskIndicators(state.riskIndicators)
        bindLighting(state.upcomingLighting)
        bindChecklist(state.checklist)
        bindWatchdog(state.watchdog)
        bindRecovery(state.recovery)
        bindEventLog(state.eventLog)
    }

    fun prependEvent(time: String, type: String, message: String) {
        val container = optionalLinearLayout(R.id.eventLogContainer) ?: return
        container.addView(
            ShowHudUi.rowView(activity, "$time / ${type.uppercase()}", message, R.color.show_panel_alt),
            0
        )
    }

    private fun bindHeader(state: ShowState) {
        optionalText(R.id.showTitleText)?.text = state.show.title
        optionalText(R.id.sceneText)?.text = "${state.show.currentScene} / ${state.show.operator}"
        optionalText(R.id.showClockText)?.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        optionalText(R.id.modeText)?.let { ShowHudUi.setStatusBadge(it, state.show.mode) }
        optionalText(R.id.connectionStatusText)?.let { ShowHudUi.setStatusBadge(it, "QLab online") }
        optionalText(R.id.qlabStatusText)?.let { ShowHudUi.setStatusBadge(it, "QLab online") }
        optionalText(R.id.ma2StatusText)?.let { ShowHudUi.setStatusBadge(it, "MA2 online") }
    }

    private fun bindSync(sync: SyncState) {
        optionalText(R.id.syncStatusText)?.let { ShowHudUi.setStatusBadge(it, "sync ${sync.status}") }
        optionalText(R.id.syncWarningText)?.text = if (sync.warnings.isEmpty()) {
            "OK"
        } else {
            "MIS"
        }
    }

    private fun bindQlab(qlab: QLabSection) {
        val current = qlab.currentCue
        val next = qlab.nextCue
        find<TextView>(R.id.qlabCurrentNumberText).text = current.number
        find<TextView>(R.id.qlabCurrentNameText).text = current.name
        find<TextView>(R.id.qlabCurrentMetaText).text = buildString {
            append(current.type)
            append(if (current.isRunning) " / running" else " / standing by")
            current.remainingTime?.let { append(" / ${String.format(Locale.US, "%.1f", it)}s") }
        }
        find<TextView>(R.id.qlabNextNumberText).text = next.number
        find<TextView>(R.id.qlabNextNameText).text = next.name
        find<TextView>(R.id.qlabNextMetaText).text = next.type
        ShowHudUi.setRiskBadge(find(R.id.qlabNextRiskText), next.risk)
        applyRiskFrame(R.id.qlabCueCard, next.risk)
    }

    private fun bindMa2(ma2: Ma2Section) {
        val current = ma2.currentCue
        val next = ma2.nextCue
        find<TextView>(R.id.ma2CurrentNumberText).text = current.cue
        find<TextView>(R.id.ma2CurrentNameText).text = current.name
        find<TextView>(R.id.ma2CurrentMetaText).text = current.sequence
        find<TextView>(R.id.ma2NextNumberText).text = next.cue
        find<TextView>(R.id.ma2NextNameText).text = next.name
        find<TextView>(R.id.ma2NextMetaText).text = next.sequence
        ShowHudUi.setRiskBadge(find(R.id.ma2NextRiskText), next.risk)
        applyRiskFrame(R.id.ma2CueCard, next.risk)
    }

    private fun bindMemos(state: ShowState) {
        find<TextView>(R.id.currentMemoText).text = buildString {
            append("QLab: ${state.qlab.currentCue.memo}")
            append("\nMA2: ${state.ma2.currentCue.memo}")
        }
        find<TextView>(R.id.nextMemoText).text = buildString {
            append("QLab: ${state.qlab.nextCue.memo}")
            append("\nMA2: ${state.ma2.nextCue.memo}")
        }
    }

    private fun bindRiskIndicators(items: List<RiskIndicator>) {
        optionalText(R.id.riskStatusText)?.let { chip ->
            val highestRisk = items.firstOrNull { it.level.equals("high", ignoreCase = true) }
                ?: items.firstOrNull { it.level.equals("medium", ignoreCase = true) }
                ?: items.firstOrNull()
            val state = when (highestRisk?.level?.lowercase()) {
                "high", "danger" -> "danger"
                "medium", "warning" -> "warning"
                null -> "inactive"
                else -> "normal"
            }
            ShowHudUi.setStatusChip(chip, "RISK", state)
        }
        val container = optionalLinearLayout(R.id.riskContainer) ?: return
        ShowHudUi.fillRows(
            container,
            items.map { "${it.level.uppercase()} / ${it.label}" to it.message }
        ) { _, body ->
            val item = items.firstOrNull { it.message == body }
            ShowHudUi.riskColor(item?.level.orEmpty())
        }
    }

    private fun bindLighting(items: List<UpcomingLightingCue>) {
        optionalText(R.id.expectedLightingText)?.text = items.firstOrNull()?.let {
            "Expected: MA2 Cue ${it.cue} / ${it.label}\n${it.notes}"
        } ?: "Expected lighting state unavailable."
        optionalLinearLayout(R.id.lightingContainer)?.let { container ->
            ShowHudUi.fillRows(
                container,
                items.map { "MA2 Cue ${it.cue} / ${it.label}" to it.notes }
            )
        }
    }

    private fun bindChecklist(items: List<ChecklistItem>) {
        val container = optionalLinearLayout(R.id.checklistContainer) ?: return
        ShowHudUi.fillRows(
            container,
            items.map { item ->
                val status = if (item.done) "READY" else "PENDING"
                "$status / ${item.label}" to if (item.done) "Complete" else "Needs confirmation before next transition"
            }
        ) { _, body ->
            if (body == "Complete") R.color.show_ok else R.color.show_prepare
        }
    }

    private fun bindWatchdog(items: List<WatchdogItem>) {
        val container = optionalLinearLayout(R.id.watchdogContainer) ?: return
        ShowHudUi.fillRows(
            container,
            items.map { "${it.status.uppercase()} / ${it.label}" to watchdogMessage(it.status) }
        ) { title, _ ->
            ShowHudUi.statusColor(title)
        }
    }

    private fun watchdogMessage(status: String): String {
        return when (status.lowercase()) {
            "ok" -> "No unexpected running cue detected."
            "warning" -> "Check and stop if still active."
            "attention" -> "Verify before the next show action."
            else -> "Status unknown."
        }
    }

    private fun bindRecovery(recovery: RecoveryState) {
        optionalText(R.id.recoveryTitleText)?.text = recovery.title
        optionalText(R.id.recoveryExpectedText)?.text = "QLab: ${recovery.expectedQlabCue}\nMA2: ${recovery.expectedMa2Cue}"
        optionalLinearLayout(R.id.recoveryStepsContainer)?.let { container ->
            ShowHudUi.fillRows(
                container,
                recovery.steps.mapIndexed { index, step -> "Step ${index + 1}" to step }
            )
        }
    }

    private fun bindEventLog(items: List<EventLogItem>) {
        val container = optionalLinearLayout(R.id.eventLogContainer) ?: return
        ShowHudUi.fillRows(
            container,
            items.take(3).map { "${it.time} / ${it.type.uppercase()}" to it.message }
        ) { _, body ->
            when {
                body.contains("warning", ignoreCase = true) -> R.color.show_prepare
                body.contains("still", ignoreCase = true) -> R.color.show_danger
                else -> R.color.show_panel_alt
            }
        }
    }

    private fun optionalText(id: Int): TextView? {
        return activity.findViewById<View>(id) as? TextView
    }

    private fun optionalLinearLayout(id: Int): LinearLayout? {
        return activity.findViewById<View>(id) as? LinearLayout
    }

    private fun applyRiskFrame(id: Int, risk: String) {
        val card = activity.findViewById<View>(id) as? MaterialCardView ?: return
        card.strokeColor = activity.getColor(ShowHudUi.riskColor(risk))
        card.strokeWidth = activity.resources.displayMetrics.density.times(
            if (risk.equals("high", ignoreCase = true) || risk.equals("danger", ignoreCase = true)) 3f else 1.5f
        ).toInt()
    }

    private fun <T : View> find(id: Int): T = activity.findViewById(id)
}
