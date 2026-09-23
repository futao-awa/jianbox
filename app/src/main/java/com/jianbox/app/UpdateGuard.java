package com.jianbox.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/** Public Supabase release/announcement client and application-wide forced-update gate. */
final class UpdateGuard {
    private static final long CHECK_COOLDOWN_MS = 30_000L;
    private static final long PROMPT_COOLDOWN_MS = 6 * 60 * 60 * 1000L;
    private static final String PREFS = "jianbox_update_guard";
    private static final String FORCE_MIN = "force_min";
    private static final String FORCE_NAME = "force_name";
    private static final String FORCE_NOTES = "force_notes";
    private static final String FORCE_APK = "force_apk";
    private static final String FORCE_CHANNEL = "force_channel";
    private static final String SEEN_PREFIX = "announcement_seen_";
    private static final String UPDATE_QUERY = "/rest/v1/app_updates?select=version_code,version_name,min_version_code,apk_url,notes,force_update,channel&is_published=eq.true&channel=in.(stable,beta)&order=version_code.desc,channel.asc&limit=20";
    private static final String ANNOUNCEMENT_QUERY = "/rest/v1/app_announcements?select=id,title,body,level,target_min_version,target_max_version&is_published=eq.true&order=created_at.desc&limit=20";
    private static long lastCheckAt;
    private static long lastPromptAt;
    private static int lastPromptVersion = -1;
    private static boolean updateDialogShowing;
    private static boolean activeDialogForce;
    private static WeakReference<Dialog> activeUpdateDialog = new WeakReference<>(null);
    private static WeakReference<Activity> activeUpdateOwner = new WeakReference<>(null);
    private static boolean announcementDialogShowing;
    private static boolean overlayCheckInFlight;

    static void check(Activity activity) { check(activity, false); }
    static void checkNow(Activity activity) { check(activity, true); }

    static boolean resume(Activity activity) {
        if (enforce(activity)) return false;
        check(activity, false);
        return true;
    }

    /** Returns true after presenting a cached blocking update. Call before exposing app features. */
    static boolean enforce(Activity activity) {
        if (!hasForceGate(activity)) return false;
        stopOverlay(activity);
        showCachedForceDialog(activity);
        return true;
    }

    static boolean hasForceGate(Context context) {
        return preferences(context).getInt(FORCE_MIN, 0) > BuildConfig.VERSION_CODE;
    }

    /** Used by overlay/browser entry points when a cached gate already exists. */
    static void openGate(Context context) {
        stopOverlay(context);
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { context.startActivity(intent); } catch (Exception ignored) { }
    }

