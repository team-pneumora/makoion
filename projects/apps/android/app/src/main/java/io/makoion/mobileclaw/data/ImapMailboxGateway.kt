package io.makoion.mobileclaw.data

import java.util.Properties
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder

data class MailboxValidationResult(
    val connected: Boolean,
    val summary: String,
    val lastError: String? = null,
    val inboxCount: Int = 0,
)

data class MailboxMessageSnapshot(
    val messageKey: String,
    val subject: String,
    val sender: String,
    val preview: String,
    val receivedAtEpochMillis: Long?,
    val hasListUnsubscribe: Boolean,
)

data class MailboxClassificationResult(
    val classification: EmailTriageClassification,
    val reason: String,
)

data class MailboxTriageRunResult(
    val scannedCount: Int,
    val promotionalMovedCount: Int,
    val importantMessages: List<MailboxMessageSnapshot>,
    val reviewMessages: List<MailboxMessageSnapshot>,
    val persistedItems: List<EmailTriageWriteRecord>,
)

interface MailboxGateway {
    suspend fun validate(
        config: MailboxConnectionConfig,
        password: String,
    ): MailboxValidationResult

    suspend fun triage(
        config: MailboxConnectionConfig,
        password: String,
        automationId: String,
    ): MailboxTriageRunResult
}

class ImapMailboxGateway : MailboxGateway {
    override suspend fun validate(
        config: MailboxConnectionConfig,
        password: String,
    ): MailboxValidationResult {
        return runCatching {
            withStore(config, password) { store ->
                val inbox = store.getFolder(config.inboxFolder)
                require(inbox.exists()) {
                    "Inbox folder ${config.inboxFolder} does not exist."
                }
                ensureFolder(store, config.promotionsFolder)
                MailboxValidationResult(
                    connected = true,
                    summary = "Validated ${config.username}@${config.host}:${config.port} with inbox ${config.inboxFolder} and promotions folder ${config.promotionsFolder}.",
                    inboxCount = inbox.messageCount,
                )
            }
        }.getOrElse { error ->
            MailboxValidationResult(
                connected = false,
                summary = "Mailbox validation failed.",
                lastError = error.message ?: error::class.java.simpleName,
            )
        }
    }

