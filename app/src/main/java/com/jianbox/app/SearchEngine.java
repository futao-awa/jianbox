package com.jianbox.app;

import android.net.Uri;

import java.util.Locale;

final class SearchEngine {
    static final String[] IDS = {"bing", "duckduckgo", "google", "baidu", "sogou"};

    static String label(String id) {
        if ("duckduckgo".equals(id)) return "DuckDuckGo";
        if ("google".equals(id)) return "Google";
        if ("baidu".equals(id)) return "百度";
        if ("sogou".equals(id)) return "搜狗";
        return "Bing";
    }

    static String glyph(String id) {
        if ("duckduckgo".equals(id)) return "D";
        if ("google".equals(id)) return "G";
        if ("baidu".equals(id)) return "百";
        if ("sogou".equals(id)) return "搜";
        return "B";
    }

    static int color(String id) {
        if ("duckduckgo".equals(id)) return 0xFFDE5833;
        if ("google".equals(id)) return 0xFF4285F4;
        if ("baidu".equals(id)) return 0xFF2932E1;
        if ("sogou".equals(id)) return 0xFFFB4B4B;
        return 0xFF1683D8;
    }

    static String url(String id, String query) {
        String key = query == null ? "" : query.trim();
        String encoded = Uri.encode(key);
        if ("duckduckgo".equals(id)) return "https://duckduckgo.com/?q=" + encoded;
        if ("google".equals(id)) return "https://www.google.com/search?q=" + encoded;
        if ("baidu".equals(id)) return "https://www.baidu.com/s?wd=" + encoded;
        if ("sogou".equals(id)) return "https://www.sogou.com/web?query=" + encoded;
        return "https://www.bing.com/search?q=" + encoded;
    }

    static String action(String id) {
        if ("duckduckgo".equals(id)) return "https://duckduckgo.com/";
        if ("google".equals(id)) return "https://www.google.com/search";
        if ("baidu".equals(id)) return "https://www.baidu.com/s";
        if ("sogou".equals(id)) return "https://www.sogou.com/web";
        return "https://www.bing.com/search";
    }

    static String queryKey(String id) {
        if ("baidu".equals(id)) return "wd";
        if ("sogou".equals(id)) return "query";
        return "q";
    }

    static String safeId(String id) {
        for (String known : IDS) if (known.equals(id == null ? "" : id.toLowerCase(Locale.ROOT))) return known;
        return "bing";
    }

    private SearchEngine() {}
}
