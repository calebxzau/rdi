package calebxzhou.rdi.mc.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;

import static calebxzhou.rdi.mc.common.RDI.HOST_ID;
import static calebxzhou.rdi.mc.common.RDI.IHQ_URL;

/**
 * calebxzhou @ 2026-01-06 23:36
 */
public class WebSocketClient {
    private static int reqId = 0;
    private static final Logger lgr = LogManager.getLogger("rdi-ws-client");
    private static final Gson gson = new GsonBuilder().create();
    private static final Type WS_MESSAGE_JSON_TYPE = new TypeToken<WsMessage<JsonElement>>() {
    }.getType();
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final SocketFactory DIRECT_SOCKET_FACTORY = new DirectSocketFactory();
    private static final ProxySelector DIRECT_PROXY_SELECTOR = new DirectProxySelector();
    private static volatile Session currentSession;

    private static final class Session {
        private final String wsUrl;
        private final WsMessageHandler handler;
        private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rdi-ws-reconnect");
            t.setDaemon(true);
            return t;
        });
        private volatile WebSocket webSocket;
        private volatile boolean closed;
        private volatile boolean paused;
        private volatile boolean connecting;

        private Session(String wsUrl, WsMessageHandler handler) {
            this.wsUrl = wsUrl;
            this.handler = handler;
        }
    }

    public static void start(WsMessageHandler handler) {
        stop();
        Session session = new Session("ws://" + IHQ_URL + "/host/play/" + HOST_ID, handler);
        currentSession = session;
        disableJvmProxy();
        attemptConnect(session);
    }
    public static void stop() {
        Session session = currentSession;
        currentSession = null;
        if (session == null) {
            return;
        }
        session.closed = true;
        WebSocket ws = session.webSocket;
        if (ws != null) {
            try {
                ws.disconnect(1000, "server stopping");
            } catch (Exception ex) {
                lgr.warn("Failed to send WebSocket close frame", ex);
            }
        }
        session.reconnectExecutor.shutdownNow();
    }
    public static void pauseReconnect() {
        Session session = currentSession;
        if (session != null) {
            session.paused = true;
        }
    }
    public static <T> boolean sendMessage(WsMessage.Channel channel, T data) {
        if (sendMessage(reqId, channel, data)) {
            reqId++;
            return true;
        }
        return false;
    }
    public static <T> boolean sendMessage(int id, WsMessage.Channel channel, T data) {
        Session session = currentSession;
        WebSocket ws = session == null ? null : session.webSocket;
        if (ws != null && ws.isOpen()) {
            String json = gson.toJson(new WsMessage<T>(id, channel, data));
            lgr.info("Sending message: {}", json);
            ws.sendText(json);
            return true;
        } else {
            lgr.warn("Cannot send message, WebSocket is not connected");
            return false;
        }
    }
    public static <T> T fromJson(JsonElement json, Class<T> type) {
        return gson.fromJson(json, type);
    }
    private WebSocketClient() {

    }

    private static void attemptConnect(Session session) {
        if (!isCurrent(session) || session.paused) {
            return;
        }

        // Check if there's already an active connection
        WebSocket ws = session.webSocket;
        if (ws != null && ws.isOpen()) {
            lgr.debug("WebSocket already connected, skipping connection attempt");
            return;
        }

        // Check if a connection attempt is already in progress
        if (session.connecting) {
            lgr.debug("WebSocket connection already in progress, skipping");
            return;
        }

        session.connecting = true;
        lgr.info("ws try conn");
        try {
            disableJvmProxy();
            WebSocket newWs = new WebSocketFactory()
                    .setSocketFactory(DIRECT_SOCKET_FACTORY)
                    .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                    .createSocket(session.wsUrl)
                    .addListener(new Listener(session));
            if (!isCurrent(session) || session.paused) {
                newWs.disconnect(1000, "server stopping");
                return;
            }
            session.webSocket = newWs;
            newWs.connectAsynchronously();
        } catch (IOException ex) {
            session.connecting = false;
            lgr.error("Failed to connect WebSocket ", ex);
            scheduleReconnect(session);
        }
    }

    private static void scheduleReconnect(Session session) {
        if (!isCurrent(session) || session.paused) {
            return;
        }

        // Don't schedule reconnect if already connected
        WebSocket ws = session.webSocket;
        if (ws != null && ws.isOpen()) {
            lgr.debug("WebSocket already connected, skipping reconnect scheduling");
            return;
        }

        try {
            session.reconnectExecutor.schedule(() -> attemptConnect(session), 5, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // The session was stopped while a reconnect was being scheduled.
        }
        lgr.info("ws reconn 5s");
    }

    private static boolean isCurrent(Session session) {
        return session != null && !session.closed && currentSession == session;
    }

    private static boolean isCurrentSocket(Session session, WebSocket webSocket) {
        return isCurrent(session) && session.webSocket == webSocket;
    }

    private static class Listener extends WebSocketAdapter {
        private final Session session;

        private Listener(Session session) {
            this.session = session;
        }

        @Override
        public void onConnected(WebSocket webSocket, Map<String, List<String>> headers) {
            if (!isCurrentSocket(session, webSocket) || session.paused) {
                webSocket.disconnect(1000, "server stopping");
                return;
            }
            session.connecting = false;
            lgr.info("ws-conn");
            lgr.debug("WebSocket connection opened: {}", session.wsUrl);
        }

        @Override
        public void onTextMessage(WebSocket webSocket, String text) {
            if (!isCurrentSocket(session, webSocket)) {
                return;
            }
            lgr.debug("Received text message: {}", text);
            WsMessage<JsonElement> msg = gson.fromJson(text, WS_MESSAGE_JSON_TYPE);
            session.handler.onMessage(msg);
        }

        @Override
        public void onBinaryMessage(WebSocket webSocket, byte[] binary) {
            lgr.debug("Received binary message (length={})", binary.length);
        }

        @Override
        public void onPingFrame(WebSocket webSocket, WebSocketFrame frame) {
            lgr.debug("Received ping");
        }

        @Override
        public void onPongFrame(WebSocket webSocket, WebSocketFrame frame) {
            lgr.debug("Received pong");
        }

        @Override
        public void onDisconnected(WebSocket webSocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
            if (!isCurrentSocket(session, webSocket)) {
                return;
            }
            session.connecting = false;
            String reason = serverCloseFrame != null ? serverCloseFrame.getCloseReason() : "unknown";
            int code = serverCloseFrame != null ? serverCloseFrame.getCloseCode() : -1;
            lgr.info("ws closed: {} - {}", code, reason);
            session.webSocket = null;
            scheduleReconnect(session);
        }

        @Override
        public void onConnectError(WebSocket webSocket, WebSocketException exception) {
            if (!isCurrentSocket(session, webSocket)) {
                return;
            }
            session.connecting = false;
            lgr.error("ws conn error", exception);
            session.webSocket = null;
            scheduleReconnect(session);
        }

        @Override
        public void onError(WebSocket webSocket, WebSocketException cause) {
            if (!isCurrentSocket(session, webSocket)) {
                return;
            }
            session.connecting = false;
            lgr.error("ws error", cause);
            session.webSocket = null;
            scheduleReconnect(session);
        }
    }

    private static void disableJvmProxy() {
        System.setProperty("java.net.useSystemProxies", "false");
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        System.clearProperty("socksProxyVersion");
        System.clearProperty("java.net.socks.username");
        System.clearProperty("java.net.socks.password");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("ftp.proxyHost");
        System.clearProperty("ftp.proxyPort");
        System.clearProperty("http.nonProxyHosts");
        ProxySelector.setDefault(DIRECT_PROXY_SELECTOR);
    }

    private static class DirectSocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() {
            return new Socket(Proxy.NO_PROXY);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }

    private static class DirectProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }


}
