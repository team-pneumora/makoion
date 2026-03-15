package io.makoion.mobileclaw.data

enum class AgentPlannerMode {
    Answer,
    Question,
    Plan,
    ActionIntent,
    Escalation,
}

data class AgentPlanningTrace(
    val mode: AgentPlannerMode,
    val summary: String,
    val capabilities: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
)
