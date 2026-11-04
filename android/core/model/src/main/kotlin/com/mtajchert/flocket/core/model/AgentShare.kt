package com.mtajchert.flocket.core.model

/**
 * One file share an agent made through the `flocket-share` document: [paths] are absolute paths on the
 * machine the terminal runs on. [address] is the terminal's address as the endpoint's core knows it
 * (`t3` locally, `rohan/t3` for a federated [machine]); [machine] is null for the endpoint's own machine.
 */
data class AgentShare(
    val id: String,
    val address: TerminalAddress,
    val machine: String?,
    val paths: List<String>,
    val note: String?,
    val atEpochSeconds: Long,
)
