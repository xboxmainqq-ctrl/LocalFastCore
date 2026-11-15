package ru.nexora.ai.server;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

/** GitHub repository access. Tokens stay in Android Keystore-backed app storage. */
public final class GitHubBridge {
    private static final String PREFS = "nexora_github";
    private static final String KEY_ALIAS = "nexora_github_aes";
    private static final String API = "https://api.github.com";
    private final Activity activity;
    private final WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    GitHubBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void beginDeviceLogin(String clientId) {
        final String id = clientId == null ? "" : clientId.trim();
        if (!id.matches("[A-Za-z0-9_.-]{10,80}")) {
            deliver("device_code", errorResult("Укажи корректный Client ID зарегистрированного GitHub App."));
            return;
        }
        executor.execute(() -> {
            try {
                JSONObject response = postForm("https://github.com/login/device/code",
                        "client_id=" + formEncode(id) + "&scope=offline_access");
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("user_code", response.optString("user_code"));
                result.put("verification_uri", response.optString("verification_uri", "https://github.com/login/device"));
                result.put("device_code", response.optString("device_code"));
                result.put("interval", response.optInt("interval", 5));
                result.put("expires_in", response.optInt("expires_in", 900));
                result.put("client_id", id);
                deliver("device_code", result);
            } catch (Exception e) {
                deliver("device_code", errorResult(safeError(e)));
            }
        });
    }

