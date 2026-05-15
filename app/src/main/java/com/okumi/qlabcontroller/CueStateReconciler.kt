package com.okumi.qlabcontroller

class CueStateReconciler {
    enum class Action {
        Go,
        Previous,
        Next
    }

    data class Flags(
        val networkCorrected: Boolean,
        val externalCueChange: Boolean,
        val localMovement: Boolean
    )

    private var confirmedCueState: String? = null
    private var predictedCueState: String? = null
    private var displayedCueState: String? = null
    private var correctionPending = false
    private var externalCueChangePending = false
    private var localMovementPending = false

    fun setNetworkCue(cueId: String?): Boolean {
        val normalized = cueId.normalizeCueId()
        val previousConfirmed = confirmedCueState
        val predicted = predictedCueState
        correctionPending = predicted != null && predicted != normalized
        externalCueChangePending = predicted == null &&
            previousConfirmed != null &&
            normalized != null &&
            previousConfirmed != normalized
        confirmedCueState = normalized
        predictedCueState = null
        displayedCueState = normalized ?: displayedCueState
        return correctionPending
    }

    fun predict(action: Action, cues: List<QLabOscManager.CueData>, currentCueId: String?): String? {
        val current = currentCueId.normalizeCueId() ?: displayedCueState ?: confirmedCueState
        val currentIndex = cues.indexOfFirst { it.matches(current) }
        if (currentIndex == -1) return displayedCueState

        val nextIndex = when (action) {
            Action.Go,
            Action.Next -> currentIndex + 1
            Action.Previous -> currentIndex - 1
        }
        val predicted = cues.getOrNull(nextIndex)?.uniqueId.normalizeCueId()
        if (predicted != null) {
            predictedCueState = predicted
            displayedCueState = predicted
            correctionPending = false
            localMovementPending = true
        }
        return displayedCueState
    }

    fun displayedCueId(): String? = displayedCueState ?: predictedCueState ?: confirmedCueState

    fun consumeFlags(): Flags {
        val result = Flags(
            networkCorrected = correctionPending,
            externalCueChange = externalCueChangePending,
            localMovement = localMovementPending
        )
        correctionPending = false
        externalCueChangePending = false
        localMovementPending = false
        return result
    }

    fun reset() {
        confirmedCueState = null
        predictedCueState = null
        displayedCueState = null
        correctionPending = false
        externalCueChangePending = false
        localMovementPending = false
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
