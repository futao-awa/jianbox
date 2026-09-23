package com.jianbox.app;

import java.util.Map;
import java.util.Set;

/** Dependency-free tests for the URL policy and network media extraction. */
public final class BrowserRegressionTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        equal(true, BrowserUrlRules.isWebViewUrl("about:blank"));
        equal(true, BrowserUrlRules.isWebViewUrl("blob:https://player.example/id"));
        equal(true, BrowserUrlRules.isWebViewUrl("https://player.example/?source=one"));
        equal(false, BrowserUrlRules.isWebViewUrl("intent://app"));
        equal(false, BrowserUrlRules.isWebViewUrl("jian://home"));
        equal(true, BrowserUrlRules.isHome("https://jian.home/"));
        equal(false, BrowserUrlRules.isHome("https://jian.home.evil.example/"));
        equal(false, BrowserUrlRules.isHome("https://jian.home@evil.example/"));
        equal(true, BrowserUrlRules.isAdHost("https://ad.doubleclick.net/script.js"));
        equal(false, BrowserUrlRules.isAdHost("https://example.com/?next=doubleclick.net"));
        equal(false, BrowserUrlRules.isAdHost("https://notdoubleclick.net/script.js"));

        String functional = "https://example.com/?from=nav&source=line1&ref=route&referrer=page";
        equal(functional, BrowserUrlRules.cleanTracking(functional));
        equal("https://example.com/?url=https%3a%2f%2fx.test%2fa%3fx%3d1%26y%3d2&a=1&flag&a=2&q=a+b#play",
                BrowserUrlRules.cleanTracking("https://example.com/?url=https%3a%2f%2fx.test%2fa%3fx%3d1%26y%3d2&a=1&utm_source=nav&flag&a=2&q=a+b#play"));
        equal("https://example.com/#play", BrowserUrlRules.cleanTracking("https://example.com/?utm_source=nav#play"));
        equal("https://example.com/#/?utm_source=nav", BrowserUrlRules.cleanTracking("https://example.com/#/?utm_source=nav"));
        for (String key : new String[]{"sign", "sig", "signature", "token", "auth_key", "Policy", "X-Amz-Signature", "X-Goog-Credential"}) {
            String signed = "https://cdn.example/play?utm_source=nav&"+key+"=a%2Bb%3D";
            equal(signed, BrowserUrlRules.cleanTracking(signed));
        }
        equal("https://example.com/?bad%=a&utm_source=nav", BrowserUrlRules.cleanTracking("https://example.com/?bad%=a&utm_source=nav"));

        String stream = "https://cdn.example:666/live/INDEX.M3U8?token=a%2Bb%3D";
        equal(Set.of(stream), MediaUrlExtractor.extract(stream, null));
        equal(Set.of("https://cdn.example/live.m3u8?token=a%2Bb"), MediaUrlExtractor.extract(
                "https://player.example/?url=https%3A%2F%2Fcdn.example%2Flive.m3u8%3Ftoken%3Da%252Bb", null));
        equal(Set.of("https://cdn.example/live.mpd"), MediaUrlExtractor.extract(
                "https://player.example/?url=https%253A%252F%252Fcdn.example%252Flive.mpd", null));
        equal(Set.of(), MediaUrlExtractor.extract("https://player.example/?title=movie.mp4", null));
        equal(Set.of(), MediaUrlExtractor.extract("https://cdn.example/part001.ts", null));
        equal(Set.of(), MediaUrlExtractor.extract("https://cdn.example/part001.m4s", null));
        equal(Set.of(), MediaUrlExtractor.extract("blob:https://example.com/movie.mp4", null));
        equal(Set.of(), MediaUrlExtractor.extract("data:video/mp4;base64,AAAA", null));
        equal(Set.of("https://cdn.example/stream?id=1"), MediaUrlExtractor.extract(
                "https://cdn.example/stream?id=1", Map.of("aCcEpT", "application/vnd.apple.mpegurl")));
        equal(Set.of(), MediaUrlExtractor.extract("https://cdn.example/script.js", Map.of("Accept", "*/*")));
        System.out.println("Browser regression checks passed: " + checks);
    }
}
