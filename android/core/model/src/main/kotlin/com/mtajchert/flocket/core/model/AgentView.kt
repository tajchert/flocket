package com.mtajchert.flocket.core.model

/** What the desktop Agents panel shows for an agent. [rank] orders urgency, higher first. */
enum class ShownStatus(
    val rank: Int,
) {
    NeedsYou(4),
    Done(3),
    Working(2),
    Idle(1),
}

/** The `herdr.seen` turn, or null when the label is missing or not a number. */
val TerminalRecord.seenTurn: Long? get() = labels[HerdrLabels.SEEN]?.trim()?.toLongOrNull()

val TerminalRecord.isPinned: Boolean get() = !labels[HerdrLabels.PINNED].isNullOrEmpty()

/** Only terminals whose program reported a status are agents; the rest are plain shells. */
val TerminalRecord.isAgent: Boolean get() = status is AgentStatus.Reported

/** Shown status of an agent, null for a non-agent. Same rules as the desktop `Model::status`. */
fun TerminalRecord.shownStatus(): ShownStatus? {
    val reported = status as? AgentStatus.Reported ?: return null
    return when (reported.state) {
        AgentState.Working -> {
            ShownStatus.Working
        }

        AgentState.Blocked, AgentState.Error -> {
            ShownStatus.NeedsYou
        }

        else -> {
            val seen = seenTurn
            when {
                seen != null -> if (turn > seen) ShownStatus.Done else ShownStatus.Idle
                reported.state == AgentState.Done -> ShownStatus.Done
                else -> ShownStatus.Idle
            }
        }
    }
}

/** Shown status of a sub-agent: error counts as needs-you, done as idle, the rest as [shownStatus]. */
fun TerminalRecord.childShownStatus(): ShownStatus? {
    val reported = status as? AgentStatus.Reported ?: return null
    return when (reported.state) {
        AgentState.Error -> ShownStatus.NeedsYou
        AgentState.Done -> ShownStatus.Idle
        else -> shownStatus()
    }
}
