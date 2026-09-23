package com.jianbox.app;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Dependency-free client for Supabase Auth and PostgREST. Uses a public key only. */
final class BackendClient {
    static final String BASE_URL = BuildConfig.SUPABASE_URL;
    static final String ANON_KEY = BuildConfig.SUPABASE_ANON_KEY;
    interface Callback { void done(boolean ok, JSONObject body, String error); }

    static boolean configured() { return !BASE_URL.trim().isEmpty() && !ANON_KEY.trim().isEmpty(); }
    static void request(String path, String method, JSONObject payload, Callback callback) { request(path, method, payload, "", callback); }
    static void request(String path, String method, JSONObject payload, String accessToken, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                if (!configured()) { post(callback, false, null, "Supabase publishable/anon key 尚未配置"); return; }
                connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
                connection.setConnectTimeout(5000); connection.setReadTimeout(8000); connection.setRequestMethod(method);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("apikey", ANON_KEY);
                connection.setRequestProperty("Authorization", "Bearer " + (accessToken == null || accessToken.isEmpty() ? ANON_KEY : accessToken));
                if (payload != null) { connection.setDoOutput(true); try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes(StandardCharsets.UTF_8)); } }
                int code = connection.getResponseCode();
                InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                StringBuilder raw = new StringBuilder();
                if (input != null) try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) raw.append(line); }
                JSONObject body = parse(raw.toString());
                String error = chineseError(body.optString("error_description", body.optString("msg", body.optString("message", body.optString("error", body.optString("error_code", "HTTP " + code))))));
                post(callback, code >= 200 && code < 300, body, error);
            } catch (Exception ignored) { post(callback, false, null, "Supabase 服务暂不可用，请稍后重试"); }
            finally { if (connection != null) connection.disconnect(); }
        }).start();
    }
    /** Performs an authenticated request and transparently renews an expired Supabase access token once. */
    static void requestWithSession(String path, String method, JSONObject payload, JianData data, Callback callback) {
        String token = data == null ? "" : data.accountToken();
        if (token.isEmpty()) { post(callback, false, null, "请先登录账号"); return; }
        request(path, method, payload, token, (ok, body, error) -> {
            if (ok || !isExpired(error, body) || data.accountRefreshToken().isEmpty()) { callback.done(ok, body, error); return; }
            JSONObject refreshPayload;
            try { refreshPayload = new JSONObject().put("refresh_token", data.accountRefreshToken()); }
            catch (Exception ignored) { data.clearAccount(); callback.done(false, null, "登录会话已过期，请重新登录"); return; }
            request("/auth/v1/token?grant_type=refresh_token", "POST", refreshPayload, (renewed, session, renewError) -> {
                if (!renewed || session == null || session.optString("access_token", "").isEmpty()) {
                    data.clearAccount(); callback.done(false, session, "登录会话已过期，请重新登录"); return;
                }
                data.accountToken(session.optString("access_token", ""));
                if (session.has("refresh_token")) data.accountRefreshToken(session.optString("refresh_token", ""));
                request(path, method, payload, data.accountToken(), callback);
            });
        });
    }
    private static boolean isExpired(String error, JSONObject body) {
        String value = ((error == null ? "" : error) + " " + (body == null ? "" : body.toString())).toLowerCase(Locale.ROOT);
        return value.contains("jwt expired") || value.contains("token has expired") || value.contains("expired");
    }
    private static JSONObject parse(String raw) throws Exception { if (raw == null || raw.trim().isEmpty()) return new JSONObject(); if (raw.trim().startsWith("[")) return new JSONObject().put("data", new JSONArray(raw)); return new JSONObject(raw); }
    private static String chineseError(String value) {
        String raw=value==null?"":value,lower=raw.toLowerCase(Locale.ROOT);
        if(lower.contains("rate limit")||lower.contains("too many"))return "请求过于频繁，请稍后再试";
        if(lower.contains("token has expired")||lower.contains("otp expired"))return "验证码已过期，请重新获取";
        if(lower.contains("invalid token")||lower.contains("token is invalid"))return "验证码不正确";
        if(lower.contains("jwt expired") || lower.contains("token has expired"))return "登录会话已过期，请重新登录";
        if(lower.contains("invalid login credentials") || lower.contains("invalid_credentials") || lower.contains("email or password") || lower.contains("password is incorrect"))return "邮箱或密码不正确，请检查后重试";
        if(lower.contains("email not confirmed"))return "邮箱尚未验证，请先完成注册验证码校验";
        if(lower.contains("password")&&(lower.contains("weak")||lower.contains("least")))return "密码强度不足，请至少使用 8 位字符";
        if(lower.contains("user not found")||lower.contains("no user found"))return "该邮箱尚未注册，请切换到注册模式";
        if(lower.contains("signups not allowed"))return "当前项目暂未开放注册，请稍后再试";
        if(lower.contains("email")&&lower.contains("invalid"))return "邮箱地址格式不正确";
        if(lower.contains("already registered")||lower.contains("already been registered"))return "该邮箱已经注册，请切换到登录模式";
        return raw.isEmpty()?"账号服务暂不可用，请稍后再试":raw;
    }
    private static void post(Callback callback, boolean ok, JSONObject body, String error) { new Handler(Looper.getMainLooper()).post(() -> callback.done(ok, body, error)); }
    static void heartbeat(JianData data) {
        if (data == null || !configured()) return;
        try {
            request("/rest/v1/rpc/jianbox_record_install", "POST", new JSONObject().put("p_installation", data.installationId()).put("p_app_version", BuildConfig.VERSION_CODE), (ok, body, error) -> {});
            String token = data.accountToken(), userId = data.accountUserId();
            if (token.isEmpty() || userId.isEmpty()) return;
            String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date());
            request("/rest/v1/profiles?id=eq." + userId, "PATCH", new JSONObject().put("last_seen_at", timestamp), token, (ok, body, error) -> {});
            request("/rest/v1/app_events", "POST", new JSONObject().put("user_id", userId).put("event_name", "app_open").put("app_version", BuildConfig.VERSION_CODE), token, (ok, body, error) -> {});
            requestWithSession("/rest/v1/rpc/jianbox_bind_installation", "POST", new JSONObject().put("p_installation", data.installationId()), data, (ok, body, error) -> {});
        } catch (Exception ignored) { }
    }
    static void syncProfileName(JianData data, String name, SyncCallback callback) {
        if (data == null || data.accountToken().isEmpty() || data.accountUserId().isEmpty() || name == null || name.trim().isEmpty()) { if(callback!=null)callback.done(true, ""); return; }
        try {
            requestWithSession("/rest/v1/profiles?id=eq." + data.accountUserId(), "PATCH", new JSONObject().put("display_name", name.trim()), data, (ok, body, error) -> { if(callback!=null)callback.done(ok,error); });
        } catch (Exception ignored) { if(callback!=null)callback.done(false, "资料同步请求失败"); }
    }
    interface SyncCallback { void done(boolean ok, String error); }
    static JSONObject firstRow(JSONObject body) {
        if(body==null)return null;JSONArray rows=body.optJSONArray("data");return rows==null?body:rows.optJSONObject(0);
    }
    private BackendClient() { }
}
