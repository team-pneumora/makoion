package io.makoion.mobileclaw.data

data class ScheduledAutomationPlan(
    val title: String,
    val scheduleLabel: String,
    val deliveryLabel: String,
    val recurringHint: Boolean,
)

internal fun buildScheduledAutomationPlan(prompt: String): ScheduledAutomationPlan {
    val trimmed = prompt.trim()
    val normalized = trimmed.lowercase()
    val recurringHint = looksLikeScheduledAutomationPrompt(trimmed)
    val scheduleLabel = when {
        containsAny(normalized, "every morning", "daily morning", "매일 아침", "아침마다") -> "Daily morning"
        containsAny(normalized, "daily", "every day", "매일", "매일마다") -> "Daily"
        containsAny(normalized, "weekly", "every week", "매주", "주간") -> "Weekly"
        containsAny(normalized, "hourly", "every hour", "매시간", "시간마다") -> "Hourly"
        else -> "Recurring"
    }
    val deliveryLabel = when {
        containsAny(normalized, "telegram", "텔레그램") -> "Telegram"
        containsAny(normalized, "email", "mail", "이메일", "메일") -> "Email"
        containsAny(normalized, "notification", "notify", "알림") -> "Notification"
        else -> "Chat summary"
    }
    val title = trimmed
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .take(72)
        .ifBlank { "Scheduled automation" }
    return ScheduledAutomationPlan(
        title = title,
        scheduleLabel = scheduleLabel,
        deliveryLabel = deliveryLabel,
        recurringHint = recurringHint,
    )
}

internal fun looksLikeScheduledAutomationPrompt(prompt: String): Boolean {
    val normalized = prompt.trim().lowercase()
    return containsAny(
        normalized,
        "automation",
        "automate",
        "schedule",
        "scheduled",
        "recurring",
        "repeat",
        "every day",
        "every morning",
        "every week",
        "every hour",
        "daily",
        "weekly",
        "hourly",
        "매일",
        "매주",
        "매시간",
        "반복",
        "주기",
        "정기",
        "스케줄",
        "자동화",
        "자동수집",
    )
}

private fun containsAny(
    value: String,
    vararg needles: String,
): Boolean {
    return needles.any { needle -> value.contains(needle) }
}
