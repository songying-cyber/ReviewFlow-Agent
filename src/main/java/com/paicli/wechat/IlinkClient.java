package com.paicli.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class IlinkClient {
    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OkHttpClient http;

    public IlinkClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(40))
                .writeTimeout(Duration.ofSeconds(15))
                .build());
    }

    IlinkClient(OkHttpClient http) {
        this.http = http;
    }

    public WechatQrLogin startQrLogin(String botType) throws IOException {
        String endpoint = "ilink/bot/get_bot_qrcode?bot_type=" + encode(botType == null || botType.isBlank() ? "3" : botType);
        JsonNode node = MAPPER.readTree(post(DEFAULT_BASE_URL, endpoint, "{}", null, Duration.ofSeconds(15)));
        String qrcode = node.path("qrcode").asText(node.path("qrcode_id").asText(""));
        String url = node.path("qrcode_img_content").asText(node.path("qrcode_url").asText(""));
        if (qrcode.isBlank() || url.isBlank()) {
            throw new IOException("获取微信二维码失败: " + node);
        }
        return new WechatQrLogin(qrcode, url);
    }

    public WechatLoginResult pollQrStatus(String qrcodeId) throws IOException {
        String endpoint = "ilink/bot/get_qrcode_status?qrcode=" + encode(qrcodeId);
        JsonNode node = MAPPER.readTree(get(DEFAULT_BASE_URL, endpoint, Duration.ofSeconds(40)));
        String status = node.path("status").asText("");
        if ("confirmed".equals(status)) {
            return new WechatLoginResult(
                    true,
                    false,
                    status,
                    node.path("bot_token").asText(""),
                    node.path("ilink_bot_id").asText(""),
                    node.path("baseurl").asText(DEFAULT_BASE_URL),
                    node.path("ilink_user_id").asText(""),
                    "connected");
        }
        return new WechatLoginResult(
                false,
                "expired".equals(status),
                status,
                null,
                null,
                null,
                null,
                node.path("retmsg").asText(status));
    }

    public WechatUpdate getUpdates(WechatAccount account, long timeoutMs) throws IOException {
        String body = MAPPER.createObjectNode()
                .put("get_updates_buf", account.syncBuf() == null ? "" : account.syncBuf())
                .toString();
        JsonNode node = MAPPER.readTree(post(account.baseUrl(), "ilink/bot/getupdates", body, account.token(),
                Duration.ofMillis(Math.max(5_000, timeoutMs))));
        int ret = node.path("ret").asInt(node.path("errcode").asInt(0));
        String next = node.path("get_updates_buf").asText(account.syncBuf());
        Long serverTimeout = node.has("longpolling_timeout_ms") ? node.path("longpolling_timeout_ms").asLong() : null;
        List<WechatMessage> messages = parseMessages(node.path("msgs"));
        return new WechatUpdate(ret, node.path("errmsg").asText(node.path("retmsg").asText("")), next, serverTimeout, messages);
    }

    public void sendText(WechatAccount account, String toUserId, String contextToken, String text) throws IOException {
        var item = MAPPER.createObjectNode()
                .put("type", 1)
                .set("text_item", MAPPER.createObjectNode().put("text", text == null ? "" : text));
        var msg = MAPPER.createObjectNode()
                .put("from_user_id", account.accountId())
                .put("to_user_id", toUserId)
                .put("client_id", "paicli-" + System.currentTimeMillis() + "-" + Integer.toHexString(RANDOM.nextInt()))
                .put("message_type", 2)
                .put("message_state", 2)
                .put("context_token", contextToken == null ? "" : contextToken);
        msg.putArray("item_list").add(item);
        var body = MAPPER.createObjectNode().set("msg", msg);
        post(account.baseUrl(), "ilink/bot/sendmessage", body.toString(), account.token(), Duration.ofSeconds(15));
    }

    public void sendTyping(WechatAccount account, String toUserId, String contextToken, int status) throws IOException {
        JsonNode config = MAPPER.readTree(post(account.baseUrl(), "ilink/bot/getconfig",
                MAPPER.createObjectNode()
                        .put("ilink_user_id", toUserId)
                        .put("context_token", contextToken == null ? "" : contextToken)
                        .toString(),
                account.token(),
                Duration.ofSeconds(10)));
        String ticket = config.path("typing_ticket").asText("");
        if (ticket.isBlank()) {
            return;
        }
        String body = MAPPER.createObjectNode()
                .put("ilink_user_id", toUserId)
                .put("typing_ticket", ticket)
                .put("status", status)
                .toString();
        post(account.baseUrl(), "ilink/bot/sendtyping", body, account.token(), Duration.ofSeconds(10));
    }

    public void notifyStart(WechatAccount account) throws IOException {
        post(account.baseUrl(), "ilink/bot/msg/notifystart", "{}", account.token(), Duration.ofSeconds(10));
    }

    public void notifyStop(WechatAccount account) throws IOException {
        post(account.baseUrl(), "ilink/bot/msg/notifystop", "{}", account.token(), Duration.ofSeconds(10));
    }

    private String get(String baseUrl, String endpoint, Duration timeout) throws IOException {
        Request request = new Request.Builder()
                .url(url(baseUrl, endpoint))
                .headers(okhttp3.Headers.of(commonHeaders(null)))
                .get()
                .build();
        return execute(request);
    }

    private String post(String baseUrl, String endpoint, String body, String token, Duration timeout) throws IOException {
        Request request = new Request.Builder()
                .url(url(baseUrl, endpoint))
                .headers(okhttp3.Headers.of(commonHeaders(token)))
                .post(RequestBody.create(body == null ? "{}" : body, JSON))
                .build();
        return execute(request);
    }

    private String execute(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("iLink HTTP " + response.code() + ": " + body);
            }
            return body;
        }
    }

    private static java.util.Map<String, String> commonHeaders(String token) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("AuthorizationType", "ilink_bot_token");
        headers.put("X-WECHAT-UIN", randomUin());
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private static String randomUin() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String url(String baseUrl, String endpoint) {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        return base.replaceAll("/+$", "") + "/" + endpoint.replaceAll("^/+", "");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<WechatMessage> parseMessages(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<WechatMessage> messages = new ArrayList<>();
        for (JsonNode msg : node) {
            String from = msg.path("from_user_id").asText("");
            String context = msg.path("context_token").asText("");
            String id = msg.path("message_id").asText(msg.path("seq").asText(""));
            StringBuilder text = new StringBuilder();
            List<WechatMediaItem> media = new ArrayList<>();
            JsonNode items = msg.path("item_list");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String type = normalizeType(item.path("type").asText(""));
                    if (item.has("text_item")) {
                        appendText(text, item.path("text_item").path("text").asText(""));
                    } else if (item.has("voice_item")) {
                        appendText(text, item.path("voice_item").path("text").asText(""));
                    } else if (item.has("image_item")) {
                        media.add(parseMedia("image", item.path("image_item"), null));
                    } else if (item.has("file_item")) {
                        JsonNode file = item.path("file_item");
                        media.add(parseMedia("file", file, file.path("file_name").asText("")));
                        appendText(text, "[用户发送了文件: " + file.path("file_name").asText("unknown") + "]");
                    } else if ("text".equals(type)) {
                        appendText(text, item.path("text").asText(""));
                    }
                }
            }
            messages.add(new WechatMessage(id, from, context, text.toString().trim(), media));
        }
        return messages;
    }

    private static WechatMediaItem parseMedia(String type, JsonNode node, String fileName) {
        JsonNode media = node.path("media");
        if (media.isMissingNode() || media.isNull()) {
            media = node.path("cdn_media");
        }
        String aesKey = media.path("aes_key").asText(node.path("aeskey").asText(""));
        return new WechatMediaItem(
                type,
                fileName,
                node.path("mime_type").asText(""),
                media.path("encrypt_query_param").asText(""),
                aesKey);
    }

    private static String normalizeType(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    private static void appendText(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(value);
    }
}
