package cn.wildfirechat.asr.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时语音识别 WebSocket 代理
 * <p>
 * 客户端连接 /api/stream，本服务为每个客户端连接建立一个到 wf-voice 的连接，双向转发消息。协议与 wf-voice 相同，详见 wf-voice 项目的
 * docs/server-api.md。区别是客户端发送的第一条文本消息（clientId）不转发，由本服务生成唯一的 clientId 发给 wf-voice：
 * wf-voice 用 clientId 区分连接，clientId 重复时识别结果会发到别的连接上。
 * <p>
 * 任意一端断开时关闭另一端。wf-voice 连不上或者断开时，以 1011 关闭客户端连接。
 */
@Component
public class AsrStreamHandler extends AbstractWebSocketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AsrStreamHandler.class);

    static final String ATTR_USER_ID = "user_id";

    // wf-voice 单条消息最大 16KB，超过会断开连接，转发时把大的音频消息拆开
    private static final int MAX_UPSTREAM_MESSAGE_BYTES = 16000;
    // 连上 wf-voice 之前最多缓存的客户端数据，约 3 秒音频（16kHz × 2 字节 × 3 秒）
    private static final int MAX_PENDING_BYTES = 96000;
    // 发送超时时间和发送缓冲上限，超过时认为对端不可用
    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 1024 * 1024;
    // 连接 wf-voice 的超时时间
    private static final String CONNECT_TIMEOUT_MS = "3000";

    // wf-voice 对 client_id 的硬性上限（见 wf-voice 源码 MAX_CLIENT_ID_LEN 与 docs/server-api.md）
    static final int WF_VOICE_MAX_CLIENT_ID_LEN = 64;
    // wf-voice 允许的字符集：仅字母数字与 _ -（其余字符会被判为非法并回 1008）
    private static final java.util.regex.Pattern WF_VOICE_CLIENT_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1," + WF_VOICE_MAX_CLIENT_ID_LEN + "}$");

    /**
     * 生成发给 wf-voice 的 clientId（唯一，且必须满足 wf-voice 的校验规则）。
     *
     * ★ 2026-09-17 修 bug：原实现是 prefix + "-" + UUID(32位) = 野火 UID(32 字符) 时共 65 字符，
     *   超过 wf-voice 的 64 上限 → wf-voice 回 "[ERROR] invalid clientId" 并以 Code 1008 关闭连接，
     *   表现为**所有客户端**的实时语音识别(WS)都失败。这里把 prefix 按剩余额度截短（完整保留 32 位
     *   随机后缀，128 位随机量，不同连接不会撞号），使长度恒 ≤ 64。
     */
    static String buildClientId(String userId) {
        // wf-voice 保存录音时会用 clientId 作为文件名，只保留安全字符
        String prefix = userId == null ? "anonymous" : userId.replaceAll("[^A-Za-z0-9_-]", "_");
        String suffix = UUID.randomUUID().toString().replace("-", "");   // 32 字符随机后缀
        int room = WF_VOICE_MAX_CLIENT_ID_LEN - 1 - suffix.length();     // 预留给 prefix 的额度（含中间的 '-'）
        if (room < 1) room = 1;
        if (prefix.length() > room) {
            LOG.warn("userId 长度 {} 过长，按 wf-voice 上限 {} 截短为 {} 字符（原始 UID 不受影响，仅用于本连接标识）",
                    prefix.length(), WF_VOICE_MAX_CLIENT_ID_LEN, room);
            prefix = prefix.substring(0, room);
        }
        return prefix + "-" + suffix;
    }

    /**
     * 启动自检（2026-09-17 添加）：直接用**真实生成函数**跑几组典型入参，
     * 现场看日志即可确认生成的 clientId 满足 wf-voice 规则，不必等客户端真连上来。
     * 任一用例不合规会打 ERROR，一眼可见。
     */
    @javax.annotation.PostConstruct
    void logClientIdLimitSelfCheck() {
        String[][] cases = new String[][] {
                { "32 字符 UID（野火标准）", "0123456789abcdef0123456789abcdef" },
                { "64 字符 UID（超长）",     "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" },
                { "含非法字符的 UID",        "user@example.com/#1" },
                { "未鉴权（anonymous）",     null },
        };
        int bad = 0;
        for (String[] c : cases) {
            String id = buildClientId(c[1]);
            boolean ok = WF_VOICE_CLIENT_ID_PATTERN.matcher(id).matches();
            if (!ok) bad++;
            LOG.info("自检·clientId[{}/上限{}]：{} → id=\"{}\" 长度={} 合规={}",
                    c[0], WF_VOICE_MAX_CLIENT_ID_LEN, c[1] == null ? "null" : c[1], id, id.length(), ok);
        }
        if (bad > 0) {
            LOG.error("自检失败：有 {} 组入参生成的 clientId 不符合 wf-voice 规则，实时语音识别将无法工作", bad);
        } else {
            LOG.info("自检通过：全部入参生成的 clientId 均符合 wf-voice 规则（长度 ≤ {}，字符集 [A-Za-z0-9_-]）",
                    WF_VOICE_MAX_CLIENT_ID_LEN);
        }
    }

    @Value("${asr.stream_server_url}")
    private String mStreamServerUrl;

    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();

    // key 是客户端连接的 session id
    private final Map<String, ProxySession> proxySessions = new ConcurrentHashMap<>();

    public AsrStreamHandler() {
        webSocketClient.setUserProperties(Collections.<String, Object>singletonMap("org.apache.tomcat.websocket.IO_TIMEOUT_MS", CONNECT_TIMEOUT_MS));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = (String) session.getAttributes().get(ATTR_USER_ID);
        ProxySession proxy = new ProxySession(session, userId);
        proxySessions.put(session.getId(), proxy);
        LOG.info("[{}] client connected, userId={}, active sessions={}", proxy.clientId, userId, proxySessions.size());
        proxy.connectUpstream();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy != null) {
            proxy.onClientText(message.getPayload());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy != null) {
            proxy.onClientBinary(message.getPayload());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        ProxySession proxy = proxySessions.get(session.getId());
        if (proxy != null) {
            LOG.warn("[{}] client transport error: {}", proxy.clientId, exception.toString());
            proxy.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ProxySession proxy = proxySessions.remove(session.getId());
        if (proxy != null) {
            proxy.close(null);
            LOG.info("[{}] client closed, status={}, duration={}ms, audio={}ms, active sessions={}", proxy.clientId, status,
                    System.currentTimeMillis() - proxy.startTime, proxy.getAudioBytes() / 32, proxySessions.size());
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception e) {
            LOG.debug("close websocket session failed: {}", e.toString());
        }
    }

    /**
     * 一个客户端连接，以及对应的 wf-voice 连接
     */
    private class ProxySession {
        // 发给 wf-voice 的 clientId，同时用于日志
        final String clientId;
        final long startTime = System.currentTimeMillis();
        private final WebSocketSession client;

        // 连上 wf-voice 之前收到的客户端消息
        private final Queue<WebSocketMessage<?>> pending = new ArrayDeque<>();
        private int pendingBytes;
        private boolean clientIdReceived;
        private long audioBytes;
        // 连上 wf-voice 之后才有值
        private WebSocketSession upstream;
        private volatile boolean closed;

        ProxySession(WebSocketSession session, String userId) {
            client = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
            // 生成发给 wf-voice 的 clientId（内部已按 wf-voice 的 64 字符上限做截短，见 buildClientId）
            clientId = buildClientId(userId);
            // 诊断（2026-09-17 添加）：把发给 wf-voice 的 clientId 完整打出来（含长度），
            // 便于与 wf-voice 侧日志逐字对齐。
            LOG.info("[{}] userId={}, 生成 clientId（发给 wf-voice）= {}, 长度={}", clientId, userId, clientId,
                    clientId.length());
            if (clientId.length() > WF_VOICE_MAX_CLIENT_ID_LEN) {
                LOG.warn("[{}] clientId 长度 {} 超过 wf-voice 上限 {}（将由 wf-voice 以 invalid clientId 拒绝）",
                        clientId, clientId.length(), WF_VOICE_MAX_CLIENT_ID_LEN);
            }
        }

        void connectUpstream() {
            if (!StringUtils.hasText(mStreamServerUrl)) {
                LOG.error("[{}] asr.stream_server_url is not configured", clientId);
                close(CloseStatus.SERVER_ERROR.withReason("asr server not configured"));
                return;
            }
            LOG.info("[{}] 连接语音引擎（wf-voice）: {}", clientId, mStreamServerUrl);
            webSocketClient.doHandshake(new UpstreamHandler(), new WebSocketHttpHeaders(), URI.create(mStreamServerUrl))
                    .addCallback(upstreamSession -> {
                    }, e -> {
                        LOG.error("[{}] connect asr server {} failed: {}", clientId, mStreamServerUrl, e.toString());
                        close(CloseStatus.SERVER_ERROR.withReason("asr server unavailable"));
                    });
        }

        synchronized void onUpstreamConnected(WebSocketSession session) {
            if (closed) {
                closeQuietly(session, CloseStatus.NORMAL);
                return;
            }
            LOG.info("[{}] asr server connected in {}ms", clientId, System.currentTimeMillis() - startTime);
            upstream = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
            if (!sendUpstream(new TextMessage(clientId))) {
                return;
            }
            int pendingCount = pending.size();
            WebSocketMessage<?> message;
            while ((message = pending.poll()) != null) {
                if (!sendUpstream(message)) {
                    return;
                }
            }
            if (pendingCount > 0) {
                LOG.info("[{}] flushed {} pending messages buffered before asr server connected", clientId, pendingCount);
            }
            pendingBytes = 0;
        }

        synchronized void onClientText(String text) {
            if (closed) {
                return;
            }
            if (!clientIdReceived) {
                // 客户端的第一条文本消息是 clientId，不转发
                clientIdReceived = true;
                LOG.info("[{}] first text message (clientId) not forwarded: {}", clientId, text);
                return;
            }
            LOG.info("[{}] client command at +{}ms: {}", clientId, System.currentTimeMillis() - startTime, text);
            forward(new TextMessage(text));
        }

        synchronized void onClientBinary(ByteBuffer payload) {
            if (closed) {
                return;
            }
            audioBytes += payload.remaining();
            while (payload.hasRemaining()) {
                byte[] data = new byte[Math.min(payload.remaining(), MAX_UPSTREAM_MESSAGE_BYTES)];
                payload.get(data);
                if (!forward(new BinaryMessage(data))) {
                    return;
                }
            }
        }

        synchronized long getAudioBytes() {
            return audioBytes;
        }

        /**
         * 转发客户端消息给 wf-voice，还没连上时先缓存
         */
        private boolean forward(WebSocketMessage<?> message) {
            if (upstream != null) {
                return sendUpstream(message);
            }
            pendingBytes += message.getPayloadLength();
            if (pendingBytes > MAX_PENDING_BYTES) {
                LOG.warn("[{}] asr server is not connected, too much pending data", clientId);
                close(CloseStatus.SERVICE_OVERLOAD.withReason("asr server not ready"));
                return false;
            }
            pending.add(message);
            return true;
        }

        private boolean sendUpstream(WebSocketMessage<?> message) {
            try {
                upstream.sendMessage(message);
                return true;
            } catch (Exception e) {
                LOG.warn("[{}] send to asr server failed: {}", clientId, e.toString());
                close(CloseStatus.SERVER_ERROR.withReason("asr server error"));
                return false;
            }
        }

        void onUpstreamText(String text) {
            if (closed) {
                return;
            }
            long receiveTime = System.currentTimeMillis();
            try {
                client.sendMessage(new TextMessage(text));
            } catch (Exception e) {
                LOG.warn("[{}] send to client failed: {}", clientId, e.toString());
                close(CloseStatus.SERVER_ERROR);
                return;
            }
            long sendDuration = System.currentTimeMillis() - receiveTime;
            String preview = text.length() > 80 ? text.substring(0, 80) + "..." : text;
            if (sendDuration > 100) {
                // 写客户端阻塞会反压上游读取，导致后续识别结果延迟
                LOG.warn("[{}] result at +{}ms, send to client blocked {}ms: {}", clientId, receiveTime - startTime, sendDuration, preview);
            } else if (text.startsWith("[PARTIAL]")) {
                LOG.info("[{}] partial result at +{}ms: {}", clientId, receiveTime - startTime, preview);
            } else {
                LOG.info("[{}] result at +{}ms: {}", clientId, receiveTime - startTime, preview);
            }
        }

        void onUpstreamClosed(CloseStatus status) {
            if (!closed) {
                LOG.warn("[{}] asr server closed, status={}", clientId, status);
                close(CloseStatus.SERVER_ERROR.withReason("asr server closed"));
            }
        }

        /**
         * 关闭两端的连接，可以重复调用
         *
         * @param clientStatus 关闭客户端连接的状态，客户端已经断开时传 null
         */
        synchronized void close(CloseStatus clientStatus) {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            pendingBytes = 0;
            if (upstream != null) {
                closeQuietly(upstream, CloseStatus.NORMAL);
            }
            if (clientStatus != null) {
                closeQuietly(client, clientStatus);
            }
        }

        private class UpstreamHandler extends AbstractWebSocketHandler {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) {
                onUpstreamConnected(session);
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                onUpstreamText(message.getPayload());
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                LOG.warn("[{}] asr server transport error: {}", clientId, exception.toString());
                close(CloseStatus.SERVER_ERROR.withReason("asr server error"));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                onUpstreamClosed(status);
            }
        }
    }
}
