const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const port = Number(process.env.PORT || 8787);
const adminToken = process.env.JIANBOX_ADMIN_TOKEN;
if (!adminToken) { console.error('WARNING: JIANBOX_ADMIN_TOKEN is not set. Set it before deploying to production.'); process.exit(1); }
const dataDir = path.join(__dirname, 'data');
const usersFile = path.join(dataDir, 'users.json');
const updateFile = path.join(dataDir, 'update.json');
fs.mkdirSync(dataDir, { recursive: true });
if (!fs.existsSync(usersFile)) fs.writeFileSync(usersFile, '[]');
if (!fs.existsSync(updateFile)) fs.writeFileSync(updateFile, JSON.stringify({ latestVersion: 12, minVersion: 1, versionName: '3.7.0', force: false, apkUrl: '', notes: '' }, null, 2));

const json = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const save = (file, value) => fs.writeFileSync(file, JSON.stringify(value, null, 2));
const send = (res, code, value) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' }); res.end(JSON.stringify(value)); };
const body = req => new Promise((resolve, reject) => { let value = ''; req.on('data', chunk => { value += chunk; if (value.length > 100000) reject(new Error('too large')); }); req.on('end', () => { try { resolve(value ? JSON.parse(value) : {}); } catch (error) { reject(error); } }); });
const hashPassword = password => { const salt = crypto.randomBytes(16).toString('hex'); return `${salt}:${crypto.scryptSync(password, salt, 64).toString('hex')}`; };
const verifyPassword = (password, stored) => { const [salt, hash] = stored.split(':'); return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), crypto.scryptSync(password, salt, 64)); };
const issueToken = id => `${id}.${crypto.createHmac('sha256', adminToken).update(id).digest('hex')}`;
const authorized = req => req.headers.authorization === `Bearer ${adminToken}`;

http.createServer(async (req, res) => {
  try {
    if (req.method === 'OPTIONS') return send(res, 204, {});
    if (req.url === '/' || req.url.startsWith('/admin')) { const html = fs.readFileSync(path.join(__dirname, 'public', 'admin.html')); res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(html); }
    if (req.url.startsWith('/api/update') && req.method === 'GET') return send(res, 200, json(updateFile));
    if (req.url === '/api/register' && req.method === 'POST') { const input = await body(req); const email = String(input.email || '').trim().toLowerCase(); if (!email || String(input.password || '').length < 6) return send(res, 400, { error: '账号或密码格式不正确' }); const users = json(usersFile); if (users.some(user => user.email === email)) return send(res, 409, { error: '账号已存在' }); const id = crypto.randomUUID(); users.push({ id, email, password: hashPassword(String(input.password)), createdAt: Date.now(), lastSeen: Date.now() }); save(usersFile, users); return send(res, 201, { token: issueToken(id) }); }
    if (req.url === '/api/login' && req.method === 'POST') { const input = await body(req); const users = json(usersFile); const user = users.find(item => item.email === String(input.email || '').trim().toLowerCase()); if (!user || !verifyPassword(String(input.password || ''), user.password)) return send(res, 401, { error: '账号或密码错误' }); user.lastSeen = Date.now(); save(usersFile, users); return send(res, 200, { token: issueToken(user.id) }); }
    if (req.url === '/api/heartbeat' && req.method === 'POST') { const users = json(usersFile); const token = String(req.headers.authorization || '').replace('Bearer ', ''); const id = token.split('.')[0]; const user = users.find(item => item.id === id); if (user) { user.lastSeen = Date.now(); save(usersFile, users); } return send(res, 200, { ok: true }); }
    if (req.url === '/api/admin/stats' && req.method === 'GET') { if (!authorized(req)) return send(res, 401, { error: 'unauthorized' }); const users = json(usersFile); const now = Date.now(); return send(res, 200, { registered: users.length, active24h: users.filter(user => now - user.lastSeen < 86400000).length, active30d: users.filter(user => now - user.lastSeen < 2592000000).length, update: json(updateFile) }); }
    if (req.url === '/api/admin/update' && req.method === 'POST') { if (!authorized(req)) return send(res, 401, { error: 'unauthorized' }); const next = await body(req); save(updateFile, next); return send(res, 200, { ok: true }); }
    send(res, 404, { error: 'not found' });
  } catch (error) { send(res, 500, { error: 'server error' }); }
}).listen(port, () => console.log(`JianBox service: http://localhost:${port}`));