    static void guardOverlay(Context context, Runnable allowed) {
        if (hasForceGate(context)) { openGate(context); return; }
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_COOLDOWN_MS) { if (allowed != null) allowed.run(); return; }
        if (overlayCheckInFlight) return;
        overlayCheckInFlight = true;
        lastCheckAt = now;
        BackendClient.request(UPDATE_QUERY, "GET", null, (ok, body, error) -> {
            overlayCheckInFlight = false;
            if (!ok) { if (allowed != null) allowed.run(); return; }
            JSONObject update = chooseUpdate(body.optJSONArray("data"));
            if (update != null && update.optBoolean("force_update")
                    && BuildConfig.VERSION_CODE < update.optInt("min_version_code", 1)) {
                rememberForceGate(context, update.optInt("min_version_code", 1),
                        update.optString("version_name", "新版本"), update.optString("notes", "发现必须安装的更新"),
                        update.optString("apk_url", ""), update.optString("channel", "stable"));
                openGate(context);
                return;
            }
            if (allowed != null) allowed.run();
        });
    }

    private static void check(Activity activity, boolean manual) {
        boolean forceGate = hasForceGate(activity);
        long now = System.currentTimeMillis();
        if (forceGate) {
            stopOverlay(activity);
            showCachedForceDialog(activity);
        }
        if (!manual && !forceGate && now - lastCheckAt < CHECK_COOLDOWN_MS) return;
        lastCheckAt = now;
        BackendClient.request(UPDATE_QUERY, "GET", null, (ok, body, error) -> {
            if (!ok || activity.isFinishing()) {
                if (manual && !activity.isFinishing()) Toast.makeText(activity, error == null ? "更新检查失败，请稍后重试" : error, Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray rows = body.optJSONArray("data");
            JSONObject update = chooseUpdate(rows);
            if (update == null) {
                clearForceGate(activity);
                if (manual) Toast.makeText(activity, "当前已经是最新版本", Toast.LENGTH_SHORT).show();
                checkAnnouncements(activity);
                return;
            }
            int latest = update.optInt("version_code", BuildConfig.VERSION_CODE);
            int minimum = update.optInt("min_version_code", 1);
            boolean force = update.optBoolean("force_update") && BuildConfig.VERSION_CODE < minimum;
            String name = update.optString("version_name", "新版本");
            String notes = update.optString("notes", "发现可用更新");
            String apk = update.optString("apk_url", "");
            String channel = update.optString("channel", "stable");
            if (force) {
                rememberForceGate(activity, minimum, name, notes, apk, channel);
                stopOverlay(activity);
                if (!activity.hasWindowFocus()) {
                    openGate(activity);
                    return;
                }
            } else {
                clearForceGate(activity);
            }
            long promptNow = System.currentTimeMillis();
            if (!manual && !force && latest == lastPromptVersion && promptNow - lastPromptAt < PROMPT_COOLDOWN_MS) {
                checkAnnouncements(activity);
                return;
            }
            lastPromptVersion = latest;
            lastPromptAt = promptNow;
            showUpdateDialog(activity, name, notes, apk, channel, force, force ? null : () -> checkAnnouncements(activity));
        });
    }

    private static JSONObject chooseUpdate(JSONArray rows) {
        if (rows == null) return null;
        JSONObject regular = null;
        JSONObject forced = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || row.optInt("version_code", 0) <= BuildConfig.VERSION_CODE) continue;
            if (regular == null) regular = row;
            int minimum = row.optInt("min_version_code", 1);
            if (row.optBoolean("force_update") && BuildConfig.VERSION_CODE < minimum) {
                if (forced == null || row.optInt("version_code", 0) > forced.optInt("version_code", 0)) forced = row;
            }
        }
        return forced == null ? regular : forced;
    }

    private static void checkAnnouncements(Activity activity) {
        if (activity.isFinishing() || !activity.hasWindowFocus() || hasForceGate(activity)) return;
        BackendClient.request(ANNOUNCEMENT_QUERY, "GET", null, (ok, body, error) -> {
            if (!ok || activity.isFinishing() || announcementDialogShowing || hasForceGate(activity)) return;
            JSONArray rows = body.optJSONArray("data");
            if (rows == null) return;
            SharedPreferences prefs = preferences(activity);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item == null) continue;
                long id = item.optLong("id", -1L);
                int min = item.isNull("target_min_version") ? 1 : item.optInt("target_min_version", 1);
                int max = item.isNull("target_max_version") ? Integer.MAX_VALUE : item.optInt("target_max_version", Integer.MAX_VALUE);
                if (id < 0 || BuildConfig.VERSION_CODE < min || BuildConfig.VERSION_CODE > max || prefs.getBoolean(SEEN_PREFIX + id, false)) continue;
                showAnnouncementDialog(activity, id, item.optString("title", "简盒公告"), item.optString("body", ""), item.optString("level", "info"));
                return;
            }
        });
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void rememberForceGate(Context context, int minimum, String name, String notes, String apk, String channel) {
        preferences(context).edit().putInt(FORCE_MIN, minimum)
                .putString(FORCE_NAME, name).putString(FORCE_NOTES, notes)
                .putString(FORCE_APK, apk).putString(FORCE_CHANNEL, channel).apply();
    }

    private static void clearForceGate(Context context) {
        preferences(context).edit().remove(FORCE_MIN).remove(FORCE_NAME).remove(FORCE_NOTES)
                .remove(FORCE_APK).remove(FORCE_CHANNEL).apply();
    }

    private static void showCachedForceDialog(Activity activity) {
        if (activity.isFinishing()) return;
        SharedPreferences prefs = preferences(activity);
        showUpdateDialog(activity, prefs.getString(FORCE_NAME, "新版本"),
                prefs.getString(FORCE_NOTES, "该版本需要完成更新后继续使用。"),
                prefs.getString(FORCE_APK, ""), prefs.getString(FORCE_CHANNEL, "stable"), true, null);
    }

    private static void showUpdateDialog(Activity activity, String name, String notes, String apk, String channel, boolean force, Runnable afterDismiss) {
        if (activity.isFinishing()) return;
        Dialog existingDialog = activeUpdateDialog.get();
        Activity existingOwner = activeUpdateOwner.get();
        if (updateDialogShowing && existingDialog != null && existingDialog.isShowing()) {
            if (existingOwner == activity && (activeDialogForce || !force)) return;
            // A newly opened Activity must own the blocking dialog. Otherwise the old
            // Activity's dialog can leave the new screen interactive underneath it.
            existingDialog.setOnDismissListener(null);
            try { existingDialog.dismiss(); } catch (Exception ignored) { }
        }
        updateDialogShowing = true;
        activeDialogForce = force;
        Dialog dialog = new Dialog(activity);
        activeUpdateDialog = new WeakReference<>(dialog);
        activeUpdateOwner = new WeakReference<>(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = Ui.vertical(activity);
        card.setPadding(Ui.dp(activity, 19), Ui.dp(activity, 18), Ui.dp(activity, 19), Ui.dp(activity, 16));
        card.setBackground(Ui.bordered(Ui.SURFACE, force ? 0x44EF5350 : Ui.BORDER, 18, activity));
        LinearLayout top = Ui.horizontal(activity);
        String badgeText = force ? "必须更新" : ("beta".equals(channel) ? "测试版更新" : "发现新版本");
        TextView badge = Ui.text(activity, badgeText, 10, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER); badge.setPadding(Ui.dp(activity, 9), 0, Ui.dp(activity, 9), 0);
        badge.setBackground(Ui.bg(force ? Ui.RED : ("beta".equals(channel) ? 0xFF3F6FB5 : Ui.GREEN), 8, activity));
        top.addView(badge, new LinearLayout.LayoutParams(-2, Ui.dp(activity, 27)));
        TextView installed = Ui.text(activity, "当前 v" + BuildConfig.VERSION_NAME, 10, Ui.MUTED, false);
        installed.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT); top.addView(installed, Ui.weight(1));
        card.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 29)));
        TextView title = Ui.text(activity, "更新至 " + name, 21, Ui.TEXT, true);
        title.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4)); card.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 38)));
        card.addView(Ui.text(activity, force ? "此版本包含必须安装的修复" : ("beta".equals(channel) ? "测试版本已准备就绪" : "新的版本已准备就绪"), 11, force ? Ui.RED : Ui.MUTED, false), new LinearLayout.LayoutParams(-1, Ui.dp(activity, 24)));
        View divider = new View(activity); divider.setBackgroundColor(Ui.BORDER); card.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 1)));
        TextView content = Ui.text(activity, notes.isEmpty() ? "本次版本包含体验优化与稳定性修复。" : notes, 13, Ui.TEXT, false);
        content.setLineSpacing(Ui.dp(activity, 3), 1f); content.setPadding(0, Ui.dp(activity, 13), 0, Ui.dp(activity, 11)); card.addView(content, new LinearLayout.LayoutParams(-1, -2));
        if (force) {
            TextView hint = Ui.text(activity, "更新前不能继续使用简盒，悬浮球、内置浏览器和设置入口均已停用。", 10, Ui.RED, false);
            hint.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8), Ui.dp(activity, 10), Ui.dp(activity, 8)); hint.setBackground(Ui.bg(0x14EF5350, 9, activity)); card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        } else if (apk.isEmpty()) {
            TextView hint = Ui.text(activity, "下载地址尚未发布，可稍后再检查。", 10, Ui.MUTED, false);
            hint.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8), Ui.dp(activity, 10), Ui.dp(activity, 8)); hint.setBackground(Ui.bg(Ui.SOFT_SURFACE, 9, activity)); card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout actions = Ui.horizontal(activity); actions.setPadding(0, Ui.dp(activity, 15), 0, 0);
        Button secondary = Ui.button(activity, force ? "退出应用" : "稍后", false);
        Button primary = Ui.button(activity, apk.isEmpty() ? (force ? "退出应用" : "知道了") : "立即更新", true);
        actions.addView(secondary, new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1)); Ui.margin(secondary, 0, 0, 8, 0, activity); actions.addView(primary, new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1));
        secondary.setOnClickListener(v -> { dialog.dismiss(); if (force) forceExit(activity); });
        primary.setOnClickListener(v -> {
            if (apk.isEmpty()) { dialog.dismiss(); if (force) forceExit(activity); return; }
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk))); dialog.dismiss(); if (force) forceExit(activity); }
            catch (Exception ignored) { Toast.makeText(activity, "无法打开更新地址，请联系管理员", Toast.LENGTH_SHORT).show(); }
        });
        card.addView(actions, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 57)));
        dialog.setContentView(card); dialog.setCancelable(!force); dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            if (activeUpdateDialog.get() == dialog) {
                updateDialogShowing = false;
                activeDialogForce = false;
                activeUpdateDialog = new WeakReference<>(null);
                activeUpdateOwner = new WeakReference<>(null);
            }
            if (!force && afterDismiss != null && !activity.isFinishing()) afterDismiss.run();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams lp = window.getAttributes(); lp.dimAmount = .58f; window.setAttributes(lp); window.setLayout(Math.min(Ui.dp(activity, 420), activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 34)), WindowManager.LayoutParams.WRAP_CONTENT); }
    }

    private static void showAnnouncementDialog(Activity activity, long id, String title, String body, String level) {
        if (activity.isFinishing() || announcementDialogShowing) return;
        announcementDialogShowing = true;
        int color = "critical".equals(level) ? Ui.RED : "warning".equals(level) ? 0xFFF59E0B : "success".equals(level) ? Ui.GREEN : 0xFF3F6FB5;
        String label = "critical".equals(level) ? "重要公告" : "warning".equals(level) ? "提醒" : "success".equals(level) ? "功能公告" : "简盒公告";
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = Ui.vertical(activity);
        card.setPadding(Ui.dp(activity, 19), Ui.dp(activity, 18), Ui.dp(activity, 19), Ui.dp(activity, 16));
        card.setBackground(Ui.bordered(Ui.SURFACE, 0x335F7964, 18, activity));
        TextView badge = Ui.text(activity, label, 10, Color.WHITE, true); badge.setGravity(Gravity.CENTER); badge.setPadding(Ui.dp(activity, 9), 0, Ui.dp(activity, 9), 0); badge.setBackground(Ui.bg(color, 8, activity));
        card.addView(badge, new LinearLayout.LayoutParams(-2, Ui.dp(activity, 27)));
        TextView heading = Ui.text(activity, title, 20, Ui.TEXT, true); heading.setPadding(0, Ui.dp(activity, 11), 0, Ui.dp(activity, 8)); card.addView(heading);
        TextView content = Ui.text(activity, body.isEmpty() ? "暂无详细内容" : body, 13, Ui.TEXT, false); content.setLineSpacing(Ui.dp(activity, 3), 1f); card.addView(content, new LinearLayout.LayoutParams(-1, -2));
        Button confirm = Ui.button(activity, "知道了", true); confirm.setOnClickListener(v -> dialog.dismiss()); card.addView(confirm, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 43))); Ui.margin(confirm, 14, 0, 0, 0, activity);
        dialog.setContentView(card); dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> { preferences(activity).edit().putBoolean(SEEN_PREFIX + id, true).apply(); announcementDialogShowing = false; });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) { window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams lp = window.getAttributes(); lp.dimAmount = .46f; window.setAttributes(lp); window.setLayout(Math.min(Ui.dp(activity, 420), activity.getResources().getDisplayMetrics().widthPixels - Ui.dp(activity, 34)), WindowManager.LayoutParams.WRAP_CONTENT); }
    }

    private static void stopOverlay(Context context) {
        try { context.stopService(new Intent(context, OverlayService.class)); } catch (Exception ignored) { }
    }

    private static void forceExit(Activity activity) {
        stopOverlay(activity);
        lastCheckAt = 0L; lastPromptAt = 0L; lastPromptVersion = -1;
        activity.finishAffinity();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) activity.finishAndRemoveTask();
    }

    private UpdateGuard() { }
}