    override suspend fun triage(
        config: MailboxConnectionConfig,
        password: String,
        automationId: String,
    ): MailboxTriageRunResult {
        return withStore(config, password) { store ->
            val inbox = store.getFolder(config.inboxFolder)
            require(inbox.exists()) {
                "Inbox folder ${config.inboxFolder} does not exist."
            }
            val promotionsFolder = ensureFolder(store, config.promotionsFolder)
            var expungeOnClose = false
            inbox.open(Folder.READ_WRITE)
            try {
                val messageCount = inbox.messageCount
                if (messageCount == 0) {
                    return@withStore MailboxTriageRunResult(
                        scannedCount = 0,
                        promotionalMovedCount = 0,
                        importantMessages = emptyList(),
                        reviewMessages = emptyList(),
                        persistedItems = emptyList(),
                    )
                }
                val startIndex = (messageCount - maxScannedMessages + 1).coerceAtLeast(1)
                val messages = inbox.getMessages(startIndex, messageCount)
                inbox.fetch(
                    messages,
                    FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(FetchProfile.Item.CONTENT_INFO)
                    },
                )
                val promotionalMessages = mutableListOf<Message>()
                val importantMessages = mutableListOf<MailboxMessageSnapshot>()
                val reviewMessages = mutableListOf<MailboxMessageSnapshot>()
                val persistedItems = mutableListOf<EmailTriageWriteRecord>()
                messages.asSequence()
                    .filterNot { it.isExpunged }
                    .sortedByDescending { it.receivedDate?.time ?: 0L }
                    .forEach { message ->
                        val snapshot = messageSnapshot(inbox, message)
                        val classification = classifyMailboxMessage(snapshot)
                        when (classification.classification) {
                            EmailTriageClassification.Promotional -> {
                                promotionalMessages += message
                                persistedItems += snapshot.toWriteRecord(
                                    mailboxId = config.mailboxId,
                                    automationId = automationId,
                                    classification = EmailTriageClassification.Promotional,
                                    actionLabel = "Moved to ${config.promotionsFolder}",
                                    reason = classification.reason,
                                )
                            }
                            EmailTriageClassification.Important -> {
                                importantMessages += snapshot
                                persistedItems += snapshot.toWriteRecord(
                                    mailboxId = config.mailboxId,
                                    automationId = automationId,
                                    classification = EmailTriageClassification.Important,
                                    actionLabel = "Alerted",
                                    reason = classification.reason,
                                )
                            }
                            EmailTriageClassification.Review -> {
                                reviewMessages += snapshot
                                persistedItems += snapshot.toWriteRecord(
                                    mailboxId = config.mailboxId,
                                    automationId = automationId,
                                    classification = EmailTriageClassification.Review,
                                    actionLabel = "Queued for review",
                                    reason = classification.reason,
                                )
                            }
                            EmailTriageClassification.Kept -> {
                                persistedItems += snapshot.toWriteRecord(
                                    mailboxId = config.mailboxId,
                                    automationId = automationId,
                                    classification = EmailTriageClassification.Kept,
                                    actionLabel = "Kept in inbox",
                                    reason = classification.reason,
                                )
                            }
                        }
                    }
                if (promotionalMessages.isNotEmpty()) {
                    inbox.copyMessages(promotionalMessages.toTypedArray(), promotionsFolder)
                    promotionalMessages.forEach { message ->
                        message.setFlag(Flags.Flag.DELETED, true)
                    }
                    expungeOnClose = true
                }
                MailboxTriageRunResult(
                    scannedCount = messages.size,
                    promotionalMovedCount = promotionalMessages.size,
                    importantMessages = importantMessages,
                    reviewMessages = reviewMessages,
                    persistedItems = persistedItems,
                )
            } finally {
                inbox.close(expungeOnClose)
            }
        }
    }

    private fun <T> withStore(
        config: MailboxConnectionConfig,
        password: String,
        block: (Store) -> T,
    ): T {
        val store = openStore(config, password)
        return try {
            block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    private fun openStore(
        config: MailboxConnectionConfig,
        password: String,
    ): Store {
        val session = Session.getInstance(
            Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.partialfetch", "false")
                put("mail.imaps.connectiontimeout", imapTimeoutMs.toString())
                put("mail.imaps.timeout", imapTimeoutMs.toString())
                put("mail.mime.address.strict", "false")
            },
        )
        return session.getStore("imaps").apply {
            connect(config.host, config.port, config.username, password)
        }
    }

    private fun ensureFolder(
        store: Store,
        folderName: String,
    ): Folder {
        val folder = store.getFolder(folderName)
        if (!folder.exists()) {
            folder.create(Folder.HOLDS_MESSAGES)
        }
        return folder
    }

    companion object {
        private const val imapTimeoutMs = 15_000
        private const val maxScannedMessages = 15
    }
}

internal fun classifyMailboxMessage(snapshot: MailboxMessageSnapshot): MailboxClassificationResult {
    val normalized = listOf(snapshot.subject, snapshot.sender, snapshot.preview)
        .joinToString(" ")
        .lowercase()
    return when {
        snapshot.hasListUnsubscribe ||
            containsAny(
                normalized,
                "unsubscribe",
                "newsletter",
                "promotion",
                "promo",
                "sale",
                "discount",
                "coupon",
                "marketing",
                "광고",
                "프로모션",
                "이벤트",
                "세일",
                "할인",
                "수신거부",
                "news@",
                "noreply",
                "no-reply",
            ) -> MailboxClassificationResult(
                classification = EmailTriageClassification.Promotional,
                reason = "List-unsubscribe or marketing language indicates a promotional message.",
            )
        containsAny(
            normalized,
            "urgent",
            "action required",
            "security",
            "password",
            "login",
            "invoice",
            "payment",
            "contract",
            "meeting",
            "interview",
            "approval",
            "verify",
            "important",
            "critical",
            "긴급",
            "보안",
            "결제",
            "계약",
            "회의",
            "면접",
            "승인",
            "확인 필요",
            "중요",
        ) && !containsAny(normalized, "newsletter", "promotion", "sale", "discount", "광고", "프로모션") ->
            MailboxClassificationResult(
                classification = EmailTriageClassification.Important,
                reason = "The subject or preview contains direct-action or high-signal work keywords.",
            )
        else -> MailboxClassificationResult(
            classification = EmailTriageClassification.Review,
            reason = "The message is neither clearly promotional nor clearly urgent, so it should stay in the review queue.",
        )
    }
}

