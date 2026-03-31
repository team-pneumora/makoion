package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ImapMailboxGatewayTest {
    @Test
    fun `classifier marks marketing mail as promotional`() {
        val result = classifyMailboxMessage(
            MailboxMessageSnapshot(
                messageKey = "m1",
                subject = "Huge sale newsletter",
                sender = "news@example.com",
                preview = "unsubscribe and save 30 percent",
                receivedAtEpochMillis = 0L,
                hasListUnsubscribe = true,
            ),
        )

        assertEquals(EmailTriageClassification.Promotional, result.classification)
    }

    @Test
    fun `classifier marks action mail as important`() {
        val result = classifyMailboxMessage(
            MailboxMessageSnapshot(
                messageKey = "m2",
                subject = "Urgent contract approval needed",
                sender = "founder@example.com",
                preview = "Please review the contract today.",
                receivedAtEpochMillis = 0L,
                hasListUnsubscribe = false,
            ),
        )

        assertEquals(EmailTriageClassification.Important, result.classification)
    }

    @Test
    fun `classifier leaves ambiguous mail in review queue`() {
        val result = classifyMailboxMessage(
            MailboxMessageSnapshot(
                messageKey = "m3",
                subject = "Weekly check-in",
                sender = "teammate@example.com",
                preview = "Here is the update from this week.",
                receivedAtEpochMillis = 0L,
                hasListUnsubscribe = false,
            ),
        )

        assertEquals(EmailTriageClassification.Review, result.classification)
    }
}
