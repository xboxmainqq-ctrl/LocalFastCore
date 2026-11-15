package ru.nexora.ai.server;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class DashboardBridge {
    private static final String PREFS = "nexora_model_gateway";
    private static final String KEY_ALIAS = "nexora_openrouter_aes";
    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final String MODEL = "openrouter/free";
    private final Activity activity;
    private final WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    DashboardBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean hasOpenRouterKey() {
        return !readKey().isEmpty();
    }

    @JavascriptInterface
    public boolean saveOpenRouterKey(String value) {
        if (value == null || !value.startsWith("sk-or-v1-")
                || value.length() < 24 || value.length() > 256) {
            return false;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return prefs.edit()
                    .putString("key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("key_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    @JavascriptInterface
    public void clearOpenRouterKey() {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    @JavascriptInterface
    public void sendChat(String conversationJson) {
        final String safeConversation = conversationJson == null ? "" : conversationJson;
        if (safeConversation.isEmpty() || safeConversation.length() > 32000) {
            deliver(error("INVALID_INPUT", "История чата пуста или слишком длинная."));
            return;
        }
        executor.execute(() -> {
            String apiKey = readKey();
            if (apiKey.isEmpty()) {
                deliver(error("NO_API_KEY", "Добавь ключ OpenRouter в настройках чата."));
                return;
            }
            HttpURLConnection connection = null;
            try {
                JSONObject request = new JSONObject();
                request.put("model", MODEL);
                request.put("max_tokens", 5000);
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject()
                        .put("role", "system")
                        .put("content", loadSystemPrompt()));
                JSONArray history = new JSONArray(safeConversation);
                int start = Math.max(0, history.length() - 12);
                int totalChars = 0;
                for (int i = start; i < history.length(); i++) {
                    JSONObject item = history.optJSONObject(i);
                    if (item == null) continue;
                    String role = item.optString("role", "");
                    String content = item.optString("text", "");
                    if (!("user".equals(role) || "assistant".equals(role)) || content.isEmpty()) continue;
                    totalChars += content.length();
                    if (totalChars > 24000) break;
                    messages.put(new JSONObject().put("role", role).put("content", content));
                }
                if (messages.length() < 2) {
                    deliver(error("INVALID_INPUT", "Добавь текстовое сообщение в чат."));
                    return;
                }
                request.put("messages", messages);

                connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(90000);
                connection.setUseCaches(false);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("HTTP-Referer", "https://nexora.local");
                connection.setRequestProperty("X-Title", "NEXORA AI");
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }

                int status = connection.getResponseCode();
                if (status == 402 || status == 429 || status == 503 || status == 404) {
                    deliver(error("NO_FREE_ROUTE",
                            "Сейчас нет доступного бесплатного маршрута. Платная модель не будет включена."));
                    return;
                }
                if (status == 401 || status == 403) {
                    deliver(error("INVALID_KEY", "Ключ не принят провайдером. Проверь его в настройках."));
                    return;
                }
                InputStream input = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String responseText = readAll(input);
                if (status < 200 || status >= 300) {
                    deliver(error("PROVIDER_ERROR", "Провайдер вернул ошибку HTTP " + status + "."));
                    return;
                }
                JSONObject response = new JSONObject(responseText);
                JSONArray choices = response.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    deliver(error("EMPTY_RESPONSE", "Бесплатная модель вернула пустой ответ."));
                    return;
                }
                JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                String answer = message == null ? "" : message.optString("content", "");
                if (answer.trim().isEmpty()) {
                    deliver(error("EMPTY_RESPONSE", "Модель не вернула текст ответа."));
                } else {
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("text", answer);
                    result.put("model", MODEL);
                    deliver(result);
                }
            } catch (Exception ignored) {
                deliver(error("NETWORK_ERROR", "Не удалось связаться с бесплатным AI-провайдером. Проверь интернет."));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private JSONObject error(String code, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("code", code);
            result.put("message", message);
        } catch (Exception ignored) {
        }
        return result;
    }

    private void deliver(JSONObject result) {
        String script = "window.nexoraChatResult && window.nexoraChatResult("
                + result.toString() + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private String loadSystemPrompt() {
        try {
            return readAll(activity.getAssets().open("nexora-system-prompt.md"));
        } catch (Exception ignored) {
            return "Ты — NEXORA AI, инженерный помощник. Отвечай на языке пользователя. "
                    + "Изучай проект перед изменениями, пиши проверяемый код и честно сообщай, "
                    + "какие инструменты и проверки реально выполнены. Не утверждай, что "
                    + "запускал сборку или менял файлы без подтверждения. Не используй платные "
                    + "AI-маршруты и не раскрывай секреты. Не изменяй main и не публикуй "
                    + "изменения без разрешения пользователя.";
        }
    }

    private String readKey() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ivText = prefs.getString("key_iv", "");
            String dataText = prefs.getString("key_data", "");
            if (ivText.isEmpty() || dataText.isEmpty()) return "";
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] data = Base64.decode(dataText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
