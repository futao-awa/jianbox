package com.jianbox.app;

import android.webkit.WebView;

import java.util.Locale;

/** Removes common search-page advertising chrome without changing ordinary site content. */
final class BrowserCleaner {
    private static final String SCRIPT = "javascript:(()=>{const s='"+
            ".b_ad,.b_adLastChild,#b_results .b_ad,#tads,#bottomads,"+
            ".commercial-unit-desktop-top,[data-text-ad],.results--ads,[data-tuiguang],.ec_wise_ad,"+
            ".biz-tip,[sogou-ad],.ad,.ads,.advertisement,.sponsored,.b_favicon,.favicon,.result__icon"+
            "';document.querySelectorAll(s).forEach(e=>e.remove());})();";

    static void clean(WebView view, String url, boolean enabled) {
        if (!enabled || url == null) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("bing.com/search") || lower.contains("duckduckgo.com") || lower.contains("google.")
                || lower.contains("baidu.com/s") || lower.contains("sogou.com/web")) view.loadUrl(SCRIPT);
    }

    private BrowserCleaner() {}
}
