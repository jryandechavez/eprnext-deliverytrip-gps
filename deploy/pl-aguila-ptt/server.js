'use strict';

const http = require('http');
const http2 = require('http2');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = Number(process.env.PORT || 8765);
const ERP_URL = process.env.ERP_URL || 'https://soa.pl-aguila.com';
const AUTH_TIMEOUT_MS = 10000;
const MAX_FRAME_BYTES = 65536;
const APNS_KEY_ID = process.env.APNS_KEY_ID || '';
const APNS_TEAM_ID = process.env.APNS_TEAM_ID || '';
const APNS_BUNDLE_ID = process.env.APNS_BUNDLE_ID || '';
const APNS_KEY_FILE = process.env.APNS_KEY_FILE || '';
const APNS_ENVIRONMENT = process.env.APNS_ENVIRONMENT || 'production';
const REGISTRY_FILE = process.env.REGISTRY_FILE || '/var/lib/pl-aguila-ptt/ios-devices.json';
const clients = new Set();
let transmitter = null;
let transmissionTimer = null;
let apnsJwt = null;
let apnsJwtCreatedAt = 0;
let iosDevices = loadRegistry();

function loadRegistry() {
  try {
    const value = JSON.parse(fs.readFileSync(REGISTRY_FILE, 'utf8'));
    return value && typeof value === 'object' ? value : {};
  } catch (_) {
    return {};
  }
}