    @JavascriptInterface
    public void completeDeviceLogin(String clientId, String deviceCode, int intervalSeconds, int expiresInSeconds) {
        final String id = clientId == null ? "" : clientId.trim();
        final String code = deviceCode == null ? "" : deviceCode.trim();
        final int interval = Math.max(5, Math.min(intervalSeconds, 30));
        final int expires = Math.max(60, Math.min(expiresInSeconds, 900));
        if (!id.matches("[A-Za-z0-9_.-]{10,80}") || code.length() < 20 || code.length() > 200) {
            deliver("device_login", errorResult("Данные авторизации некорректны. Запроси новый код."));
            return;
        }
        executor.execute(() -> {
            int waitSeconds = interval;
            long deadline = System.currentTimeMillis() + expires * 1000L;
            try {
                while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(waitSeconds * 1000L);
                    JSONObject response = postForm("https://github.com/login/oauth/access_token",
                            "client_id=" + formEncode(id)
                                    + "&device_code=" + formEncode(code)
                                    + "&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code");
                    String error = response.optString("error", "");
                    if ("authorization_pending".equals(error)) continue;
                    if ("slow_down".equals(error)) { waitSeconds += 5; continue; }
                    if (!error.isEmpty()) {
                        deliver("device_login", errorResult("GitHub не завершил вход: " + error));
                        return;
                    }
                    String token = response.optString("access_token", "");
                    if (token.isEmpty() || !saveToken(token)) {
                        deliver("device_login", errorResult("GitHub не вернул токен или его не удалось сохранить."));
                        return;
                    }
                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("token_saved", true);
                    result.put("expires_in", response.optLong("expires_in", 0));
                    String refreshToken = response.optString("refresh_token", "");
                    if (!refreshToken.isEmpty()) saveRefreshToken(refreshToken);
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("client_id", id)
                            .putLong("expires_at", System.currentTimeMillis()
                                    + response.optLong("expires_in", 28800) * 1000L)
                            .apply();
                    result.put("refresh_token_available", !refreshToken.isEmpty());
                    deliver("device_login", result);
                    return;
                }
                deliver("device_login", errorResult("Код авторизации истёк. Начни подключение заново."));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                deliver("device_login", errorResult("Авторизация остановлена."));
            } catch (Exception e) {
                deliver("device_login", errorResult(safeError(e)));
            }
        });
    }

    private JSONObject postForm(String endpoint, String form) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            c.setRequestProperty("User-Agent", "NEXORA-AI-Android");
            byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int status = c.getResponseCode();
            String body = readAll(status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream());
            if (status < 200 || status >= 300)
                throw new IllegalStateException("GitHub OAuth вернул HTTP " + status + ".");
            return new JSONObject(body);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String formEncode(String value) throws Exception {
        return java.net.URLEncoder.encode(value, "UTF-8");
    }

    private JSONObject errorResult(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false); result.put("error", message); } catch (Exception ignored) {}
        return result;
    }

    @JavascriptInterface
    public void openDeviceVerification() {
        activity.runOnUiThread(() -> activity.startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device"))));
    }

    @JavascriptInterface
    public boolean hasToken() {
        return !readToken().isEmpty();
    }

    @JavascriptInterface
    public boolean saveToken(String token) {
        if (token == null || token.trim().length() < 20 || token.length() > 512
                || token.contains("\n") || token.contains(" ")) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] encrypted = cipher.doFinal(token.trim().getBytes(StandardCharsets.UTF_8));
            return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    @JavascriptInterface
    public void clearToken() {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void listRepositories() {
        run("repositories", () -> {
            JSONArray raw = requestArray("/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=50");
            JSONArray repos = new JSONArray();
            if (raw != null) for (int i = 0; i < raw.length(); i++) {
                JSONObject r = raw.optJSONObject(i);
                if (r == null) continue;
                JSONObject item = new JSONObject();
                item.put("full_name", r.optString("full_name"));
                item.put("name", r.optString("name"));
                item.put("private", r.optBoolean("private"));
                item.put("default_branch", r.optString("default_branch", "main"));
                item.put("html_url", r.optString("html_url"));
                repos.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("repositories", repos);
            return result;
        });
    }

    @JavascriptInterface
    public void listBranches(String fullName) {
        run("branches", () -> {
            String repo = safeRepo(fullName);
            JSONArray raw = requestArray("/repos/" + repo + "/branches?per_page=100");
            JSONArray branches = new JSONArray();
            if (raw != null) for (int i = 0; i < raw.length(); i++) {
                JSONObject b = raw.optJSONObject(i);
                if (b != null) branches.put(b.optString("name"));
            }
            JSONObject result = new JSONObject();
            result.put("branches", branches);
            return result;
        });
    }

    @JavascriptInterface
    public void listFiles(String fullName, String branchName, String folderPath) {
        final String repoInput = fullName == null ? "" : fullName;
        final String branchInput = branchName == null ? "" : branchName;
        final String folderInput = folderPath == null ? "" : folderPath.trim();
        run("workspace_files", () -> {
            String repo = safeRepo(repoInput);
            String branch = safeBranch(branchInput);
            String folder = folderInput.isEmpty() ? "" : safeFilePath(folderInput);
            JSONObject branchRef = request("GET", "/repos/" + repo + "/git/ref/heads/" + encodePath(branch), null);
            String headSha = branchRef.getJSONObject("object").getString("sha");
            String path = "/repos/" + repo + "/contents" + (folder.isEmpty() ? "" : "/" + encodeContentPath(folder)) + "?ref=" + encodeQuery(branch);
            JSONArray raw = requestArray(path);
            JSONArray resultFiles = new JSONArray();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject entry = raw.optJSONObject(i);
                if (entry == null) continue;
                String type = entry.optString("type", "");
                if (!"file".equals(type) && !"dir".equals(type)) continue;
                JSONObject item = new JSONObject();
                item.put("name", entry.optString("name"));
                item.put("path", entry.optString("path"));
                item.put("type", type);
                item.put("size", entry.optLong("size"));
                item.put("sha", entry.optString("sha"));
                resultFiles.put(item);
            }
            JSONObject result = new JSONObject();
            result.put("files", resultFiles);
            result.put("folder", folder);
            result.put("branch", branch);
            result.put("head_sha", headSha);
            return result;
        });
    }

    @JavascriptInterface
    public void readFile(String fullName, String branchName, String filePath) {
        final String repoInput = fullName == null ? "" : fullName;
        final String branchInput = branchName == null ? "" : branchName;
        final String fileInput = filePath == null ? "" : filePath;
        run("workspace_file", () -> {
            String repo = safeRepo(repoInput);
            String branch = safeBranch(branchInput);
            String path = safeFilePath(fileInput);
            JSONObject file = request("GET", "/repos/" + repo + "/contents/" + encodeContentPath(path)
                    + "?ref=" + encodeQuery(branch), null);
            if (!"file".equals(file.optString("type")))
                throw new IllegalArgumentException("Выбери обычный текстовый файл, а не папку или бинарный объект.");
            String encoded = file.optString("content", "").replace("\n", "").trim();
            if (encoded.isEmpty()) throw new IllegalStateException("GitHub не вернул содержимое файла.");
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length > 512 * 1024)
                throw new IllegalArgumentException("Редактор пока поддерживает текстовые файлы до 512 КБ.");
            String content = new String(bytes, StandardCharsets.UTF_8);
            JSONObject result = new JSONObject();
            result.put("path", path);
            result.put("content", content);
            result.put("sha", file.optString("sha"));
            result.put("size", bytes.length);
            return result;
        });
    }

    @JavascriptInterface
    public void createBranch(String fullName, String newBranch, String baseBranch) {
        run("create_branch", () -> {
            String repo = safeRepo(fullName);
            String branch = safeBranch(newBranch);
            String base = safeBranch(baseBranch);
            JSONObject ref = request("GET", "/repos/" + repo + "/git/ref/heads/" + encodePath(base), null);
            String baseSha = ref.getJSONObject("object").getString("sha");
            JSONObject body = new JSONObject();
            body.put("ref", "refs/heads/" + branch);
            body.put("sha", baseSha);
            JSONObject created = request("POST", "/repos/" + repo + "/git/refs", body);
            JSONObject result = new JSONObject();
            result.put("branch", branch);
            result.put("sha", created.optJSONObject("object") == null ? "" : created.getJSONObject("object").optString("sha"));
            return result;
        });
    }

    @JavascriptInterface
    public void commitFiles(String fullName, String branchName, String message, String filesJson) {
        final String repoInput = fullName == null ? "" : fullName;
        final String branchInput = branchName == null ? "" : branchName;
        final String messageInput = message == null ? "" : message.trim();
        final String filesInput = filesJson == null ? "" : filesJson;
        run("commit", () -> {
            String repo = safeRepo(repoInput);
            String branch = safeBranch(branchInput);
            if (messageInput.isEmpty() || messageInput.length() > 200) {
                throw new IllegalArgumentException("Укажи сообщение коммита длиной до 200 символов.");
            }
            JSONArray files = new JSONArray(filesInput);
            if (files.length() < 1 || files.length() > 50) {
                throw new IllegalArgumentException("За один коммит можно отправить от 1 до 50 файлов.");
            }

            JSONObject ref = request("GET", "/repos/" + repo + "/git/ref/heads/" + encodePath(branch), null);
            String parentSha = ref.getJSONObject("object").getString("sha");
            String expectedHead = files.optJSONObject(0) == null ? "" : files.optJSONObject(0).optString("expected_head_sha", "");
            if (!expectedHead.isEmpty() && !expectedHead.equals(parentSha)) {
                throw new IllegalStateException("Ветка изменилась после загрузки проекта. Обнови файлы и повтори проверку; конфликтующие изменения не перезаписаны.");
            }
            JSONObject parentCommit = request("GET", "/repos/" + repo + "/git/commits/" + parentSha, null);
            String baseTree = parentCommit.getJSONObject("tree").getString("sha");

            JSONArray tree = new JSONArray();
            long totalBytes = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String path = safeFilePath(file.optString("path", ""));
                for (int j = 0; j < i; j++) {
                    if (path.equals(safeFilePath(files.getJSONObject(j).optString("path", "")))) {
                        throw new IllegalArgumentException("Путь файла указан дважды: " + path);
                    }
                }
                String action = file.optString("action", "upsert");
                JSONObject entry = new JSONObject();
                entry.put("path", path);
                entry.put("mode", "100644");
                entry.put("type", "blob");
                if ("delete".equals(action)) {
                    entry.put("sha", JSONObject.NULL);
                } else if ("upsert".equals(action) || "create".equals(action) || "update".equals(action)) {
                    String content = file.optString("content", "");
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    totalBytes += bytes.length;
                    if (totalBytes > 1024L * 1024L) {
                        throw new IllegalArgumentException("Общий размер файлов в одном коммите ограничен 1 МБ.");
                    }
                    JSONObject blobBody = new JSONObject();
                    blobBody.put("content", content);
                    blobBody.put("encoding", "utf-8");
                    JSONObject blob = request("POST", "/repos/" + repo + "/git/blobs", blobBody);
                    entry.put("sha", blob.getString("sha"));
                } else {
                    throw new IllegalArgumentException("Неизвестная операция для файла: " + action);
                }
                tree.put(entry);
            }

            JSONObject treeBody = new JSONObject();
            treeBody.put("base_tree", baseTree);
            treeBody.put("tree", tree);
            JSONObject newTree = request("POST", "/repos/" + repo + "/git/trees", treeBody);

            JSONObject commitBody = new JSONObject();
            commitBody.put("message", messageInput);
            commitBody.put("tree", newTree.getString("sha"));
            JSONArray parents = new JSONArray();
            parents.put(parentSha);
            commitBody.put("parents", parents);
            JSONObject commit = request("POST", "/repos/" + repo + "/git/commits", commitBody);

            JSONObject updateRef = new JSONObject();
            updateRef.put("sha", commit.getString("sha"));
            updateRef.put("force", false);
            request("PATCH", "/repos/" + repo + "/git/refs/heads/" + encodePath(branch), updateRef);

            JSONObject result = new JSONObject();
            result.put("commit_sha", commit.getString("sha"));
            result.put("commit_url", commit.optString("html_url"));
            result.put("files", files.length());
            result.put("branch", branch);
            return result;
        });
    }

    private interface Task { JSONObject execute() throws Exception; }

    private void run(String action, Task task) {
        executor.execute(() -> {
            JSONObject result;
            try {
                if (readToken().isEmpty()) throw new IllegalStateException("Сначала подключи GitHub token.");
                result = task.execute();
                result.put("ok", true);
            } catch (Exception e) {
                result = new JSONObject();
                try {
                    result.put("ok", false);
                    result.put("error", safeError(e));
                } catch (Exception ignored) {
                }
            }
            deliver(action, result);
        });
    }

    private JSONArray requestArray(String path) throws Exception {
        refreshAccessTokenIfNeeded();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API + path).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Authorization", "Bearer " + readToken());
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            c.setRequestProperty("User-Agent", "NEXORA-AI-Android");
            int status = c.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(stream);
            if (status < 200 || status >= 300) {
                if (status == 401) throw new IllegalStateException("GitHub token недействителен или истёк (401).");
                if (status == 403) throw new IllegalStateException("GitHub запретил действие. Проверь Metadata: Read и доступ к репозиторию (403).");
                throw new IllegalStateException("GitHub API вернул HTTP " + status + ".");
            }
            return new JSONArray(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        refreshAccessTokenIfNeeded();
        String token = readToken();
        if (token.isEmpty()) throw new IllegalStateException("GitHub не подключён.");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            c.setRequestProperty("User-Agent", "NEXORA-AI-Android");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            int status = c.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(stream);
            if (status < 200 || status >= 300) {
                if (status == 401) throw new IllegalStateException("GitHub token недействителен или истёк (401).");
                if (status == 403) throw new IllegalStateException("GitHub запретил действие. Проверь Contents: Read and write и доступ к репозиторию (403).");
                if (status == 404) throw new IllegalStateException("Репозиторий, ветка или путь не найдены либо token не имеет доступа (404).");
                if (status == 409) throw new IllegalStateException("Ветка изменилась одновременно с сохранением. Обнови данные и повтори.");
                if (status == 422) throw new IllegalStateException("GitHub отклонил данные. Возможно, ветка уже существует или путь некорректен (422).");
                throw new IllegalStateException("GitHub API вернул HTTP " + status + ".");
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void saveRefreshToken(String token) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("refresh_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("refresh_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
        } catch (Exception ignored) {}
    }

    private String readRefreshToken() {
        try {
            SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ivText = p.getString("refresh_iv", "");
            String dataText = p.getString("refresh_data", "");
            if (ivText.isEmpty() || dataText.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private void refreshAccessTokenIfNeeded() throws Exception {
        SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long expiresAt = p.getLong("expires_at", 0L);
        if (expiresAt == 0L || System.currentTimeMillis() < expiresAt - 60000L) return;
        String refresh = readRefreshToken();
        String clientId = p.getString("client_id", "");
        if (refresh.isEmpty() || clientId.isEmpty())
            throw new IllegalStateException("GitHub-сессия истекла. Подключи GitHub заново.");
        JSONObject response = postForm("https://github.com/login/oauth/access_token",
                "client_id=" + formEncode(clientId)
                        + "&grant_type=refresh_token&refresh_token=" + formEncode(refresh));
        String access = response.optString("access_token", "");
        String nextRefresh = response.optString("refresh_token", "");
        if (access.isEmpty() || !saveToken(access))
            throw new IllegalStateException("Не удалось обновить GitHub-сессию. Войди снова.");
        if (!nextRefresh.isEmpty()) saveRefreshToken(nextRefresh);
        p.edit().putLong("expires_at", System.currentTimeMillis()
                + response.optLong("expires_in", 28800) * 1000L).apply();
    }

    private String readToken() {
        try {
            SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ivText = p.getString("iv", "");
            String dataText = p.getString("data", "");
            if (ivText.isEmpty() || dataText.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private String safeRepo(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Репозиторий должен быть в формате owner/name.");
        return value;
    }

    private String safeBranch(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 180
                || value.startsWith("/") || value.endsWith("/") || value.contains("..")
                || value.contains(" ") || value.contains("~") || value.contains("^")
                || value.contains(":") || value.contains("?") || value.contains("*")
                || value.contains("[") || value.contains("\\") || value.contains("@{"))
            throw new IllegalArgumentException("Некорректное имя ветки.");
        return value;
    }

    private String safeFilePath(String value) {
        if (value == null || value.trim().isEmpty() || value.startsWith("/")
                || value.contains("..") || value.contains("\\") || value.length() > 240)
            throw new IllegalArgumentException("Некорректный путь файла: " + value);
        return value;
    }

    private String encodeContentPath(String value) throws Exception {
        String[] parts = value.split("/");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append("/");
            out.append(java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
        }
        return out.toString();
    }

    private String encodeQuery(String value) throws Exception {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodePath(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace(" ", "%20");
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null) return "Неизвестная ошибка GitHub.";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void deliver(String action, JSONObject result) {
        try { result.put("action", action); } catch (Exception ignored) {}
        String script = "window.nexoraGitHubResult && window.nexoraGitHubResult("
                + result.toString() + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    void shutdown() { executor.shutdownNow(); }
}
