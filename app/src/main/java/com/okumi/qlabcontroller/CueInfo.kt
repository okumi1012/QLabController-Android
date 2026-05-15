package com.okumi.qlabcontroller

data class CueInfo(
    val previous2Cue: String = "---",
    val previousCue: String = "---",
    val currentCue: String = "---",
    val nextCue: String = "---",
    val next2Cue: String = "---",
    val currentNotes: String = "",
    val nextNotes: String = "",
    val currentCueId: String? = null,
    val nextCueId: String? = null,
    val riskState: String = "normal",
    val hasFollowTiming: Boolean = false,
    val isGroupCue: Boolean = false,
    val isPredicted: Boolean = false,
    val isNetworkCorrected: Boolean = false,
    val isExternalCueChange: Boolean = false,
    val isLocalMovement: Boolean = false
)
