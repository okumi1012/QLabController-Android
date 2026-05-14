package com.okumi.qlabcontroller

class CueStateReconciler {
    enum class Action {
        Go,
        Previous,
        Next
    }

    private var networkCueId: String? = null
    private var predictedCueId: String? = null
    private var displayedCueId: String? = null
    private var correctionPending = false

    fun setNetworkCue(cueId: String?): Boolean {
        val normalized = cueId.normalizeCueId()
        val predicted = predictedCueId
        correctionPending = predicted != null && predicted != normalized
        networkCueId = normalized
        predictedCueId = null
        displayedCueId = normalized ?: displayedCueId
        return correctionPending
    }

    fun predict(action: Action, cues: List<QLabOscManager.CueData>, currentCueId: String?): String? {
        val current = currentCueId.normalizeCueId() ?: displayedCueId ?: networkCueId
        val currentIndex = cues.indexOfFirst { it.matches(current) }
        if (currentIndex == -1) return displayedCueId

        val nextIndex = when (action) {
            Action.Go,
            Action.Next -> currentIndex + 1
            Action.Previous -> currentIndex - 1
        }
        val predicted = cues.getOrNull(nextIndex)?.uniqueId.normalizeCueId()
        if (predicted != null) {
            predictedCueId = predicted
            displayedCueId = predicted
            correctionPending = false
        }
        return displayedCueId
    }

    fun displayedCueId(): String? = displayedCueId ?: predictedCueId ?: networkCueId

    fun consumeCorrectionPending(): Boolean {
        val result = correctionPending
        correctionPending = false
        return result
    }

    fun reset() {
        networkCueId = null
        predictedCueId = null
        displayedCueId = null
        correctionPending = false
    }

    private fun QLabOscManager.CueData.matches(cueId: String?): Boolean {
        if (cueId.isNullOrBlank()) return false
        return uniqueId == cueId || number == cueId
    }
}

fun String?.normalizeCueId(): String? {
    val value = this?.trim().orEmpty()
    return when {
        value.isEmpty() -> null
        value.equals("none", ignoreCase = true) -> null
        value.equals("null", ignoreCase = true) -> null
        value == "-" -> null
        value == "---" -> null
        else -> value
    }
}
