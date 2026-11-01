package com.mtajchert.flocket.journey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement

/*
 * Compose helpers for device journeys. They wait for a node instead of asserting at once: the app reacts to a
 * real network, so a node may appear a few hundred milliseconds later.
 */

const val DEFAULT_WAIT_MS = 5_000L

/** Waits until at least one node matches [matcher]. */
fun ComposeTestRule.waitFor(
    matcher: SemanticsMatcher,
    timeoutMs: Long = DEFAULT_WAIT_MS,
) = waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

/** Waits until no node matches [matcher]. */
fun ComposeTestRule.waitForGone(
    matcher: SemanticsMatcher,
    timeoutMs: Long = DEFAULT_WAIT_MS,
) = waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

/** Scrolls the node into view when it sits in a scrollable container; a no-op on a fixed screen. */
fun SemanticsNodeInteraction.scrolledTo() = apply { runCatching { performScrollTo() } }

/** Clicks the clickable node with exactly [text], after waiting for it. */
fun ComposeTestRule.clickText(
    text: String,
    timeoutMs: Long = DEFAULT_WAIT_MS,
) = click(hasText(text) and hasClickAction(), timeoutMs)

/** Clicks the node whose content description is [description] (icon buttons), after waiting for it. */
fun ComposeTestRule.clickDescription(
    description: String,
    timeoutMs: Long = DEFAULT_WAIT_MS,
) = click(hasContentDescription(description), timeoutMs)

/** Clicks the first node matching [matcher], after waiting for it and scrolling it into view. */
fun ComposeTestRule.click(
    matcher: SemanticsMatcher,
    timeoutMs: Long = DEFAULT_WAIT_MS,
) {
    waitFor(matcher, timeoutMs)
    onAllNodes(matcher)[0].scrolledTo().performClick()
}

/** Replaces the text of a Material text field: editable, with [label] on the field or on a child. */
fun ComposeTestRule.fill(
    label: String,
    value: String,
) {
    val matcher = hasSetTextAction() and (hasText(label) or hasAnyDescendant(hasText(label)))
    waitFor(matcher)
    onNode(matcher).scrolledTo().performTextReplacement(value)
}

/** The text of the first node matching [matcher], after waiting for it. */
fun ComposeTestRule.textOf(
    matcher: SemanticsMatcher,
    timeoutMs: Long = DEFAULT_WAIT_MS,
): String {
    waitFor(matcher, timeoutMs)
    return onAllNodes(matcher)[0]
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString("") { it.text }
}

/** Calls [block] every 200 ms until it returns non-null, for conditions on the Mac (`hc ls`, `hc read`). */
fun <T : Any> poll(
    timeoutMs: Long,
    what: String = "condition",
    block: () -> T?,
): T {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        block()?.let { return it }
        check(System.currentTimeMillis() < end) { "$what not met after $timeoutMs ms" }
        Thread.sleep(POLL_INTERVAL_MS)
    }
}

private const val POLL_INTERVAL_MS = 200L
