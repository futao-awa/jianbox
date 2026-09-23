package com.jianbox.app;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes public media URLs without fetching or replaying a page's requests. */
final class MediaUrlExtractor {
    private static final Pattern MEDIA_PATH = Pattern.compile(
            ".*\\.(m3u8|mpd|mp4|m4v|webm|flv|mov|mp3|m4a|aac|flac|ogg|opus|wav)$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> URL_KEYS = new java.util.HashSet<>(java.util.Arrays.asList("url", "src", "file", "play", "playurl", "vurl"));

    static Set<String> extract(String url, Map<String, String> headers) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        boolean mediaAccept = false;
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!"accept".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            String accept = entry.getValue().toLowerCase(Locale.ROOT);
            mediaAccept = accept.contains("application/vnd.apple.mpegurl") || accept.contains("application/x-mpegurl")
                    || accept.contains("application/dash+xml") || accept.contains("video/") || accept.contains("audio/");
        }
        collect(url, mediaAccept, result, 0);
        return result;
    }

    private static void collect(String url, boolean force, Set<String> result, int depth) {
        if (!BrowserUrlRules.isWebUrl(url) || url.length() > 16384 || depth > 2) return;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) return;
            if (force || MEDIA_PATH.matcher(uri.getPath() == null ? "" : uri.getPath()).matches()) result.add(url);
            String query = uri.getRawQuery();
            if (query == null) return;
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || !URL_KEYS.contains(decode(pair[0]).toLowerCase(Locale.ROOT))) continue;
                String nested = decode(pair[1]);
                if (nested.startsWith("//")) nested = uri.getScheme() + ":" + nested;
                if (!BrowserUrlRules.isWebUrl(nested) && nested.matches("(?i)^https?%3a.*")) nested = decode(nested);
                collect(nested, false, result, depth + 1);
            }
        } catch (Exception ignored) { /* Malformed resource URLs must not interrupt loading. */ }
    }

    private static String decode(String value) throws java.io.UnsupportedEncodingException {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    }

    private MediaUrlExtractor() {}
}