private fun messageSnapshot(
    folder: Folder,
    message: Message,
): MailboxMessageSnapshot {
    val preview = extractPlainText(message).replace(Regex("\\s+"), " ").trim().take(280)
    val sender = message.from?.firstOrNull()?.toMailboxLabel().orEmpty().ifBlank { "Unknown sender" }
    return MailboxMessageSnapshot(
        messageKey = messageKey(folder, message),
        subject = message.subject?.trim().orEmpty().ifBlank { "(No subject)" },
        sender = sender,
        preview = preview,
        receivedAtEpochMillis = message.receivedDate?.time ?: message.sentDate?.time,
        hasListUnsubscribe = message.getHeader("List-Unsubscribe")?.isNotEmpty() == true,
    )
}

private fun messageKey(
    folder: Folder,
    message: Message,
): String {
    val headerKey = message.getHeader("Message-ID")?.firstOrNull()?.trim().orEmpty()
    if (headerKey.isNotBlank()) {
        return headerKey
    }
    val uidKey = (folder as? UIDFolder)?.getUID(message)?.takeIf { it > 0 }?.toString().orEmpty()
    if (uidKey.isNotBlank()) {
        return uidKey
    }
    return buildString {
        append(message.subject?.trim().orEmpty())
        append("|")
        append(message.receivedDate?.time ?: message.sentDate?.time ?: 0L)
        append("|")
        append(message.from?.joinToString { it.toString() }.orEmpty())
    }
}

private fun extractPlainText(message: Message): String {
    return when (val content = runCatching { message.content }.getOrNull()) {
        is String -> content
        is Multipart -> {
            buildString {
                for (index in 0 until content.count) {
                    val part = content.getBodyPart(index)
                    val text = when {
                        part.isMimeType("text/plain") -> part.content?.toString().orEmpty()
                        part.isMimeType("multipart/*") -> {
                            val nested = part.content as? Multipart
                            nested?.let { nestedMultipart ->
                                buildString {
                                    for (nestedIndex in 0 until nestedMultipart.count) {
                                        append(nestedMultipart.getBodyPart(nestedIndex).content?.toString().orEmpty())
                                        append(' ')
                                    }
                                }
                            }.orEmpty()
                        }
                        else -> ""
                    }
                    if (text.isNotBlank()) {
                        append(text)
                        append(' ')
                    }
                }
            }
        }
        else -> ""
    }
}

private fun Address.toMailboxLabel(): String {
    return toString().replace("\"", "").trim()
}

private fun MailboxMessageSnapshot.toWriteRecord(
    mailboxId: String,
    automationId: String,
    classification: EmailTriageClassification,
    actionLabel: String,
    reason: String,
): EmailTriageWriteRecord {
    return EmailTriageWriteRecord(
        mailboxId = mailboxId,
        automationId = automationId,
        messageKey = messageKey,
        subject = subject,
        sender = sender,
        classification = classification,
        actionLabel = actionLabel,
        reason = reason,
        snippet = preview,
        receivedAtEpochMillis = receivedAtEpochMillis,
    )
}

private fun containsAny(
    normalizedText: String,
    vararg terms: String,
): Boolean {
    return terms.any { normalizedText.contains(it) }
}
