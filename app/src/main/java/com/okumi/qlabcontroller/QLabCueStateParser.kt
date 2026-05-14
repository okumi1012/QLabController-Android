package com.okumi.qlabcontroller

import org.json.JSONArray
import org.json.JSONObject

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
        networkCorrected: Boolean
    ): CueInfo {
        val activeCue = cues.firstOrNull { it.isRunning }
        val cueId = activeCue?.uniqueId.normalizeCueId() ?: displayedCueId.normalizeCueId()
        val currentIndex = cues.indexOfFirst { cue ->
            cue.uniqueId == cueId || cue.number == cueId
        }

        fun cueText(index: Int): String {
            if (index !in cues.indices) return "---"
            return cues[index].displayText()
        }

        val currentText = when {
            currentIndex in cues.indices -> cues[currentIndex].displayText()
            !cueId.isNullOrBlank() -> "Cue $cueId"
            else -> "---"
        }
        val nextText = if (currentIndex in cues.indices) cueText(currentIndex + 1) else "---"

        return CueInfo(
            previous2Cue = cueText(currentIndex - 2),
            previousCue = cueText(currentIndex - 1),
            currentCue = currentText,
            nextCue = nextText,
            next2Cue = cueText(currentIndex + 2),
            currentNotes = currentNotes,
            isNetworkCorrected = networkCorrected
        )
    }

    fun childParentIdFromRequest(address: String): String? {
        return Regex("/cue_id/([^/]+)/children$").find(address)?.groupValues?.getOrNull(1)
    }

    private fun QLabOscManager.CueData.displayText(): String {
        val cueNumber = number.ifBlank { uniqueId.take(8) }
        return listOf(cueNumber, name).filter { it.isNotBlank() }.joinToString(" ")
    }
}