function saveRegistry() {
  try {
    fs.mkdirSync(require('path').dirname(REGISTRY_FILE), { recursive: true });
    const temporary = `${REGISTRY_FILE}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(iosDevices), { mode: 0o600 });
    fs.renameSync(temporary, REGISTRY_FILE);
  } catch (error) {
    console.error('Unable to persist iOS PTT registrations:', error.message);
  }
}

function base64url(value) {
  return Buffer.from(value).toString('base64url');
}

function providerToken() {
  const now = Math.floor(Date.now() / 1000);
  if (apnsJwt && now - apnsJwtCreatedAt < 50 * 60) return apnsJwt;
  const header = base64url(JSON.stringify({ alg: 'ES256', kid: APNS_KEY_ID }));
  const claims = base64url(JSON.stringify({ iss: APNS_TEAM_ID, iat: now }));
  const signer = crypto.createSign('SHA256');
  signer.update(`${header}.${claims}`);
  signer.end();
  const signature = signer.sign({ key: fs.readFileSync(APNS_KEY_FILE), dsaEncoding: 'ieee-p1363' });
  apnsJwt = `${header}.${claims}.${base64url(signature)}`;
  apnsJwtCreatedAt = now;
  return apnsJwt;
}

function sendPttPush(registration, speaker) {
  if (!APNS_KEY_ID || !APNS_TEAM_ID || !APNS_BUNDLE_ID || !APNS_KEY_FILE) return Promise.resolve();
  const host = registration.environment === 'sandbox' || APNS_ENVIRONMENT === 'sandbox' ? 'https://api.sandbox.push.apple.com' : 'https://api.push.apple.com';
  return new Promise(resolve => {
    let client;
    try { client = http2.connect(host); } catch (error) { console.error('APNs connection:', error.message); return resolve(); }
    client.on('error', error => console.error('APNs connection:', error.message));
    const request = client.request({
      ':method': 'POST',
      ':path': `/3/device/${registration.pushToken}`,
      authorization: `bearer ${providerToken()}`,
      'apns-push-type': 'pushtotalk',
      'apns-topic': `${APNS_BUNDLE_ID}.voip-ptt`,
      'apns-priority': '10',
      'apns-expiration': '0',
      'content-type': 'application/json'
    });
    let responseBody = '';
    request.setEncoding('utf8');
    request.on('response', headers => {
      const status = Number(headers[':status']);
      request.on('data', chunk => { responseBody += chunk; });
      request.on('end', () => {
        if (status === 410 || (status === 400 && responseBody.includes('BadDeviceToken'))) {
          delete iosDevices[registration.pushToken];
          saveRegistry();
        }
        if (status < 200 || status >= 300) console.error(`APNs ${status}: ${responseBody}`);
        else console.log(`APNs PTT wake accepted for ${registration.deviceId} (${registration.environment})`);
        client.close();
        resolve();
      });
    });
    request.on('error', error => { console.error('APNs request:', error.message); client.close(); resolve(); });
    request.end(JSON.stringify({ activeSpeaker: speaker || 'Fleet Radio' }));
  });
}

function wakeBackgroundIos(sender) {
  const registrations = Object.values(iosDevices).filter(item => item.deviceId !== sender.deviceId);
  Promise.allSettled(registrations.map(item => sendPttPush(item, sender.user || sender.deviceId)));
}

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ ok: true, clients: clients.size, transmitting: !!transmitter }));
  }
  res.writeHead(404).end();
});

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME_BYTES });

server.on('upgrade', (req, socket, head) => {
  if (req.url !== '/ptt' && req.url !== '/ptt/') return socket.destroy();
  wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws));
});

async function authenticate(sid) {
  if (!sid || sid.length > 256) return null;
  return new Promise(resolve => {
    const target = new URL('/api/method/frappe.auth.get_logged_user', ERP_URL);
    const request = https.get(target, {
      headers: { Cookie: `sid=${sid}`, Accept: 'application/json' },
      timeout: AUTH_TIMEOUT_MS
    }, response => {
      let encoded = '';
      response.setEncoding('utf8');
      response.on('data', chunk => { if (encoded.length < 65536) encoded += chunk; });
      response.on('end', () => {
        if (response.statusCode < 200 || response.statusCode >= 300) return resolve(null);
        try {
          const body = JSON.parse(encoded);
          resolve(body.message && body.message !== 'Guest' ? String(body.message) : null);
        } catch (_) { resolve(null); }
      });
    });
    request.on('timeout', () => request.destroy());
    request.on('error', () => resolve(null));
  });
}

function sendJson(ws, value) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
}

function broadcast(value, except) {
  const encoded = typeof value === 'string' || Buffer.isBuffer(value) ? value : JSON.stringify(value);
  for (const client of clients) {
    if (client !== except && client.authed && client.readyState === WebSocket.OPEN) client.send(encoded);
  }
}

function endTransmission(ws) {
  if (transmitter !== ws) return;
  if (transmissionTimer) clearTimeout(transmissionTimer);
  transmissionTimer = null;
  transmitter = null;
  broadcast({ type: 'tx_end', deviceId: ws.deviceId }, ws);
}

wss.on('connection', ws => {
  ws.authed = false;
  ws.isAlive = true;
  clients.add(ws);
  const authTimer = setTimeout(() => { if (!ws.authed) ws.close(4001, 'Authentication required'); }, AUTH_TIMEOUT_MS);

  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', async (data, isBinary) => {
    if (!ws.authed) {
      if (isBinary) return ws.close(4001, 'Authenticate first');
      let message;
      try { message = JSON.parse(data.toString()); } catch (_) { return ws.close(4002, 'Invalid authentication'); }
      if (message.type !== 'auth') return ws.close(4001, 'Authenticate first');
      const user = await authenticate(String(message.sid || ''));
      if (!user) return ws.close(4003, 'ERPNext login expired');
      clearTimeout(authTimer);
      ws.authed = true;
      ws.user = user;
      ws.deviceId = String(message.deviceId || 'Android').slice(0, 80);
      console.log(`PTT client authenticated: ${ws.deviceId} (${ws.user})`);
      sendJson(ws, { type: 'ready', transmitting: !!transmitter });
      if (transmitter && transmitter !== ws) {
        sendJson(ws, { type: 'tx_start', deviceId: transmitter.deviceId, user: transmitter.user });
      }
      return;
    }

    if (isBinary) {
      if (transmitter === ws) broadcast(Buffer.from(data), ws);
      return;
    }

    let message;
    try { message = JSON.parse(data.toString()); } catch (_) { return; }
    if (message.type === 'ptt_register') {
      const pushToken = String(message.pushToken || '').toLowerCase();
      const channelUUID = String(message.channelUUID || '');
      const environment = message.environment === 'sandbox' ? 'sandbox' : 'production';
      if (!/^[0-9a-f]{32,512}$/.test(pushToken) || channelUUID.length > 64) {
        return sendJson(ws, { type: 'ptt_registration_error' });
      }
      iosDevices[pushToken] = { pushToken, channelUUID, environment, deviceId: ws.deviceId, user: ws.user, updatedAt: new Date().toISOString() };
      saveRegistry();
      return sendJson(ws, { type: 'ptt_registered' });
    } else if (message.type === 'tx_start') {
      if (transmitter && transmitter !== ws) return sendJson(ws, { type: 'busy' });
      transmitter = ws;
      console.log(`PTT transmission started by ${ws.deviceId}; waking registered iOS devices`);
      broadcast({ type: 'tx_start', deviceId: ws.deviceId, user: ws.user }, ws);
      wakeBackgroundIos(ws);
      sendJson(ws, { type: 'tx_granted' });
      transmissionTimer = setTimeout(() => {
        if (transmitter === ws) {
          endTransmission(ws);
          sendJson(ws, { type: 'tx_limit', seconds: 60 });
        }
      }, 60000);
    } else if (message.type === 'tx_end') {
      endTransmission(ws);
    }
  });

  ws.on('close', () => { clearTimeout(authTimer); endTransmission(ws); clients.delete(ws); });
  ws.on('error', () => {});
});

const heartbeat = setInterval(() => {
  for (const ws of clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

wss.on('close', () => clearInterval(heartbeat));
server.listen(PORT, '127.0.0.1', () => console.log(`PTT relay listening on 127.0.0.1:${PORT}`));
