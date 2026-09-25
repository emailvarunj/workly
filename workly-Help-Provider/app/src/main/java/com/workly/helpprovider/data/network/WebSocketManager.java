package com.workly.helpprovider.data.network;
import java.util.concurrent.TimeUnit;

import android.util.Log;
import com.google.gson.Gson;
import com.workly.helpprovider.data.auth.AuthManager;
import com.workly.helpprovider.data.model.ChatMessage;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Properties;
import com.workly.helpprovider.util.AppLogger;

@Singleton
public class WebSocketManager {
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
        client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        gson = new Gson();
    }

    public void connect(String userId, MessageListener listener) {
        this.messageListener = listener;
        if (webSocket != null) {
            webSocket.close(1000, "reconnect");
        }
        appLogger.d("WORKLY_DEBUG", "WebSocketManager: [ENTER] connect - userId present, url: " + wsUrl);
        HttpUrl url = HttpUrl.parse(wsUrl).newBuilder()
                .addQueryParameter("userId", userId)
                .addQueryParameter("token", authManager.getToken())
                .build();
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                appLogger.d("WORKLY_DEBUG", "WebSocketManager: Connection established for userId: " + userId);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    appLogger.d("WORKLY_DEBUG", "WebSocketManager: Received message (" + text.length() + " chars)");
                    ChatMessage message = gson.fromJson(text, ChatMessage.class);
                    if (messageListener != null)
                        messageListener.onMessageReceived(message);
                } catch (Exception e) {
                    appLogger.e("WORKLY_DEBUG", "WebSocketManager: [FAIL] Error parsing incoming message: " + e.getMessage(), e);
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                appLogger.d("WORKLY_DEBUG", "WebSocketManager: Connection closing - code: " + code + ", reason: " + reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                appLogger.e("WORKLY_DEBUG", "WebSocketManager: [FAIL] Connection failed: " + t.getMessage(), t);
            }
        });
    }

    public void sendMessage(ChatMessage message) {
        if (webSocket != null) {
            appLogger.d("WORKLY_DEBUG", "WebSocketManager: Sending message to: " + message.receiverId);
            webSocket.send(gson.toJson(message));
        } else {
            appLogger.e("WORKLY_DEBUG", "WebSocketManager: Cannot send - WebSocket is null");
        }
    }

    public void disconnect() {
        appLogger.d("WORKLY_DEBUG", "WebSocketManager: Disconnecting WebSocket");
        messageListener = null;
        if (webSocket != null)
            webSocket.close(1000, "Bye");
    }
}
