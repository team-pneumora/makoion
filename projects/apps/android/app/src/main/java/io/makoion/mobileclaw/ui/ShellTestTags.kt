package io.makoion.mobileclaw.ui

object ShellTestTags {
    const val navChat = "shell_nav_chat"
    const val navDashboard = "shell_nav_dashboard"
    const val navHistory = "shell_nav_history"
    const val navSettings = "shell_nav_settings"

    const val chatScreenList = "chat_screen_list"
    const val chatComposerInput = "chat_composer_input"
    const val chatComposerSend = "chat_composer_send"

    fun scheduledAutomationCard(automationId: String): String = "scheduled_automation_card_$automationId"

    fun scheduledAutomationActivateButton(automationId: String): String =
        "scheduled_automation_activate_$automationId"

    fun scheduledAutomationRunButton(automationId: String): String =
        "scheduled_automation_run_$automationId"
}
