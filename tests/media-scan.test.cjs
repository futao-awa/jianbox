const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/media-scan.js'), 'utf8');

function page({ elements = [], links = [], resources = [], frames = [], globals = {} } = {}) {
    return {
        document: {
            baseURI: 'https://page.example/watch/',
            querySelectorAll(selector) { return selector.startsWith('video,') ? elements : links; }
        },
        performance: { getEntriesByType: () => resources }, frames, ...globals
    };
}
function element(fields) { return { getAttribute: () => null, ...fields }; }
function scan(window) {
    return JSON.parse(vm.runInNewContext(script, { window, URL, atob, unescape }, { timeout: 1000 }));
}

test('MacCMS Base64 plus percent-encoded URL is available before a video element exists', () => {
    const url = 'https://cdn.example:666/episode/index.m3u8?token=a%2Bb%3D';
    const encoded = btoa(encodeURIComponent(url));
    assert.deepEqual(scan(page({ globals: { player_aaaa: { encrypt: 2, url: encoded } } })), [url]);
});

test('plain and URL-encoded player configs, malformed Base64 isolation', () => {
    assert.deepEqual(scan(page({ globals: {
        player_one: { encrypt: 0, url: '//cdn.example/movie.mp4' },
        player_two: { encrypt: 1, url: 'https%3A%2F%2Fcdn.example%2Flive.mpd' },
        player_bad: { encrypt: 2, url: 'not base64!' }
    } })), ['https://cdn.example/movie.mp4', 'https://cdn.example/live.mpd']);
});

test('same-origin nested players are scanned and cross-origin frames do not abort scanning', () => {
    const blocked = Object.defineProperty({}, 'document', { get() { throw new Error('cross origin'); } });
    const child = page({ elements: [element({ src: 'https://cdn.example/stream?id=1' })] });
    assert.deepEqual(scan(page({ frames: [blocked, child] })), ['https://cdn.example/stream?id=1']);
});

test('blob players expose manifests through resource timing without listing media segments', () => {
    assert.deepEqual(scan(page({
        elements: [element({ src: 'blob:https://page.example/id' })],
        resources: [
            { name: 'https://cdn.example/live.m3u8?sig=a%2Bb', initiatorType: 'fetch' },
            { name: 'https://cdn.example/seg.ts', initiatorType: 'fetch' },
            { name: 'https://cdn.example/seg.m4s', initiatorType: 'fetch' }
        ]
    })), ['https://cdn.example/live.m3u8?sig=a%2Bb']);
});

test('iframe wrapper yields its nested media URL instead of the HTML player URL', () => {
    assert.deepEqual(scan(page({ links: [element({ src: 'https://player.example/?url=https%3A%2F%2Fcdn.example%2Fvideo.m3u8%3Ftoken%3Da%252Bb' })] })),
        ['https://cdn.example/video.m3u8?token=a%2Bb']);
});

test('relative URLs, duplicates and unsafe protocols', () => {
    assert.deepEqual(scan(page({ elements: [
        element({ src: '../video.mp4', currentSrc: 'https://page.example/video.mp4' }),
        element({ src: 'javascript:alert(1)' }), element({ src: 'data:video/mp4;base64,AAA' }),
        element({ src: 'file:///sdcard/video.mp4' })
    ], links: [element({ href: 'https://page.example/?title=not-a-video.mp4' })] })), ['https://page.example/video.mp4']);
});

test('refresh sees media inserted after the first scan', () => {
    const elements = [];
    const window = page({ elements });
    assert.deepEqual(scan(window), []);
    elements.push(element({ src: 'https://cdn.example/late.mp4' }));
    assert.deepEqual(scan(window), ['https://cdn.example/late.mp4']);
});

test('capture is bounded', () => {
    const elements = Array.from({ length: 200 }, (_, i) => element({ src: `https://cdn.example/${i}.mp4` }));
    assert.equal(scan(page({ elements })).length, 160);
});
