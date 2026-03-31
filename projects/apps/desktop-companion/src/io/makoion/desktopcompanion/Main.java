package io.makoion.desktopcompanion;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
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
    private static final String SUPPORTED_CAPABILITIES_JSON =
        "[\"files.transfer\",\"session.notify\",\"app.open\",\"workflow.run\","
            + "\"mcp.connect\",\"mcp.tools.list\",\"mcp.tools.call\","
            + "\"browser.navigate\",\"browser.extract\",\"browser.submit\"]";
    private static final String MCP_DISCOVERY_CAPABILITIES_JSON =
        "[\"mcp.connect\",\"mcp.tools.list\",\"mcp.tools.call\",\"mcp.skills.sync\","
            + "\"session.notify\",\"app.open\",\"workflow.run\",\"files.transfer\","
            + "\"browser.navigate\",\"browser.extract\",\"browser.submit\"]";
    private static final String MCP_DISCOVERY_TOOLS_JSON =
        "[\"desktop.session.notify\",\"desktop.app.open\",\"desktop.workflow.run\","
            + "\"files.transfer.receive\",\"browser.navigate\",\"browser.extract\","
            + "\"browser.submit\",\"browser.research.plan\",\"api.request.ingest\"]";
    private static final String MCP_DISCOVERY_SKILL_BUNDLES_JSON =
        "["
            + "{\"bundle_id\":\"desktop_action_bridge\",\"title\":\"Desktop action bridge\","
            + "\"summary\":\"Routes guarded notify, open, and workflow actions through the paired companion.\","
            + "\"tool_names\":[\"desktop.session.notify\",\"desktop.app.open\",\"desktop.workflow.run\"],"
            + "\"version_label\":\"2026.03\"},"
            + "{\"bundle_id\":\"browser_research_handoff\",\"title\":\"Browser research handoff\","
            + "\"summary\":\"Runs guarded webpage access and browser research handoff through the paired companion.\","
            + "\"tool_names\":[\"browser.navigate\",\"browser.extract\",\"browser.submit\",\"browser.research.plan\"],"
            + "\"version_label\":\"2026.03\"},"
            + "{\"bundle_id\":\"external_api_ingest\",\"title\":\"External API ingest\","
            + "\"summary\":\"Registers API ingest work for later guarded execution and parsing.\","
            + "\"tool_names\":[\"api.request.ingest\"],"
            + "\"version_label\":\"2026.03\"}"
            + "]";
    private static final String MCP_DISCOVERY_TOOL_SCHEMAS_JSON =
        "["
            + "{\"name\":\"desktop.session.notify\",\"title\":\"Desktop Session Notify\","
            + "\"summary\":\"Show a guarded desktop notification on the paired companion.\","
            + "\"input_schema_summary\":\"title:string, body:string\","
            + "\"requires_confirmation\":false},"
            + "{\"name\":\"desktop.app.open\",\"title\":\"Desktop App Open\","
            + "\"summary\":\"Open an approved desktop surface such as inbox or latest transfer.\","
            + "\"input_schema_summary\":\"target_kind:string, target_label?:string, open_mode:string\","
            + "\"requires_confirmation\":true},"
            + "{\"name\":\"desktop.workflow.run\",\"title\":\"Desktop Workflow Run\","
            + "\"summary\":\"Run a guarded named workflow through the paired companion.\","
            + "\"input_schema_summary\":\"workflow_id:string, workflow_label?:string, run_mode:string\","
            + "\"requires_confirmation\":true},"
                + "{\"name\":\"files.transfer.receive\",\"title\":\"Receive Transfer Payload\","
                + "\"summary\":\"Materialize transfer payloads into the companion inbox.\","
                + "\"input_schema_summary\":\"transfer archive payload\","
                + "\"requires_confirmation\":false},"
                + "{\"name\":\"browser.navigate\",\"title\":\"Browser Navigate\","
                + "\"summary\":\"Open a webpage through the companion bridge and capture readable text.\","
                + "\"input_schema_summary\":\"url:string, max_chars?:number\","
                + "\"requires_confirmation\":false},"
                + "{\"name\":\"browser.extract\",\"title\":\"Browser Extract\","
                + "\"summary\":\"Extract readable text from a target webpage through the companion bridge.\","
                + "\"input_schema_summary\":\"url:string, max_chars?:number\","
                + "\"requires_confirmation\":false},"
                + "{\"name\":\"browser.submit\",\"title\":\"Browser Submit\","
                + "\"summary\":\"Submit a guarded browser action plan through the paired companion.\","
                + "\"input_schema_summary\":\"url:string, action_plan:string\","
                + "\"requires_confirmation\":true},"
                + "{\"name\":\"browser.research.plan\",\"title\":\"Browser Research Plan\","
                + "\"summary\":\"Stage a browser research brief for later guarded execution.\","
                + "\"input_schema_summary\":\"topic:string, objective:string\","
                + "\"requires_confirmation\":false},"
                + "{\"name\":\"api.request.ingest\",\"title\":\"API Request Ingest\","
            + "\"summary\":\"Register API ingest work for later guarded execution and parsing.\","
            + "\"input_schema_summary\":\"request template + auth binding\","
            + "\"requires_confirmation\":false}"
            + "]";
    private static final String MCP_DISCOVERY_WORKFLOWS_JSON =
        "[\"open_latest_transfer\",\"open_actions_folder\",\"open_latest_action\"]";
    private static final Pattern TRANSFER_ID_PATTERN =
        Pattern.compile("\"transfer_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REQUEST_ID_PATTERN =
        Pattern.compile("\"request_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEVICE_NAME_PATTERN =
        Pattern.compile("\"device_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TITLE_PATTERN =
        Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BODY_PATTERN =
        Pattern.compile("\"body\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SOURCE_PATTERN =
        Pattern.compile("\"source\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TARGET_KIND_PATTERN =
        Pattern.compile("\"target_kind\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TARGET_LABEL_PATTERN =
        Pattern.compile("\"target_label\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OPEN_MODE_PATTERN =
        Pattern.compile("\"open_mode\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern WORKFLOW_ID_PATTERN =
        Pattern.compile("\"workflow_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern WORKFLOW_LABEL_PATTERN =
        Pattern.compile("\"workflow_label\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RUN_MODE_PATTERN =
        Pattern.compile("\"run_mode\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOOL_NAME_PATTERN =
        Pattern.compile("\"tool_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL_PATTERN =
        Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAX_CHARS_PATTERN =
        Pattern.compile("\"max_chars\"\\s*:\\s*(\\d+)");
    private static final Pattern FILE_NAMES_PATTERN =
        Pattern.compile("\"file_names\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING_PATTERN =
        Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern HTML_TITLE_PATTERN =
        Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HTML_SCRIPT_STYLE_PATTERN =
        Pattern.compile("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>");
    private static final Pattern HTML_TAG_PATTERN =
        Pattern.compile("(?is)<[^>]+>");
    private static final Object TRAY_LOCK = new Object();
    private static TrayIcon trayIcon;
    private static boolean trayIconInitializationAttempted;
    private static final int DEFAULT_BROWSER_TEXT_LIMIT = 2200;
    private static final int MAX_BROWSER_FETCH_CHARS = 12000;

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
        server.createContext("/api/v1/mcp/discovery", new McpDiscoveryHandler(config));
        server.createContext("/api/v1/mcp/tools/call", new McpToolCallHandler(config));
        server.createContext("/api/v1/transfers", new TransferHandler(config));
        server.createContext("/api/v1/transfers/pending", new PendingTransferHandler(config));
        server.createContext("/api/v1/session/notify", new SessionNotifyHandler(config));
        server.createContext("/api/v1/app/open", new AppOpenHandler(config));
        server.createContext("/api/v1/workflow/run", new WorkflowRunHandler(config));
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
                + "\"capabilities\":" + SUPPORTED_CAPABILITIES_JSON + ","
                + "\"notification_display_supported\":" + isNotificationDisplaySupported() + ","
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

    private static final class McpDiscoveryHandler implements HttpHandler {
        private final CompanionConfig config;

        private McpDiscoveryHandler(CompanionConfig config) {
            this.config = config;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }

            Headers headers = exchange.getRequestHeaders();
            String presentedSecret = headers.getFirst("X-MobileClaw-Trusted-Secret");
            if (!config.trustedSecret().isBlank() && !config.trustedSecret().equals(presentedSecret)) {
                sendJson(exchange, 401, "{\"error\":\"invalid_trusted_secret\"}");
                return;
            }

            String authLabel = config.trustedSecret().isBlank()
                ? "Local network policy gate"
                : "Trusted secret + local policy gate";
            String response = "{"
                + "\"status\":\"ok\","
                + "\"server_label\":\"Makoion desktop companion\","
                + "\"transport_label\":\"Direct HTTP bridge\","
                + "\"auth_label\":\"" + escapeJson(authLabel) + "\","
                + "\"capabilities\":" + MCP_DISCOVERY_CAPABILITIES_JSON + ","
                + "\"tool_names\":" + MCP_DISCOVERY_TOOLS_JSON + ","
                + "\"tool_schemas\":" + MCP_DISCOVERY_TOOL_SCHEMAS_JSON + ","
                + "\"skill_bundles\":" + MCP_DISCOVERY_SKILL_BUNDLES_JSON + ","
                + "\"workflow_ids\":" + MCP_DISCOVERY_WORKFLOWS_JSON + ","
                + "\"notification_display_supported\":" + isNotificationDisplaySupported() + ","
                + "\"inbox_dir\":\"" + escapeJson(config.inboxDir().toAbsolutePath().toString()) + "\","
                + "\"status_detail\":\"Companion MCP discovery is ready to advertise desktop action, browser handoff, and API ingest tools.\""
                + "}";
            sendJson(exchange, 200, response);
        }
    }

    private static final class McpToolCallHandler implements HttpHandler {
        private final CompanionConfig config;

        private McpToolCallHandler(CompanionConfig config) {
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

            String payload = readUtf8(exchange.getRequestBody());
            String requestId = extractPatternGroup(payload, REQUEST_ID_PATTERN, "mcp-call-" + UUID.randomUUID());
            String toolName = extractPatternGroup(payload, TOOL_NAME_PATTERN, "");
            if (toolName.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"missing_tool_name\"}");
                return;
            }

            Path actionDir = createActionDirectory(config.inboxDir(), "mcp-" + safePathSegment(toolName), requestId);
            Files.writeString(actionDir.resolve("request.json"), payload, StandardCharsets.UTF_8);

            switch (toolName) {
                case "browser.navigate":
                case "browser.extract":
                    handleBrowserTool(exchange, actionDir, requestId, toolName, payload);
                    return;
                case "browser.submit":
                    sendJson(
                        exchange,
                        501,
                        "{"
                            + "\"error\":\"tool_not_implemented\","
                            + "\"tool_name\":\"browser.submit\","
                            + "\"status_detail\":\"Guarded browser form submission is not wired yet.\""
                            + "}"
                    );
                    return;
                default:
                    sendJson(
                        exchange,
                        404,
                        "{"
                            + "\"error\":\"unknown_tool\","
                            + "\"tool_name\":\"" + escapeJson(toolName) + "\","
                            + "\"status_detail\":\"The companion does not expose that MCP tool.\""
                            + "}"
                    );
            }
        }

        private void handleBrowserTool(
            HttpExchange exchange,
            Path actionDir,
            String requestId,
            String toolName,
            String payload
        ) throws IOException {
            String rawUrl = extractPatternGroup(payload, URL_PATTERN, "");
            String normalizedUrl = normalizeBrowserUrl(rawUrl);
            if (normalizedUrl == null) {
                sendJson(
                    exchange,
                    400,
                    "{"
                        + "\"error\":\"invalid_url\","
                        + "\"tool_name\":\"" + escapeJson(toolName) + "\","
                        + "\"status_detail\":\"Only http and https URLs are supported for webpage access.\""
                        + "}"
                );
                return;
            }

            int maxChars = parseMaxChars(payload);
            BrowserFetchResult result = fetchBrowserPage(normalizedUrl, maxChars);
            Files.writeString(
                actionDir.resolve("summary.txt"),
                buildBrowserSummary(toolName, requestId, result),
                StandardCharsets.UTF_8
            );
            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"tool_name\":\"" + escapeJson(toolName) + "\","
                + "\"request_id\":\"" + escapeJson(requestId) + "\","
                + "\"executed\":" + result.executed() + ","
                + "\"materialized_dir\":\"" + escapeJson(actionDir.getFileName().toString()) + "\","
                + "\"final_url\":\"" + escapeJson(result.finalUrl()) + "\","
                + "\"http_status\":" + result.httpStatus() + ","
                + "\"content_type\":\"" + escapeJson(result.contentType()) + "\","
                + "\"page_title\":\"" + escapeJson(result.pageTitle()) + "\","
                + "\"text_excerpt\":\"" + escapeJson(result.textExcerpt()) + "\","
                + "\"content_text\":\"" + escapeJson(result.contentText()) + "\","
                + "\"status_detail\":\"" + escapeJson(result.detail()) + "\""
                + "}";
            sendJson(exchange, result.executed() ? 202 : 502, response);
        }

        private BrowserFetchResult fetchBrowserPage(String requestUrl, int maxChars) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(requestUrl).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(6_000);
                connection.setReadTimeout(8_000);
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
                connection.setRequestProperty("User-Agent", "MakoionDesktopCompanion/2026.03");
                int responseCode = connection.getResponseCode();
                String finalUrl = connection.getURL().toString();
                String contentType = connection.getContentType();
                InputStream responseStream = responseCode >= 200 && responseCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
                String responseBody = responseStream == null ? "" : readUtf8(responseStream);
                String contentText = collapseWhitespace(
                    stripHtml(responseBody)
                );
                contentText = truncate(contentText, Math.max(400, Math.min(maxChars, MAX_BROWSER_FETCH_CHARS)));
                String pageTitle = extractHtmlTitle(responseBody);
                String excerptSource = !contentText.isBlank() ? contentText : responseBody;
                String excerpt = truncate(collapseWhitespace(stripHtml(excerptSource)), 320);
                if (responseCode >= 200 && responseCode < 300) {
                    return new BrowserFetchResult(
                        true,
                        responseCode,
                        finalUrl,
                        valueOrDefault(contentType, "unknown"),
                        valueOrDefault(pageTitle, ""),
                        valueOrDefault(excerpt, ""),
                        valueOrDefault(contentText, ""),
                        "Fetched webpage content through the companion MCP browser bridge."
                    );
                }
                return new BrowserFetchResult(
                    false,
                    responseCode,
                    finalUrl,
                    valueOrDefault(contentType, "unknown"),
                    valueOrDefault(pageTitle, ""),
                    valueOrDefault(excerpt, ""),
                    valueOrDefault(contentText, ""),
                    "The companion reached the webpage, but the server returned HTTP " + responseCode + "."
                );
            } catch (Exception error) {
                return new BrowserFetchResult(
                    false,
                    0,
                    requestUrl,
                    "unknown",
                    "",
                    "",
                    "",
                    "The companion could not fetch the webpage: " + error.getMessage()
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private int parseMaxChars(String payload) {
            Matcher matcher = MAX_CHARS_PATTERN.matcher(payload);
            if (!matcher.find()) {
                return DEFAULT_BROWSER_TEXT_LIMIT;
            }
            try {
                return Math.max(400, Math.min(Integer.parseInt(matcher.group(1)), MAX_BROWSER_FETCH_CHARS));
            } catch (NumberFormatException ignored) {
                return DEFAULT_BROWSER_TEXT_LIMIT;
            }
        }

        private String normalizeBrowserUrl(String rawUrl) {
            if (rawUrl == null || rawUrl.isBlank()) {
                return null;
            }
            String candidate = rawUrl.trim();
            String lowerCandidate = candidate.toLowerCase();
            if (!lowerCandidate.startsWith("http://") && !lowerCandidate.startsWith("https://")) {
                candidate = "https://" + candidate;
            }
            try {
                URL url = URI.create(candidate).toURL();
                if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
                    return null;
                }
                return url.toString();
            } catch (Exception ignored) {
                return null;
            }
        }

        private String buildBrowserSummary(String toolName, String requestId, BrowserFetchResult result) {
            return "MCP tool: " + toolName + "\n"
                + "Request: " + requestId + "\n"
                + "Executed: " + result.executed() + "\n"
                + "HTTP status: " + result.httpStatus() + "\n"
                + "URL: " + result.finalUrl() + "\n"
                + "Title: " + result.pageTitle() + "\n"
                + "Content type: " + result.contentType() + "\n"
                + "Detail: " + result.detail() + "\n\n"
                + result.contentText() + "\n";
        }

        private String extractHtmlTitle(String html) {
            Matcher matcher = HTML_TITLE_PATTERN.matcher(html);
            if (!matcher.find()) {
                return "";
            }
            return truncate(collapseWhitespace(stripHtml(unescapeHtml(matcher.group(1)))), 160);
        }

        private String stripHtml(String html) {
            if (html == null || html.isBlank()) {
                return "";
            }
            String withoutScripts = HTML_SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
            String withoutTags = HTML_TAG_PATTERN.matcher(withoutScripts).replaceAll(" ");
            return unescapeHtml(withoutTags);
        }

        private String collapseWhitespace(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return value.replaceAll("\\s+", " ").trim();
        }

        private String unescapeHtml(String value) {
            return value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        }

        private String truncate(String value, int limit) {
            if (value == null) {
                return "";
            }
            if (value.length() <= limit) {
                return value;
            }
            return value.substring(0, limit).trim() + "...";
        }

        private String valueOrDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
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
            Path transferDir = createOrReuseTransferDirectory(config.inboxDir(), transferId);
            boolean alreadyMaterialized = hasMaterializedFiles(transferDir);
            Path requestedFilesDir = transferDir.resolve("requested-files");
            if (!alreadyMaterialized) {
                resetRequestedFilesDirectory(requestedFilesDir);
            }

            Path manifestPath = transferDir.resolve("request-manifest.json");
            Files.writeString(manifestPath, payload, StandardCharsets.UTF_8);
            Files.writeString(
                transferDir.resolve("summary.txt"),
                buildSummary(transferId, deviceName, fileNames, alreadyMaterialized),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                transferDir.resolve("README.txt"),
                "This companion endpoint materializes manifest-only requests as placeholders "
                    + "and extracts direct archive uploads when payload bytes are available.\n",
                StandardCharsets.UTF_8
            );

            if (!alreadyMaterialized) {
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
            }

            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"delivery_mode\":\"manifest_only\","
                + "\"transfer_id\":\"" + escapeJson(transferId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(transferDir.getFileName().toString()) + "\","
                + "\"requested_count\":" + fileNames.size() + ","
                + "\"pending_recovery\":" + (!alreadyMaterialized) + ","
                + "\"status_detail\":\""
                + escapeJson(
                    alreadyMaterialized
                        ? "Transfer was already materialized; manifest was refreshed for audit only."
                        : "Manifest captured for later materialization."
                )
                + "\""
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
            Path transferDir = createOrReuseTransferDirectory(config.inboxDir(), transferId);
            boolean recoveredFromManifest = countPendingPlaceholders(transferDir) > 0;
            ArchiveExtractionSummary extractionSummary = extractArchive(exchange.getRequestBody(), transferDir);
            if (recoveredFromManifest) {
                deleteRequestedFilesDirectory(transferDir.resolve("requested-files"));
            }
            Path receivedNote = transferDir.resolve("received.txt");
            Files.writeString(
                receivedNote,
                "Transfer: " + transferId + "\n"
                    + "From: " + deviceName + "\n"
                    + "Delivery mode: " + deliveryMode + "\n"
                    + "Extracted entries: " + extractionSummary.extractedEntries() + "\n"
                    + "File entries: " + extractionSummary.fileEntryCount() + "\n"
                    + "Recovered from manifest: " + recoveredFromManifest + "\n",
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
                + "\"materialized_from_manifest\":" + recoveredFromManifest + ","
                + "\"status_detail\":\""
                + escapeJson(statusDetailForZipMode(deliveryMode, recoveredFromManifest))
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

        private Path createOrReuseTransferDirectory(Path inboxDir, String transferId) throws IOException {
            Path existingTransferDir = findTransferDirectory(inboxDir, transferId);
            if (existingTransferDir != null) {
                Files.createDirectories(existingTransferDir);
                return existingTransferDir;
            }
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
            Path transferDir = inboxDir.resolve(timestamp + "-" + safePathSegment(transferId));
            Files.createDirectories(transferDir);
            return transferDir;
        }

        private void resetRequestedFilesDirectory(Path requestedFilesDir) throws IOException {
            deleteRequestedFilesDirectory(requestedFilesDir);
            Files.createDirectories(requestedFilesDir);
        }

        private void deleteRequestedFilesDirectory(Path requestedFilesDir) throws IOException {
            if (!Files.exists(requestedFilesDir)) {
                return;
            }
            try (var paths = Files.walk(requestedFilesDir)) {
                paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
            } catch (RuntimeException runtimeException) {
                if (runtimeException.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw runtimeException;
            }
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
            java.util.List<String> fileNames,
            boolean alreadyMaterialized
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
            builder.append("Companion status: ");
            if (alreadyMaterialized) {
                builder.append("binary payload already exists for this transfer, so the manifest was recorded without recreating pending placeholders.");
            } else {
                builder.append("manifest and placeholders were materialized successfully.");
            }
            builder.append('\n');
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
                delayAcknowledgement(responseTimeoutMs);
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

        private void delayAcknowledgement(int responseTimeoutMs) {
            long delayMillis = Math.max(responseTimeoutMs + 4_000L, 10_000L);
            try {
                Thread.sleep(delayMillis);
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

        private String statusDetailForZipMode(String deliveryMode, boolean recoveredFromManifest) {
            if ("archive_zip_streaming".equals(deliveryMode)) {
                return recoveredFromManifest
                    ? "Streaming archive payload recovered a pending manifest placeholder."
                    : "Streaming archive payload extracted into the transfer directory.";
            }
            return recoveredFromManifest
                ? "Archive payload recovered a pending manifest placeholder."
                : "Archive payload extracted into the transfer directory.";
        }
    }

    private static final class PendingTransferHandler implements HttpHandler {
        private final CompanionConfig config;

        private PendingTransferHandler(CompanionConfig config) {
            this.config = config;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }

            Headers headers = exchange.getRequestHeaders();
            String presentedSecret = headers.getFirst("X-MobileClaw-Trusted-Secret");
            if (!config.trustedSecret().isBlank() && !config.trustedSecret().equals(presentedSecret)) {
                sendJson(exchange, 401, "{\"error\":\"invalid_trusted_secret\"}");
                return;
            }

            java.util.List<PendingTransferDescriptor> pendingTransferList =
                loadPendingTransfers(config.inboxDir());
            StringBuilder pendingTransfers = new StringBuilder("[");
            boolean first = true;
            for (PendingTransferDescriptor pendingTransfer : pendingTransferList) {
                if (!first) {
                    pendingTransfers.append(',');
                }
                pendingTransfers.append('{')
                    .append("\"transfer_id\":\"").append(escapeJson(pendingTransfer.transferId())).append("\",")
                    .append("\"device_name\":\"").append(escapeJson(pendingTransfer.deviceName())).append("\",")
                    .append("\"materialized_dir\":\"").append(escapeJson(pendingTransfer.materializedDir())).append("\",")
                    .append("\"requested_count\":").append(pendingTransfer.requestedCount()).append(',')
                    .append("\"pending_file_count\":").append(pendingTransfer.pendingFileCount())
                    .append('}');
                first = false;
            }
            pendingTransfers.append(']');

            String response = "{"
                + "\"status\":\"ok\","
                + "\"pending_count\":" + pendingTransferList.size() + ","
                + "\"pending_transfers\":" + pendingTransfers
                + "}";
            sendJson(exchange, 200, response);
        }
    }

    private static Path findTransferDirectory(Path inboxDir, String transferId) throws IOException {
        if (!Files.exists(inboxDir)) {
            return null;
        }
        String directorySuffix = "-" + safePathSegment(transferId);
        try (var paths = Files.list(inboxDir)) {
            return paths
                .filter(Files::isDirectory)
                .filter(path -> !"actions".equalsIgnoreCase(path.getFileName().toString()))
                .filter(path -> path.getFileName().toString().endsWith(directorySuffix))
                .max((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                .orElse(null);
        }
    }

    private static boolean hasMaterializedFiles(Path transferDir) throws IOException {
        Path filesDir = transferDir.resolve("files");
        if (!Files.exists(filesDir)) {
            return false;
        }
        try (var paths = Files.walk(filesDir)) {
            return paths.anyMatch(Files::isRegularFile);
        }
    }

    private static int countPendingPlaceholders(Path transferDir) throws IOException {
        Path requestedFilesDir = transferDir.resolve("requested-files");
        if (!Files.exists(requestedFilesDir)) {
            return 0;
        }
        try (var paths = Files.list(requestedFilesDir)) {
            return (int) paths.filter(Files::isRegularFile).count();
        }
    }

    private static java.util.List<PendingTransferDescriptor> loadPendingTransfers(Path inboxDir)
        throws IOException {
        if (!Files.exists(inboxDir)) {
            return java.util.List.of();
        }

        var pendingTransfers = new java.util.ArrayList<PendingTransferDescriptor>();
        try (var paths = Files.list(inboxDir)) {
            paths.filter(Files::isDirectory)
                .filter(path -> !"actions".equalsIgnoreCase(path.getFileName().toString()))
                .sorted((left, right) -> right.getFileName().toString().compareTo(left.getFileName().toString()))
                .forEach(path -> {
                    try {
                        int pendingFileCount = countPendingPlaceholders(path);
                        if (pendingFileCount <= 0) {
                            return;
                        }
                        TransferManifestDescriptor descriptor = readTransferManifestDescriptor(path);
                        if (descriptor == null) {
                            return;
                        }
                        pendingTransfers.add(
                            new PendingTransferDescriptor(
                                descriptor.transferId(),
                                descriptor.deviceName(),
                                path.getFileName().toString(),
                                descriptor.requestedCount(),
                                pendingFileCount
                            )
                        );
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        } catch (RuntimeException runtimeException) {
            if (runtimeException.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw runtimeException;
        }
        return pendingTransfers;
    }

    private static TransferManifestDescriptor readTransferManifestDescriptor(Path transferDir)
        throws IOException {
        for (Path manifestPath : new Path[] {
            transferDir.resolve("request-manifest.json"),
            transferDir.resolve("manifest.json"),
        }) {
            if (!Files.exists(manifestPath) || Files.isDirectory(manifestPath)) {
                continue;
            }
            String payload = Files.readString(manifestPath, StandardCharsets.UTF_8);
            String transferId = extractPatternGroup(payload, TRANSFER_ID_PATTERN, "");
            if (transferId.isBlank()) {
                continue;
            }
            String deviceName = extractPatternGroup(payload, DEVICE_NAME_PATTERN, "Unknown device");
            java.util.List<String> fileNames = extractFileNamesFromPayload(payload);
            return new TransferManifestDescriptor(
                transferId,
                deviceName,
                fileNames.size()
            );
        }
        return null;
    }

    private static java.util.List<String> extractFileNamesFromPayload(String payload) {
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

    private static String extractPatternGroup(String payload, Pattern pattern, String fallback) {
        Matcher matcher = pattern.matcher(payload);
        if (matcher.find()) {
            return unescapeJson(matcher.group(1));
        }
        return fallback;
    }

    private static final class SessionNotifyHandler implements HttpHandler {
        private final CompanionConfig config;

        private SessionNotifyHandler(CompanionConfig config) {
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

            String payload = readUtf8(exchange.getRequestBody());
            String requestId = extractRequestId(payload);
            String source = extractSource(payload);
            String deviceName = extractDeviceName(payload);
            String title = extractTitle(payload);
            String body = extractBody(payload);

            Path actionDir = createActionDirectory(config.inboxDir(), "notify", requestId);
            Files.writeString(actionDir.resolve("request.json"), payload, StandardCharsets.UTF_8);
            NotificationDisplayResult displayResult = displayNotification(title, body);
            Files.writeString(
                actionDir.resolve("summary.txt"),
                buildNotificationSummary(requestId, source, deviceName, title, body, displayResult),
                StandardCharsets.UTF_8
            );

            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"capability\":\"session.notify\","
                + "\"request_id\":\"" + escapeJson(requestId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(actionDir.getFileName().toString()) + "\","
                + "\"notification_displayed\":" + displayResult.displayed() + ","
                + "\"status_detail\":\"" + escapeJson(displayResult.detail()) + "\""
                + "}";
            sendJson(exchange, 202, response);
        }

        private String extractRequestId(String payload) {
            Matcher matcher = REQUEST_ID_PATTERN.matcher(payload);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "notify-" + UUID.randomUUID();
        }

        private String extractTitle(String payload) {
            Matcher matcher = TITLE_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Makoion session notify";
        }

        private String extractBody(String payload) {
            Matcher matcher = BODY_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Notification body was empty.";
        }

        private String extractSource(String payload) {
            Matcher matcher = SOURCE_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "unknown";
        }

        private String extractDeviceName(String payload) {
            Matcher matcher = DEVICE_NAME_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Unknown device";
        }

        private String buildNotificationSummary(
            String requestId,
            String source,
            String deviceName,
            String title,
            String body,
            NotificationDisplayResult displayResult
        ) {
            return "Makoion companion notification\n"
                + "Request: " + requestId + "\n"
                + "Source: " + source + "\n"
                + "Device: " + deviceName + "\n"
                + "Title: " + title + "\n"
                + "Body: " + body + "\n"
                + "Displayed: " + displayResult.displayed() + "\n"
                + "Detail: " + displayResult.detail() + "\n";
        }
    }

    private static final class AppOpenHandler implements HttpHandler {
        private final CompanionConfig config;

        private AppOpenHandler(CompanionConfig config) {
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

            String payload = readUtf8(exchange.getRequestBody());
            String requestId = extractRequestId(payload);
            String source = extractSource(payload);
            String deviceName = extractDeviceName(payload);
            String targetKind = extractTargetKind(payload);
            String targetLabel = extractTargetLabel(payload);
            String openMode = extractOpenMode(payload);

            Path actionDir = createActionDirectory(config.inboxDir(), "app-open", requestId);
            Files.writeString(actionDir.resolve("request.json"), payload, StandardCharsets.UTF_8);
            DesktopOpenResult openResult = openRequestedTarget(config, targetKind, openMode);
            Files.writeString(
                actionDir.resolve("summary.txt"),
                buildOpenSummary(
                    requestId,
                    source,
                    deviceName,
                    targetKind,
                    targetLabel,
                    openMode,
                    openResult
                ),
                StandardCharsets.UTF_8
            );

            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"capability\":\"app.open\","
                + "\"request_id\":\"" + escapeJson(requestId) + "\","
                + "\"target_kind\":\"" + escapeJson(targetKind) + "\","
                + "\"materialized_dir\":\"" + escapeJson(actionDir.getFileName().toString()) + "\","
                + "\"opened\":" + openResult.opened() + ","
                + "\"opened_path\":\"" + escapeJson(openResult.openedPath()) + "\","
                + "\"status_detail\":\"" + escapeJson(openResult.detail()) + "\""
                + "}";
            sendJson(exchange, 202, response);
        }

        private String extractRequestId(String payload) {
            Matcher matcher = REQUEST_ID_PATTERN.matcher(payload);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "app-open-" + UUID.randomUUID();
        }

        private String extractSource(String payload) {
            Matcher matcher = SOURCE_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "unknown";
        }

        private String extractDeviceName(String payload) {
            Matcher matcher = DEVICE_NAME_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Unknown device";
        }

        private String extractTargetKind(String payload) {
            Matcher matcher = TARGET_KIND_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "inbox";
        }

        private String extractTargetLabel(String payload) {
            Matcher matcher = TARGET_LABEL_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Desktop companion inbox";
        }

        private String extractOpenMode(String payload) {
            Matcher matcher = OPEN_MODE_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "best_effort";
        }

        private String buildOpenSummary(
            String requestId,
            String source,
            String deviceName,
            String targetKind,
            String targetLabel,
            String openMode,
            DesktopOpenResult openResult
        ) {
            return "Makoion companion app.open\n"
                + "Request: " + requestId + "\n"
                + "Source: " + source + "\n"
                + "Device: " + deviceName + "\n"
                + "Target kind: " + targetKind + "\n"
                + "Target label: " + targetLabel + "\n"
                + "Open mode: " + openMode + "\n"
                + "Opened: " + openResult.opened() + "\n"
                + "Opened path: " + openResult.openedPath() + "\n"
                + "Detail: " + openResult.detail() + "\n";
        }
    }

    private static final class WorkflowRunHandler implements HttpHandler {
        private final CompanionConfig config;

        private WorkflowRunHandler(CompanionConfig config) {
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

            String payload = readUtf8(exchange.getRequestBody());
            String requestId = extractRequestId(payload);
            String source = extractSource(payload);
            String deviceName = extractDeviceName(payload);
            String workflowId = extractWorkflowId(payload);
            String workflowLabel = extractWorkflowLabel(payload);
            String runMode = extractRunMode(payload);

            Path actionDir = createActionDirectory(config.inboxDir(), "workflow-run", requestId);
            Files.writeString(actionDir.resolve("request.json"), payload, StandardCharsets.UTF_8);
            WorkflowRunResult workflowResult = runWorkflow(config, workflowId, runMode);
            Files.writeString(
                actionDir.resolve("summary.txt"),
                buildWorkflowSummary(
                    requestId,
                    source,
                    deviceName,
                    workflowId,
                    workflowLabel,
                    runMode,
                    workflowResult
                ),
                StandardCharsets.UTF_8
            );

            String response = "{"
                + "\"status\":\"accepted\","
                + "\"receipt_version\":" + RECEIPT_VERSION + ","
                + "\"acknowledged_at\":\"" + escapeJson(Instant.now().toString()) + "\","
                + "\"capability\":\"workflow.run\","
                + "\"request_id\":\"" + escapeJson(requestId) + "\","
                + "\"workflow_id\":\"" + escapeJson(workflowId) + "\","
                + "\"materialized_dir\":\"" + escapeJson(actionDir.getFileName().toString()) + "\","
                + "\"executed\":" + workflowResult.executed() + ","
                + "\"status_detail\":\"" + escapeJson(workflowResult.detail()) + "\""
                + "}";
            sendJson(exchange, 202, response);
        }

        private String extractRequestId(String payload) {
            Matcher matcher = REQUEST_ID_PATTERN.matcher(payload);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "workflow-run-" + UUID.randomUUID();
        }

        private String extractSource(String payload) {
            Matcher matcher = SOURCE_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "unknown";
        }

        private String extractDeviceName(String payload) {
            Matcher matcher = DEVICE_NAME_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Unknown device";
        }

        private String extractWorkflowId(String payload) {
            Matcher matcher = WORKFLOW_ID_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "unknown";
        }

        private String extractWorkflowLabel(String payload) {
            Matcher matcher = WORKFLOW_LABEL_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "Desktop workflow";
        }

        private String extractRunMode(String payload) {
            Matcher matcher = RUN_MODE_PATTERN.matcher(payload);
            if (matcher.find()) {
                return unescapeJson(matcher.group(1));
            }
            return "best_effort";
        }

        private String buildWorkflowSummary(
            String requestId,
            String source,
            String deviceName,
            String workflowId,
            String workflowLabel,
            String runMode,
            WorkflowRunResult workflowResult
        ) {
            return "Makoion companion workflow.run\n"
                + "Request: " + requestId + "\n"
                + "Source: " + source + "\n"
                + "Device: " + deviceName + "\n"
                + "Workflow id: " + workflowId + "\n"
                + "Workflow label: " + workflowLabel + "\n"
                + "Run mode: " + runMode + "\n"
                + "Executed: " + workflowResult.executed() + "\n"
                + "Detail: " + workflowResult.detail() + "\n";
        }
    }

    private record ArchiveExtractionSummary(
        int extractedEntries,
        int fileEntryCount
    ) {
    }

    private record BrowserFetchResult(
        boolean executed,
        int httpStatus,
        String finalUrl,
        String contentType,
        String pageTitle,
        String textExcerpt,
        String contentText,
        String detail
    ) {
    }

    private record PendingTransferDescriptor(
        String transferId,
        String deviceName,
        String materializedDir,
        int requestedCount,
        int pendingFileCount
    ) {
    }

    private record TransferManifestDescriptor(
        String transferId,
        String deviceName,
        int requestedCount
    ) {
    }

    private record NotificationDisplayResult(
        boolean displayed,
        String detail
    ) {
    }

    private record DesktopOpenResult(
        boolean opened,
        String detail,
        String openedPath
    ) {
    }

    private record WorkflowRunResult(
        boolean executed,
        String detail
    ) {
    }

    private static String readUtf8(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Path createActionDirectory(Path inboxDir, String prefix, String requestId)
        throws IOException {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
        Path actionsDir = inboxDir.resolve("actions");
        Files.createDirectories(actionsDir);
        Path actionDir = actionsDir.resolve(
            timestamp + "-" + safePathSegment(prefix) + "-" + safePathSegment(requestId)
        );
        Files.createDirectories(actionDir);
        return actionDir;
    }

    private static boolean isNotificationDisplaySupported() {
        return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
    }

    private static NotificationDisplayResult displayNotification(String title, String body) {
        if (!isNotificationDisplaySupported()) {
            return new NotificationDisplayResult(
                false,
                "Desktop notification UI is unavailable, so the request was recorded only."
            );
        }
        try {
            TrayIcon icon;
            synchronized (TRAY_LOCK) {
                icon = ensureTrayIcon();
            }
            if (icon == null) {
                return new NotificationDisplayResult(
                    false,
                    "System tray could not be initialized, so the request was recorded only."
                );
            }
            icon.displayMessage(title, body, TrayIcon.MessageType.NONE);
            return new NotificationDisplayResult(
                true,
                "Desktop notification was displayed through the system tray."
            );
        } catch (Exception exception) {
            return new NotificationDisplayResult(
                false,
                "Notification display failed: " + exception.getMessage()
            );
        }
    }

    private static DesktopOpenResult openRequestedTarget(
        CompanionConfig config,
        String targetKind,
        String openMode
    ) {
        if ("record_only".equalsIgnoreCase(openMode)) {
            return new DesktopOpenResult(
                false,
                "Request was recorded without opening a desktop surface.",
                ""
            );
        }
        if (!Desktop.isDesktopSupported()) {
            return new DesktopOpenResult(
                false,
                "Desktop actions are unavailable, so the request was recorded only.",
                ""
            );
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                return new DesktopOpenResult(
                    false,
                    "Desktop OPEN action is unavailable, so the app.open request was recorded only.",
                    ""
                );
            }
            Path targetPath;
            String targetSummary;
            if ("inbox".equalsIgnoreCase(targetKind)) {
                targetPath = config.inboxDir();
                targetSummary = "Companion inbox";
            } else if ("latest_transfer".equalsIgnoreCase(targetKind)) {
                targetPath = findLatestTransferDirectory(config.inboxDir());
                if (targetPath == null) {
                    return new DesktopOpenResult(
                        false,
                        "No materialized transfer directory was found for latest_transfer.",
                        ""
                    );
                }
                targetSummary = "Latest transfer directory";
            } else if ("actions_folder".equalsIgnoreCase(targetKind)) {
                targetPath = ensureActionsDirectory(config.inboxDir());
                targetSummary = "Companion actions directory";
            } else if ("latest_action".equalsIgnoreCase(targetKind)) {
                targetPath = findLatestActionDirectory(config.inboxDir());
                if (targetPath == null) {
                    return new DesktopOpenResult(
                        false,
                        "No materialized action directory was found for latest_action.",
                        ""
                    );
                }
                targetSummary = "Latest companion action directory";
            } else {
                return new DesktopOpenResult(
                    false,
                    "Unknown app.open target kind: " + targetKind,
                    ""
                );
            }
            desktop.open(targetPath.toFile());
            return new DesktopOpenResult(
                true,
                targetSummary + " was opened through the desktop shell.",
                targetPath.toAbsolutePath().toString()
            );
        } catch (Exception exception) {
            return new DesktopOpenResult(
                false,
                "Desktop open failed: " + exception.getMessage(),
                ""
            );
        }
    }

    private static WorkflowRunResult runWorkflow(
        CompanionConfig config,
        String workflowId,
        String runMode
    ) {
        if ("open_latest_transfer".equalsIgnoreCase(workflowId)) {
            if ("record_only".equalsIgnoreCase(runMode)) {
                return new WorkflowRunResult(
                    false,
                    "Workflow request was recorded without executing open_latest_transfer."
                );
            }
            if (!Desktop.isDesktopSupported()) {
                return new WorkflowRunResult(
                    false,
                    "Desktop actions are unavailable, so the workflow request was recorded only."
                );
            }
            try {
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.OPEN)) {
                    return new WorkflowRunResult(
                        false,
                        "Desktop OPEN action is unavailable, so the workflow request was recorded only."
                    );
                }
                Path latestTransferDir = findLatestTransferDirectory(config.inboxDir());
                if (latestTransferDir == null) {
                    return new WorkflowRunResult(
                        false,
                        "No materialized transfer directory was found for open_latest_transfer."
                    );
                }
                desktop.open(latestTransferDir.toFile());
                return new WorkflowRunResult(
                    true,
                    "Latest transfer directory was opened through the desktop shell: "
                        + latestTransferDir.getFileName()
                );
            } catch (Exception exception) {
                return new WorkflowRunResult(
                    false,
                    "Desktop workflow failed: " + exception.getMessage()
                );
            }
        }
        if ("open_actions_folder".equalsIgnoreCase(workflowId)) {
            if ("record_only".equalsIgnoreCase(runMode)) {
                return new WorkflowRunResult(
                    false,
                    "Workflow request was recorded without executing open_actions_folder."
                );
            }
            if (!Desktop.isDesktopSupported()) {
                return new WorkflowRunResult(
                    false,
                    "Desktop actions are unavailable, so the workflow request was recorded only."
                );
            }
            try {
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.OPEN)) {
                    return new WorkflowRunResult(
                        false,
                        "Desktop OPEN action is unavailable, so the workflow request was recorded only."
                    );
                }
                Path actionsDir = ensureActionsDirectory(config.inboxDir());
                desktop.open(actionsDir.toFile());
                return new WorkflowRunResult(
                    true,
                    "Companion actions directory was opened through the desktop shell: "
                        + actionsDir.getFileName()
                );
            } catch (Exception exception) {
                return new WorkflowRunResult(
                    false,
                    "Desktop workflow failed: " + exception.getMessage()
                );
            }
        }
        if ("open_latest_action".equalsIgnoreCase(workflowId)) {
            if ("record_only".equalsIgnoreCase(runMode)) {
                return new WorkflowRunResult(
                    false,
                    "Workflow request was recorded without executing open_latest_action."
                );
            }
            if (!Desktop.isDesktopSupported()) {
                return new WorkflowRunResult(
                    false,
                    "Desktop actions are unavailable, so the workflow request was recorded only."
                );
            }
            try {
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.OPEN)) {
                    return new WorkflowRunResult(
                        false,
                        "Desktop OPEN action is unavailable, so the workflow request was recorded only."
                    );
                }
                Path latestActionDir = findLatestActionDirectory(config.inboxDir());
                if (latestActionDir == null) {
                    return new WorkflowRunResult(
                        false,
                        "No materialized action directory was found for open_latest_action."
                    );
                }
                desktop.open(latestActionDir.toFile());
                return new WorkflowRunResult(
                    true,
                    "Latest companion action directory was opened through the desktop shell: "
                        + latestActionDir.getFileName()
                );
            } catch (Exception exception) {
                return new WorkflowRunResult(
                    false,
                    "Desktop workflow failed: " + exception.getMessage()
                );
            }
        }
        return new WorkflowRunResult(
            false,
            "Unknown workflow id: " + workflowId
        );
    }

    private static Path findLatestTransferDirectory(Path inboxDir) throws IOException {
        if (!Files.exists(inboxDir)) {
            return null;
        }
        try (var paths = Files.list(inboxDir)) {
            return paths
                .filter(Files::isDirectory)
                .filter(path -> !"actions".equalsIgnoreCase(path.getFileName().toString()))
                .max((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                .orElse(null);
        }
    }

    private static Path ensureActionsDirectory(Path inboxDir) throws IOException {
        Path actionsDir = inboxDir.resolve("actions");
        Files.createDirectories(actionsDir);
        return actionsDir;
    }

    private static Path findLatestActionDirectory(Path inboxDir) throws IOException {
        Path actionsDir = inboxDir.resolve("actions");
        if (!Files.exists(actionsDir)) {
            return null;
        }
        try (var paths = Files.list(actionsDir)) {
            return paths
                .filter(Files::isDirectory)
                .max((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                .orElse(null);
        }
    }

    private static TrayIcon ensureTrayIcon() throws Exception {
        if (trayIcon != null) {
            return trayIcon;
        }
        if (trayIconInitializationAttempted) {
            return null;
        }
        trayIconInitializationAttempted = true;

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x14, 0x8A, 0x73));
        graphics.fillRoundRect(0, 0, 16, 16, 6, 6);
        graphics.setColor(new Color(0xF5, 0xF1, 0xE6));
        graphics.fillOval(4, 4, 8, 8);
        graphics.dispose();

        TrayIcon created = new TrayIcon(image, "Makoion desktop companion");
        created.setImageAutoSize(true);
        SystemTray.getSystemTray().add(created);
        trayIcon = created;
        return trayIcon;
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
            .replace("\\/", "/")
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
