package io.makoion.mobileclaw.data

data class BrowserResearchBrief(
    val query: String,
    val recurringHint: Boolean,
    val requestedDelivery: String,
)

internal fun buildBrowserResearchBrief(prompt: String): BrowserResearchBrief {
    val trimmed = prompt.trim()
    val normalized = trimmed.lowercase()
    val recurringHint = containsAny(
        normalized,
        "daily",
        "every day",
        "every morning",
        "hourly",
        "schedule",
        "scheduled",
        "매일",
        "매주",
        "반복",
        "주기적",
        "정기적",
        "스케줄",
        "알림",
    )
    val requestedDelivery = when {
        containsAny(normalized, "telegram", "텔레그램") -> "Telegram"
        containsAny(normalized, "email", "mail", "이메일", "메일") -> "Email"
        containsAny(normalized, "notification", "notify", "알림") -> "Notification"
        else -> "Chat summary"
    }
    val query = trimmed
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .take(140)
        .ifBlank { "Research request" }
    return BrowserResearchBrief(
        query = query,
        recurringHint = recurringHint,
        requestedDelivery = requestedDelivery,
    )
}

private fun containsAny(
    value: String,
    vararg needles: String,
): Boolean {
    return needles.any { needle -> value.contains(needle) }
}
