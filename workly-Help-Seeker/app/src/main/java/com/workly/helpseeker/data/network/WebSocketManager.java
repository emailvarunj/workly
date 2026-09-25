package com.workly.helpseeker.data.network;

import android.util.Log;
import com.google.gson.Gson;
import com.workly.helpseeker.data.auth.AuthManager;
import com.workly.helpseeker.data.model.ChatMessage;
import com.workly.helpseeker.util.AppLogger;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Singleton
public class WebSocketManager {
    private static final String TAG = "WORKLY_DEBUG";

    private final String wsUrl;
    private WebSocket webSocket;
    private final OkHttpClient client;
    private final Gson gson;
    private volatile MessageListener messageListener;
    private final AppLogger appLogger;
    private final AuthManager authManager;

    public interface MessageListener {
        void onMessageReceived(ChatMessage message);
    }

    @Inject
    public WebSocketManager(Properties properties, AppLogger appLogger, AuthManager authManager) {
        this.wsUrl = properties.getProperty("chat.url", "ws://192.168.31.112:8082/ws/chat");
        this.appLogger = appLogger;
        this.authManager = authManager;
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        gson = new Gson();
    }

    public void connect(String userId, MessageListener listener) {
        this.messageListener = listener;
        if (webSocket != null) {
            webSocket.close(1000, "reconnect");
        }
        appLogger.d(TAG, "WebSocketManager(Seeker): [ENTER] connect - userId present, url: " + wsUrl);
        HttpUrl url = HttpUrl.parse(wsUrl).newBuilder()
                .addQueryParameter("userId", userId)
                .addQueryParameter("token", authManager.getToken())
                .build();
        Request request = new Request.Builder()
                .url(url)
                .build();
        webSocket = client.newWebSocket(request, new WorklyWebSocketListener(userId));
    }

    public void sendMessage(ChatMessage message) {
        if (webSocket != null) {
            appLogger.d(TAG, "WebSocketManager(Seeker): Sending message to: " + message.receiverId);
            String json = gson.toJson(message);
            webSocket.send(json);
        } else {
            appLogger.e(TAG, "WebSocketManager(Seeker): Cannot send - WebSocket is null");
        }
    }

    public void disconnect() {
        appLogger.d(TAG, "WebSocketManager(Seeker): Disconnecting WebSocket");
        messageListener = null;
        if (webSocket != null) {
            webSocket.close(1000, "User disconnected");
        }
    }

    private class WorklyWebSocketListener extends WebSocketListener {
        private final String userId;

        WorklyWebSocketListener(String userId) {
            this.userId = userId;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            appLogger.d(TAG, "WebSocketManager(Seeker): Connection established for userId: " + userId);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            appLogger.d(TAG, "WebSocketManager(Seeker): Message received (" + text.length() + " chars)");
            try {
                ChatMessage message = gson.fromJson(text, ChatMessage.class);
                if (messageListener != null) {
                    messageListener.onMessageReceived(message);
                }
            } catch (Exception e) {
                appLogger.e(TAG, "WebSocketManager(Seeker): [FAIL] Error parsing message: " + e.getMessage(), e);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            appLogger.d(TAG, "WebSocketManager(Seeker): Connection closing - code: " + code + ", reason: " + reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            appLogger.e(TAG, "WebSocketManager(Seeker): [FAIL] Connection failed: " + t.getMessage(), t);
        }
    }
}
