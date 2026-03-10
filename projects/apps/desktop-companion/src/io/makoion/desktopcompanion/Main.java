package io.makoion.desktopcompanion;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Main {
    private static final int RECEIPT_VERSION = 1;
    private static final Pattern TRANSFER_ID_PATTERN =
        Pattern.compile("\"transfer_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEVICE_NAME_PATTERN =
        Pattern.compile("\"device_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FILE_NAMES_PATTERN =
        Pattern.compile("\"file_names\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING_PATTERN =
        Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        CompanionConfig config = CompanionConfig.fromArgs(args);
        Files.createDirectories(config.inboxDir());

        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.host(), config.port()),
            0
        );
        server.createContext("/health", new HealthHandler(config));
        server.createContext("/api/v1/transfers", new TransferHandler(config));
        server.setExecutor(Executors.newCachedThreadPool());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));

        System.out.println("MobileClaw desktop companion listening on http://"
            + config.host() + ":" + config.port());
        System.out.println("Inbox directory: " + config.inboxDir().toAbsolutePath());
        System.out.println("Trust secret enabled: " + (!config.trustedSecret().isBlank()));

        server.start();
    }

    private record CompanionConfig(
        String host,
        int port,
        Path inboxDir,
        String trustedSecret
    ) {
        static CompanionConfig fromArgs(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length - 1; index += 2) {
                values.put(args[index], args[index + 1]);
            }
            String host = values.getOrDefault(
                "--host",
                envOrDefault("MOBILECLAW_COMPANION_HOST", "0.0.0.0")
            );
            int port = Integer.parseInt(values.getOrDefault(
                "--port",
                envOrDefault("MOBILECLAW_COMPANION_PORT", "8787")
            ));
            Path inboxDir = Path.of(values.getOrDefault(
                "--inbox-dir",
                envOrDefault("MOBILECLAW_COMPANION_INBOX", "inbox")
            ));
            String trustedSecret = values.getOrDefault(
                "--trusted-secret",
                envOrDefault("MOBILECLAW_COMPANION_SECRET", "")
            );
            return new CompanionConfig(host, port, inboxDir, trustedSecret);
        }

        private static String envOrDefault(String key, String fallback) {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private static final class HealthHandler implements HttpHandler {
        private final CompanionConfig config;

        private HealthHandler(CompanionConfig config) {
            this.config = config;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            String response = "{"
                + "\"status\":\"ok\","
                + "\"host\":\"" + escapeJson(config.host()) + "\","
                + "\"port\":" + config.port() + ","
                + "\"trust_enabled\":" + (!config.trustedSecret().isBlank()) + ","
                + "\"inbox_dir\":\"" + escapeJson(config.inboxDir().toAbsolutePath().toString()) + "\","
                + "\"materialized_transfer_count\":" + countTransferDirectories(config.inboxDir())
                + "}";
            sendJson(exchange, 200, response);
        }

        private long countTransferDirectories(Path inboxDir) throws IOException {
            if (!Files.exists(inboxDir)) {
                return 0L;
            }
            try (var paths = Files.list(inboxDir)) {
                return paths.filter(Files::isDirectory).count();
            }
        }
    }

    private static final class TransferHandler implements HttpHandler {
        private final CompanionConfig config;
        private final Map<String, Integer> oneShotFaultAttempts = new ConcurrentHashMap<>();

        private TransferHandler(CompanionConfig config) {
            this.config = config;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }

            Headers headers = exchange.getRequestHeaders();
            String presentedSecret = headers.getFirst("X-MobileClaw-Trusted-Secret");
            if (!config.trustedSecret().isBlank() && !config.trustedSecret().equals(presentedSecret)) {
                sendJson(exchange, 401, "{\"error\":\"invalid_trusted_secret\"}");
                return;
            }

            String contentType = headers.getFirst("Content-Type");
            String debugMode = headerOrDefault(headers, "X-MobileClaw-Debug-Receipt-Mode", "normal")
                .toLowerCase();
            if (contentType != null && contentType.contains("application/zip")) {
                handleZipTransfer(exchange, debugMode);
                return;
            }

            handleJsonTransfer(exchange, debugMode);
        }

        private void handleJsonTransfer(HttpExchange exchange, String debugMode) throws IOException {
            int responseTimeoutMs = parseResponseTimeoutMillis(exchange.getRequestHeaders());
            if (shouldForceConnectionDrop(debugMode, "disconnect_once", headerOrDefault(
                exchange.getRequestHeaders(),
                "X-MobileClaw-Transfer-Id",
                "pending-json"
            ))) {
                exchange.close();
                return;
            }
            if (shouldForceTimeout(debugMode, "timeout_once", headerOrDefault(
                exchange.getRequestHeaders(),
                "X-MobileClaw-Transfer-Id",
                "pending-json"
            ))) {
                delayBeyondClientTimeout(responseTimeoutMs);
                exchange.close();
                return;
            }
            String payload = readUtf8(exchange.getRequestBody());
            String transferId = extractTransferId(payload);
            String deviceName = extractDeviceName(payload);
            var fileNames = extractFileNames(payload);
            if (shouldForceSingleShotMode(debugMode, "retry_once", transferId)) {
                sendJson(exchange, 503, retryOncePayload(transferId));
                return;
            }
            Path transferDir = createTransferDirectory(config.inboxDir(), transferId);
            Path requestedFilesDir = transferDir.resolve("requested-files");
            Files.createDirectories(requestedFilesDir);

            Path manifestPath = transferDir.resolve("manifest.json");
            Files.writeString(manifestPath, payload, StandardCharsets.UTF_8);
            Files.writeString(
                transferDir.resolve("summary.txt"),
                buildSummary(transferId, deviceName, fileNames),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                transferDir.resolve("README.txt"),
                "This companion endpoint materializes manifest-only requests as placeholders "
                    + "and extracts direct archive uploads when payload bytes are available.\n",
                StandardCharsets.UTF_8
            );

            for (int index = 0; index < fileNames.size(); index++) {
                String fileName = fileNames.get(index);
                String placeholderName = String.format(
                    "%02d-%s.request.txt",
                    index + 1,
                    safePathSegment(fileName)
                );
                Files.writeString(
                    requestedFilesDir.resolve(placeholderName),
                    buildPlaceholder(transferId, deviceName, fileName),
                    StandardCharsets.UTF_8
                );
            }

            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"delivery_mode\":\"manifest_only\","
                + "\"transfer_id\":\"" + escapeJson(transferId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(transferDir.getFileName().toString()) + "\","
                + "\"requested_count\":" + fileNames.size() + ","
                + "\"status_detail\":\"Manifest captured for later materialization.\""
                + "}";
            String partialResponse = "{"
                + "\"status\":\"accepted\","
                + "\"delivery_mode\":\"manifest_only\","
                + "\"transfer_id\":\"" + escapeJson(transferId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(transferDir.getFileName().toString()) + "\","
                + "\"status_detail\":\"Debug mode returned a partial manifest receipt.\""
                + "}";
            sendDebugAwareSuccess(
                exchange,
                debugMode,
                transferId,
                response,
                partialResponse,
                responseTimeoutMs
            );
        }

        private void handleZipTransfer(HttpExchange exchange, String debugMode) throws IOException {
            Headers headers = exchange.getRequestHeaders();
            int responseTimeoutMs = parseResponseTimeoutMillis(headers);
            String transferId = headerOrDefault(
                headers,
                "X-MobileClaw-Transfer-Id",
                "transfer-" + UUID.randomUUID()
            );
            String deviceName = headerOrDefault(
                headers,
                "X-MobileClaw-Device-Name",
                "Unknown device"
            );
            String deliveryMode = headerOrDefault(
                headers,
                "X-MobileClaw-Delivery-Mode",
                "archive_zip"
            );
            if (shouldForceConnectionDrop(debugMode, "disconnect_once", transferId)) {
                exchange.close();
                return;
            }
            if (shouldForceTimeout(debugMode, "timeout_once", transferId)) {
                delayBeyondClientTimeout(responseTimeoutMs);
                exchange.close();
                return;
            }
            if (shouldForceSingleShotMode(debugMode, "retry_once", transferId)) {
                sendJson(exchange, 503, retryOncePayload(transferId));
                return;
            }
            Path transferDir = createTransferDirectory(config.inboxDir(), transferId);
            ArchiveExtractionSummary extractionSummary = extractArchive(exchange.getRequestBody(), transferDir);
            Path receivedNote = transferDir.resolve("received.txt");
            Files.writeString(
                receivedNote,
                "Transfer: " + transferId + "\n"
                    + "From: " + deviceName + "\n"
                    + "Delivery mode: " + deliveryMode + "\n"
                    + "Extracted entries: " + extractionSummary.extractedEntries() + "\n"
                    + "File entries: " + extractionSummary.fileEntryCount() + "\n",
                StandardCharsets.UTF_8
            );

            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"delivery_mode\":\"" + escapeJson(deliveryMode) + "\","
                + "\"transfer_id\":\"" + escapeJson(transferId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(transferDir.getFileName().toString()) + "\","
                + "\"extracted_entries\":" + extractionSummary.extractedEntries() + ","
                + "\"file_entry_count\":" + extractionSummary.fileEntryCount() + ","
                + "\"status_detail\":\""
                + escapeJson(statusDetailForZipMode(deliveryMode))
                + "\""
                + "}";
            String partialResponse = "{"
                + "\"status\":\"accepted\","
                + "\"delivery_mode\":\"" + escapeJson(deliveryMode) + "\","
                + "\"transfer_id\":\"" + escapeJson(transferId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(transferDir.getFileName().toString()) + "\","
                + "\"status_detail\":\"Debug mode returned a partial archive receipt.\""
                + "}";
            sendDebugAwareSuccess(
                exchange,
                debugMode,
                transferId,
                response,
                partialResponse,
                responseTimeoutMs
            );
        }

        private String extractTransferId(String payload) {
            Matcher matcher = TRANSFER_ID_PATTERN.matcher(payload);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "transfer-" + UUID.randomUUID();
        }

        private String extractDeviceName(String payload) {
            Matcher matcher = DEVICE_NAME_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Unknown device";
        }

        private java.util.List<String> extractFileNames(String payload) {
            Matcher blockMatcher = FILE_NAMES_PATTERN.matcher(payload);
            if (!blockMatcher.find()) {
                return java.util.List.of();
            }

            Matcher itemMatcher = JSON_STRING_PATTERN.matcher(blockMatcher.group(1));
            var names = new java.util.ArrayList<String>();
            while (itemMatcher.find()) {
                names.add(unescapeJson(itemMatcher.group(1)));
            }
            return names;
        }

        private Path createTransferDirectory(Path inboxDir, String transferId) throws IOException {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
            Path transferDir = inboxDir.resolve(timestamp + "-" + safePathSegment(transferId));
            Files.createDirectories(transferDir);
            return transferDir;
        }

        private ArchiveExtractionSummary extractArchive(InputStream inputStream, Path transferDir)
            throws IOException {
            int extractedEntries = 0;
            int fileEntryCount = 0;
            try (ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    Path outputPath = transferDir.resolve(entry.getName()).normalize();
                    if (!outputPath.startsWith(transferDir)) {
                        throw new IOException("Zip entry attempted to escape the transfer directory.");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(outputPath);
                    } else {
                        Files.createDirectories(outputPath.getParent());
                        Files.copy(zipInputStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
                        extractedEntries++;
                        String normalizedEntryName = entry.getName().replaceFirst("^\\./", "");
                        if (normalizedEntryName.startsWith("files/")) {
                            fileEntryCount++;
                        }
                    }
                    zipInputStream.closeEntry();
                }
            }
            return new ArchiveExtractionSummary(extractedEntries, fileEntryCount);
        }

        private String buildSummary(
            String transferId,
            String deviceName,
            java.util.List<String> fileNames
        ) {
            StringBuilder builder = new StringBuilder();
            builder.append("MobileClaw transfer materialized").append('\n');
            builder.append("Transfer: ").append(transferId).append('\n');
            builder.append("From: ").append(deviceName).append('\n');
            builder.append("Requested files: ").append(fileNames.size()).append('\n');
            builder.append('\n');
            if (fileNames.isEmpty()) {
                builder.append("No file names were provided in the manifest.").append('\n');
            } else {
                for (String fileName : fileNames) {
                    builder.append("- ").append(fileName).append('\n');
                }
            }
            builder.append('\n');
            builder.append("Companion status: manifest and placeholders were materialized successfully.")
                .append('\n');
            return builder.toString();
        }

        private String buildPlaceholder(
            String transferId,
            String deviceName,
            String fileName
        ) {
            return "Requested file placeholder\n"
                + "Transfer: " + transferId + "\n"
                + "From: " + deviceName + "\n"
                + "File: " + fileName + "\n"
                + "Status: The desktop companion has not received binary content yet.\n";
        }

        private String headerOrDefault(
            Headers headers,
            String key,
            String fallback
        ) {
            String value = headers.getFirst(key);
            return value == null || value.isBlank() ? fallback : value;
        }

        private boolean shouldForceSingleShotMode(
            String debugMode,
            String expectedMode,
            String transferId
        ) {
            if (!expectedMode.equals(debugMode)) {
                return false;
            }
            String key = expectedMode + ":" + transferId;
            int attempts = oneShotFaultAttempts.merge(key, 1, Integer::sum);
            return attempts == 1;
        }

        private boolean shouldForceTimeout(String debugMode, String expectedMode, String transferId) {
            return shouldForceSingleShotMode(debugMode, expectedMode, transferId);
        }

        private boolean shouldForceConnectionDrop(
            String debugMode,
            String expectedMode,
            String transferId
        ) {
            return shouldForceSingleShotMode(debugMode, expectedMode, transferId);
        }

        private String retryOncePayload(String transferId) {
            return "{"
                + "\"error\":\"debug_retry_once\","
                + "\"transfer_id\":\"" + escapeJson(transferId) + "\","
                + "\"status_detail\":\"Debug mode forced a single retryable failure before materialization.\","
                + "\"retry_after_seconds\":5"
                + "}";
        }

        private void sendDebugAwareSuccess(
            HttpExchange exchange,
            String debugMode,
            String transferId,
            String successPayload,
            String partialPayload,
            int responseTimeoutMs
        ) throws IOException {
            if ("delayed_ack".equals(debugMode)) {
                delayAcknowledgement();
            }
            if ("malformed_receipt".equals(debugMode)) {
                sendString(
                    exchange,
                    202,
                    "accepted-but-malformed-" + transferId,
                    "text/plain; charset=utf-8"
                );
                return;
            }
            if ("partial_receipt".equals(debugMode)) {
                sendJson(exchange, 202, partialPayload);
                return;
            }
            sendJson(exchange, 202, successPayload);
        }

        private int parseResponseTimeoutMillis(Headers headers) {
            String raw = headerOrDefault(headers, "X-MobileClaw-Response-Timeout-Ms", "4000");
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return 4000;
            }
        }

        private void delayAcknowledgement() {
            try {
                Thread.sleep(6_000L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        private void delayBeyondClientTimeout(int responseTimeoutMs) {
            long delayMillis = Math.max(responseTimeoutMs + 1_500L, 6_000L);
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        private String statusDetailForZipMode(String deliveryMode) {
            if ("archive_zip_streaming".equals(deliveryMode)) {
                return "Streaming archive payload extracted into the transfer directory.";
            }
            return "Archive payload extracted into the transfer directory.";
        }
    }

    private record ArchiveExtractionSummary(
        int extractedEntries,
        int fileEntryCount
    ) {
    }

    private static String readUtf8(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String payload)
        throws IOException {
        sendString(exchange, statusCode, payload, "application/json; charset=utf-8");
    }

    private static void sendString(
        HttpExchange exchange,
        int statusCode,
        String payload,
        String contentType
    ) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private static String unescapeJson(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private static String safePathSegment(String value) {
        String sanitized = value
            .replaceAll("[\\\\/:*?\"<>|]+", "_")
            .replaceAll("\\s+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        if (sanitized.isBlank()) {
            return "item";
        }
        return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
    }
}
