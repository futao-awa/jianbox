package com.jianbox.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class JianData {
    static final String TYPE_WEB = "web";
    static final String TYPE_APP = "app";
    static final String TYPE_SYSTEM = "system";
    private static final String PREF = "jianbox_data_v1";
    private static final String KEY_QUICK = "quick";
    private static final String KEY_CLIPS = "clips";
    private static final String KEY_REMINDERS = "reminders";
    private static final String KEY_HISTORY = "browser_history";
    private static final String KEY_BOOKMARKS = "browser_bookmarks";
    private final SharedPreferences prefs;

    JianData(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ensureDefaults();
    }

    static final class QuickItem {
        String id = UUID.randomUUID().toString();
        String type = TYPE_WEB;
        String title = "";
        String value = "";
        String group = "常用";
        String glyph = "链";

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("type", type).put("title", title)
                    .put("value", value).put("group", group).put("glyph", glyph);
        }

        static QuickItem from(JSONObject o) {
            QuickItem q = new QuickItem();
            q.id = o.optString("id", q.id);
            q.type = o.optString("type", TYPE_WEB);
            q.title = o.optString("title");
            q.value = o.optString("value");
            q.group = o.optString("group", "常用");
            q.glyph = o.optString("glyph", "链");
            return q;
        }
    }

    static final class ClipItem {
        String id = UUID.randomUUID().toString();
        String text = "";
        long time = System.currentTimeMillis();
        boolean favorite;
        String tags = "";
        String kind = "文字";

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("text", text).put("time", time)
                    .put("favorite", favorite).put("tags", tags).put("kind", kind);
        }

        static ClipItem from(JSONObject o) {
            ClipItem c = new ClipItem();
            c.id = o.optString("id", c.id);
            c.text = o.optString("text");
            c.time = o.optLong("time", c.time);
            c.favorite = o.optBoolean("favorite");
            c.tags = o.optString("tags");
            c.kind = o.optString("kind", "文字");
            return c;
        }
    }

    static final class Reminder {
        String id = UUID.randomUUID().toString();
        String title = "";
        long time = System.currentTimeMillis();
        String repeat = "once";
        boolean done;
        boolean vibrate = true;
        boolean sound = true;
        String mode = "both";
        int repeatCount = 1;
        int firedCount;

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("title", title).put("time", time)
                    .put("repeat", repeat).put("done", done).put("vibrate", vibrate)
                    .put("sound", sound).put("mode", mode).put("repeatCount", repeatCount).put("firedCount", firedCount);
        }

        static Reminder from(JSONObject o) {
            Reminder r = new Reminder();
            r.id = o.optString("id", r.id);
            r.title = o.optString("title");
            r.time = o.optLong("time", r.time);
            r.repeat = o.optString("repeat", "once");
            r.done = o.optBoolean("done");
            r.vibrate = o.optBoolean("vibrate", true);
            r.sound = o.optBoolean("sound", true);
            r.mode = o.optString("mode", "both");
            r.repeatCount = o.optInt("repeatCount", 1);
            r.firedCount = o.optInt("firedCount", 0);
            return r;
        }
    }

    static final class BrowserHistory {
        String id = UUID.randomUUID().toString();
        String title = "";
        String url = "";
        long time = System.currentTimeMillis();

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("title", title).put("url", url).put("time", time);
        }

        static BrowserHistory from(JSONObject o) {
            BrowserHistory h = new BrowserHistory();
            h.id = o.optString("id", h.id);
            h.title = o.optString("title");
            h.url = o.optString("url");
            h.time = o.optLong("time", h.time);
            return h;
        }
    }

    static final class Bookmark {
        String id = UUID.randomUUID().toString();
        String title = "";
        String url = "";
        String group = "常用";
        long time = System.currentTimeMillis();

        JSONObject json() throws JSONException { return new JSONObject().put("id",id).put("title",title).put("url",url).put("group",group).put("time",time); }
        static Bookmark from(JSONObject o){Bookmark b=new Bookmark();b.id=o.optString("id",b.id);b.title=o.optString("title");b.url=o.optString("url");b.group=o.optString("group","常用");b.time=o.optLong("time",b.time);return b;}
    }

    synchronized List<QuickItem> quickItems() {
        List<QuickItem> list = new ArrayList<>();
        JSONArray a = array(KEY_QUICK);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) list.add(QuickItem.from(o));
        }
        return list;
    }

    synchronized void saveQuick(List<QuickItem> list) {
        JSONArray a = new JSONArray();
        try { for (QuickItem q : list) a.put(q.json()); } catch (JSONException ignored) {}
        prefs.edit().putString(KEY_QUICK, a.toString()).apply();
    }

    synchronized List<ClipItem> clips() {
        List<ClipItem> list = new ArrayList<>();
        JSONArray a = array(KEY_CLIPS);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) list.add(ClipItem.from(o));
        }
        Collections.sort(list, (a1, a2) -> a1.favorite == a2.favorite
                ? Long.compare(a2.time, a1.time) : (a1.favorite ? -1 : 1));
        return list;
    }

    synchronized void addClip(String text, String tags, String kind) {
        if (text == null || text.trim().isEmpty()) return;
        List<ClipItem> list = clips();
        for (ClipItem old : list) {
            if (old.text.equals(text)) {
                old.time = System.currentTimeMillis();
                if (tags != null && !tags.isEmpty()) old.tags = tags;
                saveClips(list);
                return;
            }
        }
        ClipItem c = new ClipItem();
        c.text = text;
        c.tags = tags == null ? extractTags(text) : tags;
        c.kind = kind == null ? (text.matches("https?://.*") ? "链接" : "文字") : kind;
        list.add(0, c);
        if (list.size() > 200) list = new ArrayList<>(list.subList(0, 200));
        saveClips(list);
    }

    synchronized void saveClips(List<ClipItem> list) {
        JSONArray a = new JSONArray();
        try { for (ClipItem c : list) a.put(c.json()); } catch (JSONException ignored) {}
        prefs.edit().putString(KEY_CLIPS, a.toString()).apply();
    }

    synchronized List<Reminder> reminders() {
        List<Reminder> list = new ArrayList<>();
        JSONArray a = array(KEY_REMINDERS);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) list.add(Reminder.from(o));
        }
        list.sort(Comparator.comparingLong(r -> r.time));
        return list;
    }

    synchronized void saveReminders(List<Reminder> list) {
        JSONArray a = new JSONArray();
        try { for (Reminder r : list) a.put(r.json()); } catch (JSONException ignored) {}
        prefs.edit().putString(KEY_REMINDERS, a.toString()).apply();
    }

    synchronized Reminder findReminder(String id) {
        for (Reminder r : reminders()) if (r.id.equals(id)) return r;
        return null;
    }

    synchronized void updateReminder(Reminder item) {
        List<Reminder> list = reminders();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(item.id)) { list.set(i, item); found = true; break; }
        }
        if (!found) list.add(item);
        saveReminders(list);
    }

    synchronized List<BrowserHistory> history() {
        List<BrowserHistory> list = new ArrayList<>();
        JSONArray a = array(KEY_HISTORY);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) list.add(BrowserHistory.from(o));
        }
        list.sort((a1, a2) -> Long.compare(a2.time, a1.time));
        return list;
    }

    synchronized void addHistory(String title, String url) {
        if (url == null || !url.startsWith("http")) return;
        List<BrowserHistory> list = history();
        list.removeIf(item -> item.url.equals(url));
        BrowserHistory h = new BrowserHistory();
        h.title = title == null || title.trim().isEmpty() ? url : title;
        h.url = url;
        list.add(0, h);
        if (list.size() > 300) list = new ArrayList<>(list.subList(0, 300));
        saveHistory(list);
    }

    synchronized void saveHistory(List<BrowserHistory> list) {
        JSONArray a = new JSONArray();
        try { for (BrowserHistory h : list) a.put(h.json()); } catch (JSONException ignored) {}
        prefs.edit().putString(KEY_HISTORY, a.toString()).apply();
    }

    synchronized void clearHistory() { prefs.edit().putString(KEY_HISTORY, "[]").apply(); }

    synchronized List<Bookmark> bookmarks(){List<Bookmark> list=new ArrayList<>();JSONArray a=array(KEY_BOOKMARKS);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)list.add(Bookmark.from(o));}list.sort((a1,a2)->{int group=a1.group.compareToIgnoreCase(a2.group);return group==0?Long.compare(a2.time,a1.time):group;});return list;}
    synchronized void saveBookmarks(List<Bookmark> list){JSONArray a=new JSONArray();try{for(Bookmark b:list)a.put(b.json());}catch(JSONException ignored){}prefs.edit().putString(KEY_BOOKMARKS,a.toString()).apply();}
    synchronized Bookmark bookmarkFor(String url){if(url==null)return null;for(Bookmark b:bookmarks())if(url.equals(b.url))return b;return null;}
    synchronized void saveBookmark(Bookmark bookmark){List<Bookmark> list=bookmarks();list.removeIf(item->item.id.equals(bookmark.id)||item.url.equals(bookmark.url));list.add(bookmark);saveBookmarks(list);}
    synchronized void removeBookmark(String id){List<Bookmark> list=bookmarks();list.removeIf(item->item.id.equals(id));saveBookmarks(list);}

    int bubbleColor() { return prefs.getInt("bubble_color", Ui.GREEN); }
    void bubbleColor(int value) { prefs.edit().putInt("bubble_color", value).apply(); }
    int bubbleSize() { return prefs.getInt("bubble_size", 52); }
    void bubbleSize(int value) { prefs.edit().putInt("bubble_size", value).apply(); }
    int bubbleAlpha() { return prefs.getInt("bubble_alpha", 88); }
    void bubbleAlpha(int value) { prefs.edit().putInt("bubble_alpha", value).apply(); }
    boolean serviceEnabled() { return prefs.getBoolean("service_enabled", false); }
    void serviceEnabled(boolean value) { prefs.edit().putBoolean("service_enabled", value).apply(); }
    boolean pendingReminder() { return prefs.getBoolean("pending_reminder", false); }
    void pendingReminder(boolean value) { prefs.edit().putBoolean("pending_reminder", value).apply(); }
    int panelAlpha() { return prefs.getInt("panel_alpha", 96); }
    void panelAlpha(int value) { prefs.edit().putInt("panel_alpha", value).apply(); }
    int panelBlockAlpha() { return prefs.getInt("panel_block_alpha", 94); }
    void panelBlockAlpha(int value) { prefs.edit().putInt("panel_block_alpha", value).apply(); }
    int panelImageBlur() { return prefs.getInt("panel_image_blur", 0); }
    void panelImageBlur(int value) { prefs.edit().putInt("panel_image_blur", value).apply(); }
    int panelTextColor() { return prefs.getInt("panel_text_color", 0xFF1A3A1A); }
    void panelTextColor(int value) { prefs.edit().putInt("panel_text_color", value).apply(); }
    int panelRadius() { return prefs.getInt("panel_radius", 22); }
    void panelRadius(int value) { prefs.edit().putInt("panel_radius", value).apply(); }
    int panelWidth() { return prefs.getInt("panel_width", 88); }
    void panelWidth(int value) { prefs.edit().putInt("panel_width", value).apply(); }
    boolean panelCompact() { return prefs.getBoolean("panel_compact", true); }
    void panelCompact(boolean value) { prefs.edit().putBoolean("panel_compact", value).apply(); }
    boolean panelBlur() { return prefs.getBoolean("panel_blur", true); }
    void panelBlur(boolean value) { prefs.edit().putBoolean("panel_blur", value).apply(); }
    int panelBlurRadius() { return prefs.getInt("panel_blur_radius", 24); }
    void panelBlurRadius(int value) { prefs.edit().putInt("panel_blur_radius", value).apply(); }
    boolean monitorEnabled() { return prefs.getBoolean("monitor_enabled", false); }
    void monitorEnabled(boolean value) { prefs.edit().putBoolean("monitor_enabled", value).apply(); }
    String monitorFields() { return prefs.getString("monitor_fields", "cpu,ram,app,battery,network"); }
    void monitorFields(String value) { prefs.edit().putString("monitor_fields", value).apply(); }
    int monitorTextSize() { return prefs.getInt("monitor_text_size", 11); }
    void monitorTextSize(int value) { prefs.edit().putInt("monitor_text_size", value).apply(); }
    int monitorAlpha() { return prefs.getInt("monitor_alpha", 88); }
    void monitorAlpha(int value) { prefs.edit().putInt("monitor_alpha", value).apply(); }
    int monitorBackgroundAlpha() { return prefs.getInt("monitor_background_alpha", 92); }
    void monitorBackgroundAlpha(int value) { prefs.edit().putInt("monitor_background_alpha", value).apply(); }
    int monitorBackgroundShade() { return prefs.getInt("monitor_background_shade", 0); }
    void monitorBackgroundShade(int value) { prefs.edit().putInt("monitor_background_shade", value).apply(); }
    int monitorBackgroundBlur() { return prefs.getInt("monitor_background_blur", 0); }
    void monitorBackgroundBlur(int value) { prefs.edit().putInt("monitor_background_blur", value).apply(); }
    int monitorBorderColor() { return prefs.getInt("monitor_border_color", 0x558FA99A); }
    void monitorBorderColor(int value) { prefs.edit().putInt("monitor_border_color", value).apply(); }
    int monitorWidth() { return prefs.getInt("monitor_width", 174); }
    void monitorWidth(int value) { prefs.edit().putInt("monitor_width", value).apply(); }
    String bubbleIconPath() { return prefs.getString("bubble_icon_path", ""); }
    void bubbleIconPath(String value) { prefs.edit().putString("bubble_icon_path", value).apply(); }
    String appIconPath() { return prefs.getString("app_icon_path", ""); }
    void appIconPath(String value) { prefs.edit().putString("app_icon_path", value).apply(); }
    String profileAvatarPath() { return prefs.getString("profile_avatar_path", ""); }
    void profileAvatarPath(String value) { prefs.edit().putString("profile_avatar_path", value == null ? "" : value).apply(); }
    String profileName() { return prefs.getString("profile_name", ""); }
    void profileName(String value) { prefs.edit().putString("profile_name", value == null ? "" : value.trim()).apply(); }
    String profileBio() { return prefs.getString("profile_bio", ""); }
    void profileBio(String value) { prefs.edit().putString("profile_bio", value == null ? "" : value.trim()).apply(); }
    String checkinDate() { return prefs.getString("profile_checkin_date", ""); }
    void checkinDate(String value) { prefs.edit().putString("profile_checkin_date", value == null ? "" : value).apply(); }
    int checkinCount() { return prefs.getInt("profile_checkin_count", 0); }
    void checkinCount(int value) { prefs.edit().putInt("profile_checkin_count", Math.max(0, value)).apply(); }
    int coinBalance() { return prefs.getInt("profile_coin_balance", 0); }
    void coinBalance(int value) { prefs.edit().putInt("profile_coin_balance", Math.max(0, value)).apply(); }
    String appBackgroundPath() { return prefs.getString("app_background_path", ""); }
    void appBackgroundPath(String value) { prefs.edit().putString("app_background_path", value == null ? "" : value).apply(); }
    int appBackgroundPosition() { return prefs.getInt("app_background_position", 42); }
    void appBackgroundPosition(int value) { prefs.edit().putInt("app_background_position", Math.max(0, Math.min(100, value))).apply(); }
    int appAccentColor() { return prefs.getInt("app_accent_color", 0xFF4CAF50); }
    void appAccentColor(int value) { prefs.edit().putInt("app_accent_color", value).apply(); }
    String panelBackgroundPath() { return prefs.getString("panel_background_path", ""); }
    void panelBackgroundPath(String value) { prefs.edit().putString("panel_background_path", value).apply(); }
    String monitorBackgroundPath() { return prefs.getString("monitor_background_path", ""); }
    void monitorBackgroundPath(String value) { prefs.edit().putString("monitor_background_path", value).apply(); }
    String browserBackgroundPath() { return prefs.getString("browser_background_path", ""); }
    void browserBackgroundPath(String value) { prefs.edit().putString("browser_background_path", value).apply(); }
    String downloadLocation() { return prefs.getString("download_location", "系统下载"); }
    void downloadLocation(String value) { prefs.edit().putString("download_location", value).apply(); }
    boolean privacyAccepted() { return prefs.getBoolean("privacy_accepted", false); }
    void privacyAccepted(boolean value) { prefs.edit().putBoolean("privacy_accepted", value).apply(); }
    String accountToken() { return prefs.getString("account_token", ""); }
    void accountToken(String value) { prefs.edit().putString("account_token", value).apply(); }
    String accountRefreshToken() { return prefs.getString("account_refresh_token", ""); }
    void accountRefreshToken(String value) { prefs.edit().putString("account_refresh_token", value == null ? "" : value).apply(); }
    String accountUserId() { return prefs.getString("account_user_id", ""); }
    void accountUserId(String value) { prefs.edit().putString("account_user_id", value == null ? "" : value).apply(); }
    String accountEmail() { return prefs.getString("account_email", ""); }
    void accountEmail(String value) { prefs.edit().putString("account_email", value == null ? "" : value).apply(); }
    String installationId() {
        String value = prefs.getString("installation_id", "");
        if (!value.isEmpty()) return value;
        value = UUID.randomUUID().toString();
        prefs.edit().putString("installation_id", value).apply();
        return value;
    }
    void clearAccount() { prefs.edit().remove("account_token").remove("account_refresh_token").remove("account_user_id").remove("account_email").apply(); }
    boolean blockAds() { return prefs.getBoolean("browser_block_ads", true); }
    void blockAds(boolean value) { prefs.edit().putBoolean("browser_block_ads", value).apply(); }
    boolean pluginAdBlock() { return prefs.getBoolean("plugin_ad_block", true); }
    void pluginAdBlock(boolean value) { prefs.edit().putBoolean("plugin_ad_block", value).apply(); }
    boolean pluginTranslate() { return prefs.getBoolean("plugin_translate", true); }
    void pluginTranslate(boolean value) { prefs.edit().putBoolean("plugin_translate", value).apply(); }
    boolean pluginFind() { return prefs.getBoolean("plugin_find", true); }
    void pluginFind(boolean value) { prefs.edit().putBoolean("plugin_find", value).apply(); }
    boolean pluginMediaExtractor() { return prefs.getBoolean("plugin_media_extractor", true); }
    void pluginMediaExtractor(boolean value) { prefs.edit().putBoolean("plugin_media_extractor", value).apply(); }
    boolean pluginSourceViewer() { return prefs.getBoolean("plugin_source_viewer", true); }
    void pluginSourceViewer(boolean value) { prefs.edit().putBoolean("plugin_source_viewer", value).apply(); }
    boolean pluginVideoTools(){return prefs.getBoolean("plugin_video_tools",true);}
    void pluginVideoTools(boolean value){prefs.edit().putBoolean("plugin_video_tools",value).apply();}
    boolean pluginSuperCopy(){return prefs.getBoolean("plugin_super_copy",true);}
    void pluginSuperCopy(boolean value){prefs.edit().putBoolean("plugin_super_copy",value).apply();}
    boolean pluginVisualAdjust(){return prefs.getBoolean("plugin_visual_adjust",true);}
    void pluginVisualAdjust(boolean value){prefs.edit().putBoolean("plugin_visual_adjust",value).apply();}
    boolean pluginPasswordHelper(){return prefs.getBoolean("plugin_password_helper",true);}
    void pluginPasswordHelper(boolean value){prefs.edit().putBoolean("plugin_password_helper",value).apply();}
    boolean pluginDownloadLinks(){return prefs.getBoolean("plugin_download_links",true);}
    void pluginDownloadLinks(boolean value){prefs.edit().putBoolean("plugin_download_links",value).apply();}
    boolean pluginDeveloper(){return prefs.getBoolean("plugin_developer",true);}
    void pluginDeveloper(boolean value){prefs.edit().putBoolean("plugin_developer",value).apply();}
    boolean pluginClipboardGuard(){return prefs.getBoolean("plugin_clipboard_guard",true);}
    void pluginClipboardGuard(boolean value){prefs.edit().putBoolean("plugin_clipboard_guard",value).apply();}
    String appStyle(){return prefs.getString("app_style","mint");}
    void appStyle(String value){prefs.edit().putString("app_style",value).apply();}
    int visualBrightness(){return prefs.getInt("visual_brightness",100);}
    void visualBrightness(int value){prefs.edit().putInt("visual_brightness",value).apply();}
    int visualContrast(){return prefs.getInt("visual_contrast",100);}
    void visualContrast(int value){prefs.edit().putInt("visual_contrast",value).apply();}
    int visualSaturation(){return prefs.getInt("visual_saturation",100);}
    void visualSaturation(int value){prefs.edit().putInt("visual_saturation",value).apply();}
    int visualGray(){return prefs.getInt("visual_gray",0);}
    void visualGray(int value){prefs.edit().putInt("visual_gray",value).apply();}
    String visualTheme(){return prefs.getString("visual_theme","standard");}
    void visualTheme(String value){prefs.edit().putString("visual_theme",value).apply();}
    String visualHistory(){return prefs.getString("visual_history","[]");}
    void addVisualHistory(String value){
        JSONArray history;
        try{history=new JSONArray(visualHistory());}catch(Exception e){history=new JSONArray();}
        JSONArray next=new JSONArray();next.put(value);
        for(int i=0;i<history.length()&&i<5;i++){String old=history.optString(i);if(!value.equals(old))next.put(old);}
        prefs.edit().putString("visual_history",next.toString()).apply();
    }
    boolean saveBrowserHistory() { return prefs.getBoolean("browser_save_history", true); }
    void saveBrowserHistory(boolean value) { prefs.edit().putBoolean("browser_save_history", value).apply(); }
    String browserHome() { return prefs.getString("browser_home", "jian://home"); }
    void browserHome(String value) { prefs.edit().putString("browser_home", value).apply(); }
    boolean safetyEnabled() { return prefs.getBoolean("browser_safety", true); }
    void safetyEnabled(boolean value) { prefs.edit().putBoolean("browser_safety", value).apply(); }
    boolean cleanTrackers() { return prefs.getBoolean("browser_clean_trackers", true); }
    void cleanTrackers(boolean value) { prefs.edit().putBoolean("browser_clean_trackers", value).apply(); }
    String searchEngine() { return prefs.getString("browser_search_engine", "bing"); }
    void searchEngine(String value) { prefs.edit().putString("browser_search_engine", value).apply(); }
    int browserBackgroundBrightness() { return prefs.getInt("browser_background_brightness", 100); }
    void browserBackgroundBrightness(int value) { returnSet("browser_background_brightness", value); }
    int browserBackgroundShade() { return prefs.getInt("browser_background_shade", 12); }
    void browserBackgroundShade(int value) { returnSet("browser_background_shade", value); }
    int browserBackgroundBlur() { return prefs.getInt("browser_background_blur", 0); }
    void browserBackgroundBlur(int value) { returnSet("browser_background_blur", value); }
    int browserBackgroundPosition() { return prefs.getInt("browser_background_position", 50); }
    void browserBackgroundPosition(int value) { returnSet("browser_background_position", value); }
    private void returnSet(String key, int value) { prefs.edit().putInt(key, value).apply(); }

    private JSONArray array(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void ensureDefaults() {
        List<QuickItem> list = prefs.contains(KEY_QUICK) ? quickItems() : new ArrayList<>();
        if (list.isEmpty()) {
            list.add(quick(TYPE_SYSTEM, "Wi‑Fi", "wifi", "系统", "网"));
            list.add(quick(TYPE_SYSTEM, "蓝牙", "bluetooth", "系统", "蓝"));
            list.add(quick(TYPE_SYSTEM, "手电筒", "torch", "系统", "灯"));
            list.add(quick(TYPE_SYSTEM, "亮度", "brightness", "系统", "亮"));
        }
        boolean hasNavigation = false;
        for (QuickItem item : list) if ("https://go.taolove.top/".equals(item.value)) hasNavigation = true;
        if (!hasNavigation) list.add(0, quick(TYPE_WEB, "万能收纳屋", "https://go.taolove.top/", "导航", "芙"));
        addDefaultWeb(list,"DeepSeek","https://chat.deepseek.com/","搜索","D");
        addDefaultWeb(list,"GitHub","https://github.com/","开发","G");
        addDefaultWeb(list,"PDF24 工具箱","https://tools.pdf24.org/zh/","文档","P");
        addDefaultWeb(list,"Convertio 转换","https://convertio.co/zh/","文档","C");
        addDefaultWeb(list,"VirusTotal","https://www.virustotal.com/gui/home/url","安全","V");
        addDefaultWeb(list,"网页翻译","https://translate.google.com/","工具","译");
        saveQuick(list);
    }

    private static void addDefaultWeb(List<QuickItem> list,String title,String url,String group,String glyph){
        for(QuickItem item:list)if(url.equals(item.value))return;
        list.add(quick(TYPE_WEB,title,url,group,glyph));
    }

    private static QuickItem quick(String type, String title, String value, String group, String glyph) {
        QuickItem q = new QuickItem();
        q.type = type; q.title = title; q.value = value; q.group = group; q.glyph = glyph;
        return q;
    }

    static String extractTags(String text) {
        StringBuilder b = new StringBuilder();
        for (String token : text.split("\\s+")) {
            if (token.startsWith("#") && token.length() > 1) {
                if (b.length() > 0) b.append(' ');
                b.append(token);
            }
        }
        return b.toString();
    }
}
