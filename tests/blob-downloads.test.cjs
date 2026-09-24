const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/blob-downloads.js'), 'utf8');

function environment({ denyBegin = false, cancelWrite = false } = {}) {
    const objects = new Map(), timers = new Map(), calls = [], chunks = [];
    let next = 0, reads = 0, maxSlice = 0;
    class PageURL extends URL {
        static createObjectURL(blob) { const url = `blob:https://page.example/${++next}`; objects.set(url, blob); return url; }
        static revokeObjectURL(url) { objects.delete(url); }
    }
    class Anchor { click() { calls.push(['click']); } }
    class FileReader {
        readAsDataURL(blob) {
            maxSlice = Math.max(maxSlice, blob.size);
            blob.arrayBuffer().then(data => { this.result = `data:application/octet-stream;base64,${Buffer.from(data).toString('base64')}`; this.onload(); });
        }
    }
    const win = {
        URL: PageURL, Blob, FileReader, HTMLAnchorElement: Anchor,
        location: { origin: 'https://page.example' }, frames: [],
        document: { addEventListener() {} }, addEventListener() {},
        setTimeout(fn) { const id = ++next; timers.set(id, fn); return id; },
        clearTimeout(id) { timers.delete(id); },
        async fetch(url) { reads++; if (!objects.has(url)) throw new Error('revoked'); return { ok: true, blob: async () => objects.get(url) }; },
        JianBlobDownloads: {
            begin(...args) { calls.push(['begin', ...args]); return !denyBegin; },
            write(token, sequence, base64) { calls.push(['write', token, sequence]); if (cancelWrite) return false; chunks.push(Buffer.from(base64, 'base64')); return true; },
            finish(token) { calls.push(['finish', token]); return true; },
            fail(token) { calls.push(['fail', token]); }
        }
    };
    vm.runInNewContext(script, { window: win }, { timeout: 1000 });
    return { win, calls, chunks, objects, timers, stats: () => ({ reads, maxSlice }) };
}

test('revoked blob with unattached download anchor retains exact binary bytes and filename', async () => {
    const e = environment(), bytes = Buffer.alloc(150000);
    for (let i = 0; i < bytes.length; i++) bytes[i] = i % 256;
    const url = e.win.URL.createObjectURL(new Blob([bytes], { type: 'image/jpeg' }));
    const anchor = new e.win.HTMLAnchorElement(); anchor.href = url; anchor.download = 'wallpaper.jpg'; anchor.click();
    e.win.URL.revokeObjectURL(url);
    await e.win.__jianBlobDownloads.start({ token: 'authorized', url });
    assert.deepEqual(Buffer.concat(e.chunks), bytes);
    const begin = e.calls.find(x => x[0] === 'begin');
    assert.equal(begin[3], 'wallpaper.jpg'); assert.equal(begin[4], 'image/jpeg'); assert.equal(begin[5], bytes.length);
    assert.deepEqual(e.calls.filter(x => x[0] === 'write').map(x => x[2]), [0, 1, 2, 3]);
    assert.equal(e.stats().maxSlice, 48 * 1024); assert.equal(e.stats().reads, 0);
    assert.equal(e.calls.at(-1)[0], 'finish');
});

test('unretained blob falls back to renderer fetch', async () => {
    const e = environment(), url = 'blob:https://page.example/preexisting';
    e.objects.set(url, new Blob(['downloaded']));
    await e.win.__jianBlobDownloads.start({ token: 'authorized', url });
    assert.equal(e.stats().reads, 1); assert.equal(Buffer.concat(e.chunks).toString(), 'downloaded');
});

test('native rejection and cancellation stop further writes', async () => {
    for (const options of [{ denyBegin: true }, { cancelWrite: true }]) {
        const e = environment(options), url = e.win.URL.createObjectURL(new Blob([Buffer.alloc(100000)]));
        await e.win.__jianBlobDownloads.start({ token: 'invalid-or-canceled', url });
        assert.equal(e.calls.filter(x => x[0] === 'write').length, options.denyBegin ? 0 : 1);
        assert.equal(e.calls.some(x => x[0] === 'finish'), false);
    }
});

test('empty files finish without emitting empty chunks', async () => {
    const e = environment(), url = e.win.URL.createObjectURL(new Blob([]));
    await e.win.__jianBlobDownloads.start({ token: 'authorized', url });
    assert.equal(e.chunks.length, 0); assert.equal(e.calls.at(-1)[0], 'finish');
});

test('capabilities are routed only to the blob origin, including nested frames', async () => {
    const e = environment(), messages = [];
    const nested = { frames: [], postMessage(data, target) { messages.push([data.token, target]); } };
    e.win.frames = [{ frames: [nested], postMessage(data, target) { messages.push([data.token, target]); } }];
    await e.win.__jianBlobDownloads.start({ token: 'authorized', url: 'blob:https://iframe.example/id' });
    assert.deepEqual(messages, [['authorized', 'https://iframe.example'], ['authorized', 'https://iframe.example']]);
    assert.equal(e.stats().reads, 0);
});

test('retention expires, is bounded and installation is idempotent', async () => {
    const e = environment();
    const create = e.win.URL.createObjectURL;
    vm.runInNewContext(script, { window: e.win });
    assert.equal(create, e.win.URL.createObjectURL);
    for (let i = 0; i < 12; i++) e.win.URL.createObjectURL(new Blob(['x']));
    assert.equal(e.timers.size, 8);
    for (const callback of Array.from(e.timers.values())) callback();
    assert.equal(e.timers.size, 0);
});
