package com.jianbox.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** URL decisions shared by the full and floating browsers. */
final class BrowserUrlRules {
    private static final Set<String> TRACKERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "spm"));
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adsystem.com", "umeng.com", "cnzz.com", "tanx.com", "adnxs.com",
            "taboola.com", "outbrain.com", "amazon-adsystem.com", "pangolin-sdk-toutiao.com"
    };

    static boolean isWebUrl(String url) {
        return url != null && (url.regionMatches(true, 0, "https://", 0, 8)
                || url.regionMatches(true, 0, "http://", 0, 7));
    }

    static boolean isWebViewUrl(String url) {
        return isWebUrl(url) || "about:blank".equals(url)
                || (url != null && url.startsWith("blob:"));
    }

    static boolean isHome(String url) {
        if ("jian://home".equals(url)) return true;
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && "jian.home".equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) { return false; }
    }

    static boolean isAdHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String root : AD_HOSTS) if (host.equals(root) || host.endsWith("." + root)) return true;
            return host.startsWith("adservice.") || host.contains(".adservice.");
        } catch (Exception ignored) { return false; }
    }

    static String cleanTracking(String url) {
        if (!isWebUrl(url)) return url;
        try {
            int fragment = url.indexOf('#');
            int query = url.indexOf('?');
            if (query < 0 || (fragment >= 0 && query > fragment)) return url;
            int end = fragment < 0 ? url.length() : fragment;
            String[] parts = url.substring(query + 1, end).split("&", -1);
            List<String> kept = new ArrayList<>();
            for (String part : parts) {
                String key = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8.name())
                        .toLowerCase(Locale.ROOT);
                // Signed links must retain every byte, including marketing-looking fields.
                if (key.equals("sign") || key.equals("sig") || key.equals("signature") || key.equals("token")
                        || key.equals("auth_key") || key.equals("policy") || key.startsWith("x-amz-")
                        || key.startsWith("x-goog-")) return url;
                if (!key.startsWith("utm_") && !TRACKERS.contains(key)) kept.add(part);
            }
            if (kept.size() == parts.length) return url;
            // Preserve ordering, repeated parameters, escaping and valueless flags verbatim.
            return url.substring(0, query) + (kept.isEmpty() ? "" : "?" + String.join("&", kept))
                    + url.substring(end);
        } catch (Exception ignored) { return url; }
    }

    private BrowserUrlRules() {}
}
