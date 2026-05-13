package com.okumi.qlabcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface ShowStateRepository {
    fun loadState(): ShowState
}

class MockShowStateRepository(private val context: Context) : ShowStateRepository {
    override fun loadState(): ShowState {
        val jsonText = context.resources.openRawResource(R.raw.mock_show_state)
            .bufferedReader()
            .use { it.readText() }
        return ShowStateJsonParser.parse(JSONObject(jsonText))
    }
}

object ShowStateJsonParser {
    fun parse(root: JSONObject): ShowState {
        val show = root.getJSONObject("show")
        val qlab = root.getJSONObject("qlab")
        val ma2 = root.getJSONObject("ma2")
        val sync = root.getJSONObject("sync")
        val recovery = root.getJSONObject("recovery")

        return ShowState(
            show = ShowInfo(
                title = show.optString("title"),
                mode = show.optString("mode"),
                currentScene = show.optString("currentScene"),
                status = show.optString("status"),
                operator = show.optString("operator")
            ),
            qlab = QLabSection(
                currentCue = parseQlabCue(qlab.getJSONObject("currentCue")),
                nextCue = parseQlabCue(qlab.getJSONObject("nextCue"))
            ),
            ma2 = Ma2Section(
                currentCue = parseMa2Cue(ma2.getJSONObject("currentCue")),
                nextCue = parseMa2Cue(ma2.getJSONObject("nextCue"))
            ),
            sync = SyncState(
                status = sync.optString("status"),
                warnings = sync.optJSONArray("warnings").toStringList()
            ),
            riskIndicators = root.optJSONArray("riskIndicators").mapObjects { json ->
                RiskIndicator(
                    id = json.optString("id"),
                    level = json.optString("level"),
                    label = json.optString("label"),
                    message = json.optString("message")
                )
            },
            upcomingLighting = root.optJSONArray("upcomingLighting").mapObjects { json ->
                UpcomingLightingCue(
                    id = json.optString("id"),
                    cue = json.optString("cue"),
                    label = json.optString("label"),
                    notes = json.optString("notes")
                )
            },
            checklist = root.optJSONArray("checklist").mapObjects { json ->
                ChecklistItem(
                    id = json.optString("id"),
                    label = json.optString("label"),
                    done = json.optBoolean("done")
                )
            },
            watchdog = root.optJSONArray("watchdog").mapObjects { json ->
                WatchdogItem(
                    id = json.optString("id"),
                    label = json.optString("label"),
                    status = json.optString("status")
                )
            },
            eventLog = root.optJSONArray("eventLog").mapObjects { json ->
                EventLogItem(
                    time = json.optString("time"),
                    type = json.optString("type"),
                    message = json.optString("message")
                )
            },
            recovery = RecoveryState(
                title = recovery.optString("title"),
                expectedQlabCue = recovery.optString("expectedQlabCue"),
                expectedMa2Cue = recovery.optString("expectedMa2Cue"),
                steps = recovery.optJSONArray("steps").toStringList()
            )
        )
    }

    private fun parseQlabCue(json: JSONObject): QLabCue {
        return QLabCue(
            id = json.optString("id"),
            number = json.optString("number"),
            name = json.optString("name"),
            type = json.optString("type"),
            memo = json.optString("memo"),
            remainingTime = json.optionalDouble("remainingTime"),
            isRunning = json.optBoolean("isRunning"),
            risk = json.optString("risk", "low")
        )
    }

    private fun parseMa2Cue(json: JSONObject): Ma2Cue {
        return Ma2Cue(
            sequence = json.optString("sequence"),
            cue = json.optString("cue"),
            name = json.optString("name"),
            memo = json.optString("memo"),
            risk = json.optString("risk", "low")
        )
    }

    private fun JSONObject.optionalDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> optString(index) }
    }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return List(length()) { index -> transform(getJSONObject(index)) }
    }
}
