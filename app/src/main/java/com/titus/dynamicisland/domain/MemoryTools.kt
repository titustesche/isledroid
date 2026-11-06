package com.titus.dynamicisland.domain

import android.content.Context
import android.text.format.DateUtils
import com.titus.dynamicisland.data.assistant.AssistantCard
import com.titus.dynamicisland.data.assistant.AssistantMemory
import com.titus.dynamicisland.data.assistant.AssistantTool
import com.titus.dynamicisland.data.assistant.CardIcon
import com.titus.dynamicisland.data.assistant.Memory
import com.titus.dynamicisland.data.assistant.ToolException
import com.titus.dynamicisland.data.assistant.ToolInput
import com.titus.dynamicisland.data.assistant.ToolParam
import com.titus.dynamicisland.data.system.Geo
import com.titus.dynamicisland.data.system.Geo.toPoint

/** The assistant's memory (see [AssistantMemory]) and the island's map for remembered places. */
class MemoryTools(
    private val context: Context,
    private val memory: AssistantMemory,
    private val map: MapController,
) {
    fun all(): List<AssistantTool> = listOf(remember, recall, forget, showOnMap)

    private fun tool(name: String, description: String, vararg params: ToolParam, run: (ToolInput) -> String) =
        assistantTool(name, description, *params, run = run)

    private val remember = tool(
        "remember",
        "Store a note to recall later: facts the user tells you to keep (\"my locker is 214\", \"the car is on level three\"). " +
            "With at_current_location the phone's precise position is stored with it: use that for \"remember where I parked\" " +
            "and similar, phrased like \"Car parked\" (add details the user gave, e.g. the level).",
        ToolParam("text", "string", "The note, short and self-explanatory, in the user's language."),
        ToolParam("at_current_location", "boolean", "Also store where the phone is right now.", required = false),
    ) { i ->
        val text = i.string("text")
        val place = if (i.optBool("at_current_location") == true) {
            if (!Geo.hasAny(context)) {
                throw ToolException("No location access. Tell the user to allow location in Dynamic Island → Assistant → Tool permissions.")
            }
            (Geo.current(context) ?: throw ToolException("The phone couldn't find its position right now. Try again outside.")).toPoint()
        } else null
        val m = memory.add(text, place)
        i.show(card(m))
        "Stored (id ${m.id})" + (place?.let { " with the position, accurate to about ${it.accuracyM?.toInt() ?: "?"} metres" } ?: "") + "."
    }

    private val recall = tool(
        "recall",
        "Search your memory. Without a query it lists everything (newest first). Each item has an id, its date and whether it has a place.",
        ToolParam("query", "string", "Optional words to look for.", required = false),
    ) { i ->
        val q = i.optString("query")?.lowercase()
        val found = memory.memories.value.filter { q == null || q.split(' ').any { w -> w.length > 2 && w in it.text.lowercase() } }
        if (found.isEmpty()) "Nothing stored" + (q?.let { " about \"$it\"" } ?: "") + "."
        else found.take(20).joinToString("\n") { describe(it) }
    }

    private val forget = tool(
        "forget",
        "Delete a stored note by its id, e.g. once the user picked the car up or says it's outdated.",
        ToolParam("id", "string", "The note's id."),
    ) { i ->
        if (memory.remove(i.string("id"))) "Deleted." else throw ToolException("No note with that id.")
    }

    private val showOnMap = tool(
        "show_on_map",
        "Open the island's map for a stored place (like the parked car): it shows where it is from here, with direction and " +
            "distance, live, until the user closes it. Use this, not a navigation app, for \"where did I park\", \"take me to my car\" " +
            "and the like. It opens when your answer is over, so keep the answer to a few words.",
        ToolParam("id", "string", "The id of a stored note that has a place."),
        ToolParam("label", "string", "One or two words for the map, e.g. \"Car\".", required = false),
    ) { i ->
        val m = memory.find(i.string("id")) ?: throw ToolException("No note with that id.")
        val place = m.place ?: throw ToolException("That note has no place stored.")
        val label = i.optString("label") ?: m.text.take(24)
        val here = if (Geo.hasAny(context)) Geo.current(context, timeoutS = 4)?.toPoint() else null
        // After the answer: while the assistant talks, it owns the island.
        i.atEnd { map.show(MapTarget(label, place)) }
        if (here == null) "The map opens after your answer."
        else "The map opens after your answer. It's ${Geo.distance(here, place).toInt()} metres away, " +
            "${compass(Geo.bearing(here, place))} of the user."
    }

    /** For the system prompt: what's stored, newest first, at most [limit]. */
    fun promptSection(limit: Int): String {
        val all = memory.memories.value
        if (all.isEmpty()) return "Your memory is empty."
        return "Your memory (newest first; use recall for more, forget when something is outdated):\n" +
            all.take(limit).joinToString("\n") { "- " + describe(it) }
    }

    private fun describe(m: Memory): String {
        val ago = DateUtils.getRelativeTimeSpanString(m.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return "[${m.id}] ${m.text} ($ago${if (m.place != null) ", has a place" else ""})"
    }

    private fun card(m: Memory) = AssistantCard(
        key = "memory-${m.id}",
        icon = if (m.place != null) CardIcon.LOCATION else CardIcon.NOTE,
        title = m.text,
        subtitle = if (m.place != null) "Remembered with the position" else "Remembered",
        label = if (m.place != null) "Remembered · here" else "Note saved",
    )

    private fun compass(deg: Double) = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[
        (((deg + 22.5) % 360) / 45).toInt()
    ]
}
