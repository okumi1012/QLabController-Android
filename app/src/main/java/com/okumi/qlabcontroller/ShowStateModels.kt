package com.okumi.qlabcontroller

data class ShowState(
    val show: ShowInfo,
    val qlab: QLabSection,
    val ma2: Ma2Section,
    val sync: SyncState,
    val riskIndicators: List<RiskIndicator>,
    val upcomingLighting: List<UpcomingLightingCue>,
    val checklist: List<ChecklistItem>,
    val watchdog: List<WatchdogItem>,
    val eventLog: List<EventLogItem>,
    val recovery: RecoveryState
)

data class ShowInfo(
    val title: String,
    val mode: String,
    val currentScene: String,
    val status: String,
    val operator: String
)

data class QLabSection(
    val currentCue: QLabCue,
    val nextCue: QLabCue
)

data class QLabCue(
    val id: String,
    val number: String,
    val name: String,
    val type: String,
    val memo: String,
    val remainingTime: Double?,
    val isRunning: Boolean,
    val risk: String
)

data class Ma2Section(
    val currentCue: Ma2Cue,
    val nextCue: Ma2Cue
)

data class Ma2Cue(
    val sequence: String,
    val cue: String,
    val name: String,
    val memo: String,
    val risk: String
)

data class SyncState(
    val status: String,
    val warnings: List<String>
)

data class RiskIndicator(
    val id: String,
    val level: String,
    val label: String,
    val message: String
)

data class UpcomingLightingCue(
    val id: String,
    val cue: String,
    val label: String,
    val notes: String
)

data class ChecklistItem(
    val id: String,
    val label: String,
    val done: Boolean
)

data class WatchdogItem(
    val id: String,
    val label: String,
    val status: String
)

data class EventLogItem(
    val time: String,
    val type: String,
    val message: String
)

data class RecoveryState(
    val title: String,
    val expectedQlabCue: String,
    val expectedMa2Cue: String,
    val steps: List<String>
)
