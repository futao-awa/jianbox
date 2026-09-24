(function install(win) {
    'use strict';
    if (win.__jianBlobDownloads) return;
    const bridge = win.JianBlobDownloads;
    if (!bridge) return;
    const retained = new Map(), names = new Map(), active = new Set();
    const lifetime = 120000, cacheLimit = 256 * 1024 * 1024, chunkSize = 48 * 1024;
    let retainedBytes = 0;

    function forget(url) {
        const entry = retained.get(url);
        if (entry) { retainedBytes -= entry.blob.size; win.clearTimeout(entry.timer); retained.delete(url); }
        names.delete(url);
    }
    const create = win.URL.createObjectURL.bind(win.URL);
    win.URL.createObjectURL = function (object) {
        const url = create(object);
        // Hold a bounded reference briefly: sites may revoke the URL immediately after clicking.
        if (object instanceof win.Blob && object.size <= cacheLimit) {
            while (retained.size && (retained.size >= 8 || retainedBytes + object.size > cacheLimit)) forget(retained.keys().next().value);
            retained.set(url, { blob: object, timer: win.setTimeout(() => forget(url), lifetime) });
            retainedBytes += object.size;
        }
        return url;
    };
    function remember(anchor) {
        if (anchor && /^blob:/i.test(anchor.href || '') && anchor.download) {
            if (names.size >= 32) names.delete(names.keys().next().value);
            names.set(anchor.href, String(anchor.download).slice(0, 200));
        }
    }
    const click = win.HTMLAnchorElement.prototype.click;
    win.HTMLAnchorElement.prototype.click = function () { remember(this); return click.apply(this, arguments); };
    win.document.addEventListener('click', event => remember(event.target && event.target.closest && event.target.closest('a[download]')), true);

    function encoded(blob) {
        return new Promise((resolve, reject) => {
            const reader = new win.FileReader();
            reader.onload = () => resolve(String(reader.result).split(',')[1]);
            reader.onerror = () => reject(new Error('读取文件分块失败'));
            reader.readAsDataURL(blob);
        });
    }
    async function read(command) {
        if (!command || typeof command.token !== 'string' || !/^blob:/i.test(command.url || '') || active.has(command.token)) return;
        let begun = false;
        active.add(command.token);
        try {
            let blob = retained.has(command.url) ? retained.get(command.url).blob : null;
            if (!blob) {
                const response = await win.fetch(command.url);
                if (!response.ok) return;
                blob = await response.blob();
            }
            // Native code accepts only a download it has already authorized, once, with a byte limit.
            begun = bridge.begin(command.token, command.url, names.get(command.url) || '', blob.type || '', blob.size);
            if (!begun) return;
            let sequence = 0;
            for (let offset = 0; offset < blob.size; offset += chunkSize) {
                const data = await encoded(blob.slice(offset, offset + chunkSize));
                if (!bridge.write(command.token, sequence++, data)) return;
            }
            bridge.finish(command.token);
        } catch (ignored) {
            // Another same-origin frame may own a revoked blob; only the frame that began may fail it.
            if (begun) bridge.fail(command.token);
        } finally { active.delete(command.token); }
    }

    win.addEventListener('message', event => {
        if (event.data && event.data.kind === 'jianbox-blob-download-v1') read(event.data);
    });
    win.__jianBlobDownloads = {
        start(command) {
            command.kind = 'jianbox-blob-download-v1';
            let origin;
            try { origin = new win.URL(command.url).origin; } catch (ignored) { return; }
            // Send the capability only to frames of the blob's origin, including nested players.
            function dispatch(frame, depth) {
                if (depth > 8) return;
                try {
                    if (frame !== win && origin !== 'null') frame.postMessage(command, origin);
                    for (let i = 0; i < frame.frames.length && i < 64; i++) dispatch(frame.frames[i], depth + 1);
                } catch (ignored) {}
            }
            dispatch(win, 0);
            return origin === win.location.origin ? read(command) : undefined;
        }
    };
    // Compatibility fallback for WebViews without document-start injection.
    for (let i = 0; i < win.frames.length && i < 64; i++) {
        try { if (win.frames[i].document) install(win.frames[i]); } catch (ignored) {}
    }
})(window);
