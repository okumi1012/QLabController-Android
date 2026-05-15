package com.okumi.qlabcontroller

import org.json.JSONArray

object QLabCueStateParser {
    fun parseCueArray(cuesArray: JSONArray?, parentId: String? = null): List<QLabOscManager.CueData> {
        if (cuesArray == null) return emptyList()
        val parsed = mutableListOf<QLabOscManager.CueData>()
        for (index in 0 until cuesArray.length()) {
            val cue = cuesArray.optJSONObject(index) ?: continue
            val cueId = cue.optString("uniqueID", "").ifBlank {
                cue.optString("id", "")
            }
            val cueName = cue.optString("name", "").ifEmpty {
                cue.optString("listName", "Untitled")
            }
            val cueNotes = cue.optString("notes", "").ifEmpty {
                cue.optString("note", "")
            }
            val cueType = cue.optString("type", "")
            parsed.add(
                QLabOscManager.CueData(
                    uniqueId = cueId,
                    number = cue.optString("number", ""),
                    name = cueName,
                    type = cueType,
                    notes = cueNotes,
                    parentId = parentId,
                    orderIndex = index,
                    isRunning = cue.optBoolean("isRunning") ||
                        cue.optBoolean("running") ||
                        cue.optBoolean("isPlaying")
                )
            )

            val nested = cue.optJSONArray("cues") ?: cue.optJSONArray("children")
            val nestedParentId = cueId.ifBlank { parentId.orEmpty() }.takeIf { it.isNotBlank() }
            parsed.addAll(parseCueArray(nested, nestedParentId))
        }
        return parsed
    }

    fun buildCueInfo(
        cues: List<QLabOscManager.CueData>,
        displayedCueId: String?,
        currentNotes: String,
        flags: CueStateReconciler.Flags
    ): CueInfo {
        val cueId = displayedCueId.normalizeCueId()
        val selectedCue = selectCurrentCue(cues, cueId)
        val currentIndex = selectedCue?.let { cues.indexOf(it) } ?: -1
        val nextCue = cues.getOrNull(currentIndex + 1)

        fun cueText(index: Int): String {
            if (index !in cues.indices) return "---"
            return cues[index].displayText()
        }

        val currentText = when {
            selectedCue != null -> selectedCue.displayText()
            !cueId.isNullOrBlank() -> "Cue $cueId"
            else -> "---"
        }
        val nextText = nextCue?.displayText() ?: "---"
        val resolvedCurrentNotes = selectedCue?.notes?.ifBlank { currentNotes } ?: currentNotes
        val riskState = listOfNotNull(selectedCue, nextCue)
            .map { it.riskState() }
            .firstOrNull { it == "danger" }
            ?: listOfNotNull(selectedCue, nextCue).map { it.riskState() }.firstOrNull { it == "warning" }
            ?: "normal"

        return CueInfo(
            previous2Cue = cueText(currentIndex - 2),
            previousCue = cueText(currentIndex - 1),
            currentCue = currentText,
            nextCue = nextText,
            next2Cue = cueText(currentIndex + 2),
            currentNotes = resolvedCurrentNotes,
            nextNotes = nextCue?.notes.orEmpty(),
            currentCueId = selectedCue?.uniqueId ?: cueId,
            nextCueId = nextCue?.uniqueId,
            riskState = riskState,
            hasFollowTiming = listOfNotNull(selectedCue, nextCue).any { it.hasFollowTiming() },
            isGroupCue = selectedCue?.parentId != null || selectedCue?.type?.contains("group", ignoreCase = true) == true,
            isNetworkCorrected = flags.networkCorrected,
            isExternalCueChange = flags.externalCueChange,
            isLocalMovement = flags.localMovement
        )
    }

    fun childParentIdFromRequest(address: String): String? {
        return Regex("/cue_id/([^/]+)/children$").find(address)?.groupValues?.getOrNull(1)
    }

    private fun QLabOscManager.CueData.displayText(): String {
        val cueNumber = number.ifBlank { uniqueId.take(8) }
        return listOf(cueNumber, name).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun selectCurrentCue(cues: List<QLabOscManager.CueData>, cueId: String?): QLabOscManager.CueData? {
        val activeCue = cues.filter { cue ->
            cue.isRunning && (cueId == null || cue.matches(cueId) || cue.isDescendantOf(cueId, cues))
        }.maxByOrNull { it.orderIndex } ?: cues.filter { it.isRunning }.maxByOrNull { it.orderIndex }
        if (activeCue != null) return activeCue

        val confirmedCue = cues.firstOrNull { it.matches(cueId) } ?: return null
        if (confirmedCue.type.contains("group", ignoreCase = true)) {
            return cues.firstOrNull { it.parentId == confirmedCue.uniqueId } ?: confirmedCue
        }
        return confirmedCue
    }

    private fun QLabOscManager.CueData.matches(cueId: String?): Boolean {
        if (cueId.isNullOrBlank()) return false
        return uniqueId == cueId || number == cueId
    }

    private fun QLabOscManager.CueData.isDescendantOf(parentCueId: String, cues: List<QLabOscManager.CueData>): Boolean {
        var parent = parentId
        while (!parent.isNullOrBlank()) {
            if (parent == parentCueId) return true
            parent = cues.firstOrNull { it.uniqueId == parent }?.parentId
        }
        return false
    }

    private fun QLabOscManager.CueData.riskState(): String {
        val text = "$type $name $notes".lowercase()
        return when {
            listOf("danger", "blackout", "automation", "motor", "trap", "pyro", "critical").any { it in text } -> "danger"
            listOf("warning", "timing", "follow", "transition", "stop", "wait").any { it in text } -> "warning"
            else -> "normal"
        }
    }

    private fun QLabOscManager.CueData.hasFollowTiming(): Boolean {
        val text = "$type $name $notes".lowercase()
        return listOf("follow", "timing", "wait", "autofollow").any { it in text }
    }
}
