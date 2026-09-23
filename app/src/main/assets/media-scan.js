(function () {
    'use strict';
    const out = new Set();
    const visited = new Set();
    const mediaPath = /\.(m3u8|mpd|mp4|m4v|webm|flv|mov|mp3|m4a|aac|flac|ogg|opus|wav)$/i;
    const urlKeys = /^(url|src|file|play|playurl|vurl)$/i;

    function add(value, base, force, depth = 0) {
        if (typeof value !== 'string' || !value || value.length > 16384 || out.size >= 160 || depth > 2) return;
        try {
            const url = new URL(value, base);
            if (!/^https?:$/.test(url.protocol)) return;
            if (force || mediaPath.test(url.pathname)) out.add(url.href);
            url.searchParams.forEach((nested, key) => {
                if (!urlKeys.test(key)) return;
                if (/^https?%3a/i.test(nested)) nested = decodeURIComponent(nested);
                if (/^(https?:)?\/\//i.test(nested)) add(nested, base, false, depth + 1);
            });
        } catch (ignored) { /* A malformed candidate does not invalidate other media. */ }
    }

    function playerConfig(config, base) {
        if (!config || typeof config !== 'object' || typeof config.url !== 'string') return;
        try {
            let url = config.url;
            // MacCMS calls transport encoding "encrypt"; this is a public player URL,
            // not a DRM key or a decryption operation on the media itself.
            if (String(config.encrypt) === '2') url = atob(url);
            if (String(config.encrypt) === '1' || String(config.encrypt) === '2') {
                try { url = decodeURIComponent(url); } catch (ignored) { url = unescape(url); }
            }
            add(url, base, false);
        } catch (ignored) { /* Invalid Base64 should not stop DOM/resource scanning. */ }
    }

    function scan(win, depth = 0) {
        if (depth > 8 || visited.has(win) || visited.size >= 64) return;
        visited.add(win);
        let doc;
        try { doc = win.document; } catch (ignored) { return; }
        if (!doc) return;
        const base = doc.baseURI || win.location.href;
        doc.querySelectorAll('video,audio,video source,audio source').forEach(element => {
            add(element.currentSrc, base, true);
            add(element.src, base, true);
            add(element.getAttribute('src'), base, true);
            add(element.getAttribute('data-src'), base, true);
        });
        doc.querySelectorAll('a[href],iframe[src],embed[src],object[data]').forEach(element => {
            add(element.href || element.src || element.data, base, false);
        });
        try {
            win.performance.getEntriesByType('resource').forEach(entry => {
                add(entry.name, base, entry.initiatorType === 'video' || entry.initiatorType === 'audio');
            });
        } catch (ignored) {}
        // Only inspect the established player configuration fields, never eval script text.
        try {
            Object.keys(win).filter(key => /^player_[a-z0-9_]+$/i.test(key)).slice(0, 32).forEach(key => {
                const property = Object.getOwnPropertyDescriptor(win, key);
                if (property && 'value' in property) playerConfig(property.value, base);
            });
            if (win.MacPlayer && typeof win.MacPlayer.PlayUrl === 'string') add(win.MacPlayer.PlayUrl, base, false);
        } catch (ignored) {}
        for (let i = 0; i < win.frames.length && i < 64; i++) {
            try { scan(win.frames[i], depth + 1); } catch (ignored) {}
        }
    }

    scan(window);
    return JSON.stringify(Array.from(out));
})();
