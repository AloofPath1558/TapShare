/* TapShare web version.
 *
 * Two devices pair with a six-character code. PeerJS (WebRTC) introduces them through a
 * public pairing service, then the file travels directly between the two.
 *
 * Messages on the data channel:
 *   sender   -> receiver  {t:'offer', name, size, mime}, then raw 16 KB chunks, then {t:'end'}
 *   receiver -> sender    {t:'accept'} | {t:'reject'} | {t:'busy'} | {t:'done', bytes} | {t:'error', message}
 *   either   -> other     {t:'cancel'}
 *
 * Inside the Android app, window.TapShareNative saves received files to Downloads/TapShare.
 */
(() => {
  'use strict';

  const CFG = Object.assign({
    publicUrl: 'https://aloofpath1558.github.io/TapShare/web/',
    peer: {},
    peerScripts: [
      'https://unpkg.com/peerjs@1.5.4/dist/peerjs.min.js',
      'https://cdn.jsdelivr.net/npm/peerjs@1.5.4/dist/peerjs.min.js',
    ],
    qrScripts: [
      'https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.js',
      'https://unpkg.com/qrcode-generator@1.4.4/qrcode.js',
    ],
  }, window.TAPSHARE_CONFIG || {});

  const ID_PREFIX = 'tapshare-';
  const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 32 symbols, no 0/O/1/I
  const CODE_LEN = 6;
  const CHUNK = 16 * 1024;
  const BLOCK = 1024 * 1024;
  const HIGH_WATER = 1024 * 1024;
  const LOW_WATER = 256 * 1024;
  const CONNECT_TIMEOUT = 20000;
  const DONE_TIMEOUT = 60000;
  const NATIVE_FLUSH = 256 * 1024;

  const LIB_ERROR = 'Could not load the connection library. Check your internet connection and try again.';
  const PAIRING_ERROR = 'Could not reach the pairing service. Check your internet connection and try again.';

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const native = window.TapShareNative || null;
  if (native) document.documentElement.classList.add('in-app');

  /* ---------- helpers ---------- */

  function randomString(len, alphabet = ALPHABET) {
    const buf = new Uint32Array(len);
    crypto.getRandomValues(buf);
    let out = '';
    for (let i = 0; i < len; i++) out += alphabet[buf[i] % alphabet.length];
    return out;
  }

  const normalizeCode = (s) => String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  const formatCode = (c) => (c.length > 3 ? c.slice(0, 3) + ' ' + c.slice(3) : c);

  function fmtBytes(n) {
    if (!isFinite(n) || n < 0) return '';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return (i === 0 ? String(n) : n.toFixed(n >= 100 ? 0 : 1)) + ' ' + units[i];
  }

  function sanitizeName(raw) {
    let name = String(raw || 'file').split(/[\\/]/).pop().replace(/[\u0000-\u001f<>:"|?*]/g, '_').trim();
    if (!name || name === '.' || name === '..') name = 'file';
    return name.slice(0, 120);
  }

  function toBase64(u8) {
    let bin = '';
    const STEP = 0x8000;
    for (let i = 0; i < u8.length; i += STEP) {
      bin += String.fromCharCode.apply(null, u8.subarray(i, i + STEP));
    }
    return btoa(bin);
  }

  function describePeerError(err) {
    switch (err && err.type) {
      case 'peer-unavailable':
        return 'No receiver is waiting with that code. Check the code and try again.';
      case 'network':
      case 'socket-error':
      case 'socket-closed':
      case 'server-error':
        return 'Lost the connection to the pairing service. Check your internet connection and try again.';
      case 'browser-incompatible':
        return 'This browser cannot make direct connections. Try a recent Chrome, Safari or Firefox.';
      case 'webrtc':
        return 'The direct connection failed. One of the networks may be blocking it.';
      default:
        return (err && (err.message || err.type)) || 'Something went wrong.';
    }
  }

  /* ---------- optional libraries, loaded on demand ---------- */

  const libs = {};
  const getPeerClass = () => window.Peer || (window.peerjs && window.peerjs.Peer) || null;
  const getQr = () => window.qrcode || null;

  function loadScript(url) {
    return new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = url;
      s.async = true;
      s.onload = resolve;
      s.onerror = () => { s.remove(); reject(new Error('Failed to load ' + url)); };
      document.head.appendChild(s);
    });
  }

  async function loadLib(name, urls, getter) {
    if (getter()) return getter();
    if (!libs[name]) {
      libs[name] = (async () => {
        for (const url of urls) {
          try { await loadScript(url); } catch (e) { continue; }
          if (getter()) return getter();
        }
        throw new Error('Could not load ' + name);
      })();
    }
    try { return await libs[name]; } catch (e) { delete libs[name]; throw e; }
  }

  /* ---------- views and state blocks ---------- */

  let view = 'home';

  function go(name) {
    view = name;
    $$('[data-view]').forEach((el) => { el.hidden = el.dataset.view !== name; });
    window.scrollTo(0, 0);
  }

  function stateUI(root) {
    const title = $('.state-title', root);
    const detail = $('.state-detail', root);
    const bar = $('.bar', root);
    const fill = $('.bar-fill', root);
    const pulse = $('.pulse', root);
    let last = 0;
    return {
      set(t, d = '', opts = {}) {
        title.textContent = t;
        detail.textContent = d;
        root.dataset.tone = opts.tone || '';
        pulse.classList.toggle('on', !!opts.pulse);
      },
      progress(done, total, force = false) {
        const now = performance.now();
        if (!force && done < total && now - last < 90) return;
        last = now;
        const pct = total > 0 ? Math.min(100, (done * 100) / total) : 100;
        bar.hidden = false;
        fill.style.width = pct.toFixed(1) + '%';
        bar.setAttribute('aria-valuenow', String(Math.round(pct)));
      },
      hideProgress() {
        bar.hidden = true;
        fill.style.width = '0%';
        bar.setAttribute('aria-valuenow', '0');
      },
    };
  }

  const ui = { send: stateUI($('#sendState')), recv: stateUI($('#recvState')) };

  /* ---------- session lifecycle ---------- */

  let session = null;

  function cleanupSession(S) {
    S.cancelled = true;
    clearTimeout(S.timer);
    clearTimeout(S.connTimer);
    clearTimeout(S.doneTimer);
    try { if (S.conn) S.conn.close(); } catch (e) { /* ignore */ }
    try { if (S.peer) S.peer.destroy(); } catch (e) { /* ignore */ }
    if (S.wake) { try { S.wake.release(); } catch (e) { /* ignore */ } S.wake = null; }
    if (native && S.nativeId > 0 && !S.nativeClosed) {
      S.nativeClosed = true;
      try { native.abort(S.nativeId); } catch (e) { /* ignore */ }
    }
    S.chunks = null;
    S.queue = null;
  }

  function teardown() {
    if (!session) return;
    const S = session;
    session = null;
    cleanupSession(S);
    if (S.url) URL.revokeObjectURL(S.url);
  }

  function newSession(role) {
    teardown();
    session = { role, ended: false, cancelled: false };
    return session;
  }

  function requestWake(S) {
    if (!(navigator.wakeLock && navigator.wakeLock.request)) return;
    navigator.wakeLock.request('screen').then((lock) => {
      if (S === session && !S.ended) S.wake = lock; else lock.release();
    }).catch(() => { /* optional */ });
  }

  function showEndActions(S, tone) {
    if (S.role === 'send') {
      $('#sendCancel').hidden = true;
      const again = $('#sendAgain');
      again.hidden = false;
      again.textContent = tone === 'ok' ? 'Send another file' : 'Try again';
      S.endTone = tone;
    } else {
      $('#recvCancel').hidden = true;
      $('#codePad').hidden = true;
      $('#incoming').hidden = true;
      const again = $('#recvAgain');
      again.hidden = false;
      again.textContent = tone === 'ok' ? 'Receive another file' : 'Start over';
      if (tone === 'ok' && S.url) {
        const save = $('#saveLink');
        save.href = S.url;
        save.download = S.offer.name;
        save.hidden = false;
      }
    }
  }

  function endSession(S, u, title, detail, tone) {
    if (S !== session || S.ended) return;
    S.ended = true;
    if (S.role === 'receive' && tone === 'ok') {
      // give the final {t:'done'} message a moment to reach the sender
      S.timer = setTimeout(() => cleanupSession(S), 1500);
    } else {
      cleanupSession(S);
    }
    u.set(title, detail, { tone });
    u.hideProgress();
    showEndActions(S, tone);
  }

  function fail(S, u, message) {
    const title = S.role === 'send' ? "Couldn't send the file" : "Couldn't receive the file";
    endSession(S, u, title, message, 'error');
  }

  /* ---------- receive ---------- */

  function joinLink(code) {
    const here = /^https?:$/.test(location.protocol) && !native;
    const base = here ? location.origin + location.pathname : CFG.publicUrl;
    return base + '?join=' + code;
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
      ta.remove();
      return ok;
    }
  }

  function flash(btn, text) {
    const old = btn.dataset.label || btn.textContent;
    btn.dataset.label = old;
    btn.textContent = text;
    clearTimeout(btn._t);
    btn._t = setTimeout(() => { btn.textContent = old; }, 1500);
  }

  async function renderQr(text, code) {
    const box = $('#qr');
    box.hidden = true;
    try {
      const qrcode = await loadLib('qr', CFG.qrScripts, getQr);
      if (!session || session.code !== code) return;
      const qr = qrcode(0, 'M');
      qr.addData(text);
      qr.make();
      const n = qr.getModuleCount();
      const quiet = 2;
      let d = '';
      for (let r = 0; r < n; r++) {
        for (let c = 0; c < n; c++) {
          if (qr.isDark(r, c)) d += 'M' + (c + quiet) + ',' + (r + quiet) + 'h1v1h-1z';
        }
      }
      const size = n + quiet * 2;
      box.innerHTML = '<svg viewBox="0 0 ' + size + ' ' + size + '" shape-rendering="crispEdges" role="img" aria-label="QR code with the link for the sender">' +
        '<rect width="' + size + '" height="' + size + '" fill="#fff"/><path d="' + d + '" fill="#000"/></svg>';
      box.hidden = false;
    } catch (e) { /* the QR code is optional */ }
  }

  function showCode(S) {
    const link = joinLink(S.code);
    $('#codeBig').textContent = formatCode(S.code);
    $('#codePad').hidden = false;
    $('#copyCode').onclick = async () => flash($('#copyCode'), (await copyText(S.code)) ? 'Copied' : 'Copy failed');
    $('#copyLink').onclick = async () => flash($('#copyLink'), (await copyText(link)) ? 'Copied' : 'Copy failed');
    const share = $('#shareLink');
    share.hidden = !(navigator.share && !native);
    share.onclick = () => navigator.share({ title: 'TapShare', text: 'Send me a file with TapShare', url: link }).catch(() => {});
    renderQr(link, S.code);
  }

  function showWaiting(S) {
    $('#incoming').hidden = true;
    $('#recvCancel').hidden = false;
    ui.recv.hideProgress();
    ui.recv.set('Ready to receive', 'Give the code to the sender, or let them open the link.', { pulse: true });
    if (S.code) $('#codePad').hidden = false;
  }

  async function startReceive() {
    const S = newSession('receive');
    go('receive');
    $('#codePad').hidden = true;
    $('#incoming').hidden = true;
    $('#saveLink').hidden = true;
    $('#recvAgain').hidden = true;
    $('#recvCancel').hidden = false;
    ui.recv.hideProgress();
    ui.recv.set('Getting ready…', 'Connecting to the pairing service.', { pulse: true });

    let Peer;
    try { Peer = await loadLib('peerjs', CFG.peerScripts, getPeerClass); } catch (e) { fail(S, ui.recv, LIB_ERROR); return; }
    if (S !== session || S.cancelled) return;
    openReceivePeer(S, Peer, 0);
  }

  function openReceivePeer(S, Peer, attempt) {
    const code = randomString(CODE_LEN);
    const peer = new Peer(ID_PREFIX + code, Object.assign({ debug: 1 }, CFG.peer));
    S.peer = peer;
    S.code = code;
    S.opened = false;
    clearTimeout(S.timer);
    S.timer = setTimeout(() => { if (S === session && !S.opened) fail(S, ui.recv, PAIRING_ERROR); }, CONNECT_TIMEOUT);

    peer.on('open', () => {
      if (S !== session || S.ended || S.peer !== peer) return;
      S.opened = true;
      clearTimeout(S.timer);
      showCode(S);
      showWaiting(S);
    });
    peer.on('connection', (conn) => onIncoming(S, conn));
    peer.on('disconnected', () => {
      if (S === session && S.opened && !S.ended) { try { peer.reconnect(); } catch (e) { /* ignore */ } }
    });
    peer.on('error', (err) => {
      if (S !== session || S.ended || S.peer !== peer) return;
      if (err && err.type === 'unavailable-id' && attempt < 5) {
        try { peer.destroy(); } catch (e) { /* ignore */ }
        openReceivePeer(S, Peer, attempt + 1);
        return;
      }
      fail(S, ui.recv, describePeerError(err));
    });
  }

  function onIncoming(S, conn) {
    if (S !== session || S.ended) { try { conn.close(); } catch (e) { /* ignore */ } return; }
    if (S.conn) {
      // already talking to someone else
      conn.on('open', () => {
        try { conn.send(JSON.stringify({ t: 'busy' })); } catch (e) { /* ignore */ }
        setTimeout(() => { try { conn.close(); } catch (e) { /* ignore */ } }, 400);
      });
      return;
    }
    S.conn = conn;
    S.offer = null;
    S.accepted = false;
    $('#codePad').hidden = true;
    ui.recv.set('Sender connected', 'Waiting for the file details…', { pulse: true });
    conn.on('data', (d) => onRecvData(S, conn, d));
    conn.on('close', () => { if (S.conn === conn) onRecvClosed(S); });
    conn.on('error', (e) => { if (S.conn === conn && !S.ended) fail(S, ui.recv, 'The connection failed: ' + describePeerError(e)); });
  }

  function onRecvClosed(S) {
    if (S !== session || S.ended) return;
    if (S.accepted) {
      fail(S, ui.recv, 'The sender disconnected before the file was complete.');
    } else {
      S.conn = null;
      S.offer = null;
      showWaiting(S);
    }
  }

  function onRecvData(S, conn, d) {
    if (S !== session || S.ended || S.conn !== conn) return;
    if (typeof d === 'string') {
      let msg;
      try { msg = JSON.parse(d); } catch (e) { return; }
      onRecvControl(S, msg);
      return;
    }
    const u8 = d instanceof ArrayBuffer ? new Uint8Array(d)
      : ArrayBuffer.isView(d) ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength) : null;
    if (u8 && S.accepted) onChunk(S, u8);
  }

  function onRecvControl(S, msg) {
    if (!msg || typeof msg !== 'object') return;
    if (msg.t === 'offer' && !S.offer) {
      const size = Number(msg.size);
      if (!isFinite(size) || size < 0) return;
      S.offer = {
        name: sanitizeName(msg.name),
        size,
        mime: typeof msg.mime === 'string' && msg.mime.length < 120 ? msg.mime : 'application/octet-stream',
      };
      $('#inName').textContent = S.offer.name;
      $('#inSize').textContent = fmtBytes(size);
      $('#incoming').hidden = false;
      $('#codePad').hidden = true;
      ui.recv.set('Sender connected', 'Choose Accept to start the transfer.', { pulse: false });
    } else if (msg.t === 'end' && S.accepted) {
      finalizeReceive(S);
    } else if (msg.t === 'cancel') {
      if (S.accepted) fail(S, ui.recv, 'The sender cancelled the transfer.');
      else { S.conn = null; S.offer = null; showWaiting(S); }
    }
  }

  function acceptIncoming() {
    const S = session;
    if (!S || S.role !== 'receive' || !S.offer || S.accepted || S.ended) return;
    const o = S.offer;
    if (native) {
      let id = -1;
      try { id = native.begin(o.name, o.mime); } catch (e) { id = -1; }
      if (!(id > 0)) {
        try { S.conn.send(JSON.stringify({ t: 'error', message: 'The receiver could not create the file.' })); } catch (e) { /* ignore */ }
        fail(S, ui.recv, 'Could not create the file in Downloads.');
        return;
      }
      S.nativeId = id;
      S.queue = [];
      S.queued = 0;
    } else {
      S.chunks = [];
    }
    S.got = 0;
    S.accepted = true;
    $('#incoming').hidden = true;
    ui.recv.set('Receiving…', o.name + ' (' + fmtBytes(o.size) + ')', {});
    ui.recv.progress(0, o.size, true);
    requestWake(S);
    try { S.conn.send(JSON.stringify({ t: 'accept' })); } catch (e) { fail(S, ui.recv, 'Could not reach the sender.'); }
  }

  function declineIncoming() {
    const S = session;
    if (!S || !S.conn || !S.offer || S.accepted) return;
    const conn = S.conn;
    try { conn.send(JSON.stringify({ t: 'reject' })); } catch (e) { /* ignore */ }
    S.conn = null;
    S.offer = null;
    setTimeout(() => { try { conn.close(); } catch (e) { /* ignore */ } }, 400);
    showWaiting(S);
  }

  function flushNative(S) {
    if (!S.queued) return true;
    const all = new Uint8Array(S.queued);
    let off = 0;
    for (const c of S.queue) { all.set(c, off); off += c.byteLength; }
    S.queue = [];
    S.queued = 0;
    return native.write(S.nativeId, toBase64(all)) === true;
  }

  function onChunk(S, u8) {
    S.got += u8.byteLength;
    if (S.got > S.offer.size) { fail(S, ui.recv, 'More data arrived than the sender announced.'); return; }
    if (native) {
      S.queue.push(u8);
      S.queued += u8.byteLength;
      if (S.queued >= NATIVE_FLUSH && !flushNative(S)) { fail(S, ui.recv, 'Could not write to Downloads.'); return; }
    } else {
      S.chunks.push(u8);
    }
    ui.recv.progress(S.got, S.offer.size);
  }

  function finalizeReceive(S) {
    const o = S.offer;
    if (S.got !== o.size) {
      fail(S, ui.recv, 'The file arrived incomplete. Ask the sender to try again.');
      return;
    }
    let detail;
    try {
      if (native) {
        if (!flushNative(S)) throw new Error('write failed');
        const saved = native.finish(S.nativeId);
        S.nativeClosed = true;
        if (!saved) throw new Error('could not finish the file');
        detail = saved + ' was saved to Downloads/TapShare.';
      } else {
        S.url = URL.createObjectURL(new Blob(S.chunks, { type: o.mime }));
        S.chunks = null;
        detail = o.name + ' (' + fmtBytes(o.size) + ') is ready to save.';
      }
    } catch (e) {
      fail(S, ui.recv, 'Could not save the file: ' + e.message);
      return;
    }
    try { S.conn.send(JSON.stringify({ t: 'done', bytes: S.got })); } catch (e) { /* ignore */ }
    ui.recv.progress(1, 1, true);
    endSession(S, ui.recv, 'Received', detail, 'ok');
  }

  /* ---------- send ---------- */

  let file = null;
  const codeInput = $('#codeInput');
  const sendBtn = $('#sendBtn');
  const drop = $('#drop');
  const fileInput = $('#fileInput');

  function updateSendBtn() {
    sendBtn.disabled = !(file && normalizeCode(codeInput.value).length === CODE_LEN);
  }

  function setFile(f) {
    file = f || null;
    drop.classList.toggle('has-file', !!file);
    $('#dropTitle').textContent = file ? file.name : 'Choose a file';
    $('#dropHint').textContent = file ? fmtBytes(file.size) : 'or drop it here';
    if (!file) fileInput.value = '';
    updateSendBtn();
  }

  function showSendForm() {
    $('#sendForm').hidden = false;
    $('#sendState').hidden = true;
    $('#sendAgain').hidden = true;
    $('#sendCancel').hidden = false;
    ui.send.hideProgress();
    updateSendBtn();
  }

  function openSend() {
    teardown();
    showSendForm();
    go('send');
  }

  async function startSend() {
    const code = normalizeCode(codeInput.value);
    if (!file || code.length !== CODE_LEN) return;
    const S = newSession('send');
    S.file = file;
    S.sent = 0;
    $('#sendForm').hidden = true;
    $('#sendState').hidden = false;
    $('#sendAgain').hidden = true;
    $('#sendCancel').hidden = false;
    ui.send.hideProgress();
    ui.send.set('Connecting…', 'Looking for the receiver.', { pulse: true });

    let Peer;
    try { Peer = await loadLib('peerjs', CFG.peerScripts, getPeerClass); } catch (e) { fail(S, ui.send, LIB_ERROR); return; }
    if (S !== session || S.cancelled) return;

    const peer = new Peer(ID_PREFIX + 's-' + randomString(10), Object.assign({ debug: 1 }, CFG.peer));
    S.peer = peer;
    S.timer = setTimeout(() => { if (S === session && !S.opened) fail(S, ui.send, PAIRING_ERROR); }, CONNECT_TIMEOUT);

    peer.on('open', () => {
      if (S !== session || S.ended) return;
      S.opened = true;
      clearTimeout(S.timer);
      const conn = peer.connect(ID_PREFIX + code, { serialization: 'raw', reliable: true });
      S.conn = conn;
      S.connTimer = setTimeout(() => {
        fail(S, ui.send, 'Found the receiver but could not open a direct connection. One of the networks may be blocking it.');
      }, CONNECT_TIMEOUT);
      conn.on('open', () => { clearTimeout(S.connTimer); sendOffer(S); });
      conn.on('data', (d) => onSendData(S, d));
      conn.on('close', () => onSendClosed(S));
      conn.on('error', (e) => fail(S, ui.send, describePeerError(e)));
    });
    peer.on('error', (err) => fail(S, ui.send, describePeerError(err)));
  }

  function sendOffer(S) {
    const f = S.file;
    try {
      S.conn.send(JSON.stringify({ t: 'offer', name: f.name, size: f.size, mime: f.type || 'application/octet-stream' }));
    } catch (e) {
      fail(S, ui.send, 'Could not talk to the receiver.');
      return;
    }
    ui.send.set('Waiting for approval', 'The receiver needs to accept ' + f.name + '.', { pulse: true });
  }

  function onSendClosed(S) {
    if (S !== session || S.ended) return;
    fail(S, ui.send, 'The receiver closed the connection.');
  }

  function onSendData(S, d) {
    if (S !== session || S.ended || typeof d !== 'string') return;
    let msg;
    try { msg = JSON.parse(d); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    switch (msg.t) {
      case 'accept':
        if (!S.streaming) streamFile(S);
        break;
      case 'reject':
        endSession(S, ui.send, 'The file was declined', 'The receiver chose not to accept it.', 'error');
        break;
      case 'busy':
        endSession(S, ui.send, "Couldn't send the file", 'The receiver is already getting a file from someone else.', 'error');
        break;
      case 'cancel':
        endSession(S, ui.send, "Couldn't send the file", 'The receiver cancelled the transfer.', 'error');
        break;
      case 'error':
        fail(S, ui.send, String(msg.message || 'The receiver reported an error.'));
        break;
      case 'done':
        ui.send.progress(1, 1, true);
        endSession(S, ui.send, 'Sent', S.file.name + ' (' + fmtBytes(S.file.size) + ') arrived.', 'ok');
        break;
      default:
        break;
    }
  }

  async function drain(S) {
    for (;;) {
      const dc = S.conn && S.conn.dataChannel;
      const buffered = dc ? dc.bufferedAmount : 0;
      if (buffered <= LOW_WATER || S !== session || S.ended) return;
      await sleep(15);
    }
  }

  async function streamFile(S) {
    S.streaming = true;
    const f = S.file;
    const conn = S.conn;
    ui.send.set('Sending…', f.name + ' (' + fmtBytes(f.size) + ')', {});
    ui.send.progress(0, f.size, true);
    requestWake(S);
    try {
      for (let off = 0; off < f.size; off += BLOCK) {
        const block = await f.slice(off, Math.min(off + BLOCK, f.size)).arrayBuffer();
        for (let i = 0; i < block.byteLength; i += CHUNK) {
          if (S !== session || S.ended) return;
          const piece = block.slice(i, Math.min(i + CHUNK, block.byteLength));
          conn.send(piece);
          S.sent += piece.byteLength;
          const dc = conn.dataChannel;
          if (dc && dc.bufferedAmount > HIGH_WATER) await drain(S);
        }
        ui.send.progress(S.sent, f.size);
      }
      if (S !== session || S.ended) return;
      conn.send(JSON.stringify({ t: 'end' }));
      ui.send.progress(f.size, f.size, true);
      ui.send.set('Finishing…', 'Waiting for the receiver to confirm.', {});
      S.doneTimer = setTimeout(() => fail(S, ui.send, 'The receiver did not confirm the transfer.'), DONE_TIMEOUT);
    } catch (e) {
      if (S === session && !S.ended) fail(S, ui.send, 'Could not read or send the file: ' + (e && e.message ? e.message : e));
    }
  }

  // Leaves the current transfer. The other side is told first, then the connection closes.
  function cancelCurrent() {
    const S = session;
    session = null;
    if (S) {
      if (S.conn && !S.ended) {
        try { S.conn.send(JSON.stringify({ t: 'cancel' })); } catch (e) { /* ignore */ }
      }
      S.ended = true;
      setTimeout(() => {
        cleanupSession(S);
        if (S.url) URL.revokeObjectURL(S.url);
      }, 400);
    }
    go('home');
  }

  /* ---------- wiring ---------- */

  $$('[data-go]').forEach((btn) => btn.addEventListener('click', () => {
    const target = btn.dataset.go;
    if (target === 'send') openSend();
    else if (target === 'receive') startReceive();
    else cancelCurrent();
  }));

  fileInput.addEventListener('change', () => setFile(fileInput.files && fileInput.files[0]));
  ['dragenter', 'dragover'].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('drag'); }));
  ['dragleave', 'drop'].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove('drag'); }));
  drop.addEventListener('drop', (e) => { if (e.dataTransfer && e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]); });

  codeInput.addEventListener('input', () => {
    codeInput.value = formatCode(normalizeCode(codeInput.value).slice(0, CODE_LEN));
    updateSendBtn();
  });
  codeInput.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !sendBtn.disabled) startSend(); });
  sendBtn.addEventListener('click', startSend);

  $('#sendCancel').addEventListener('click', cancelCurrent);
  $('#recvCancel').addEventListener('click', cancelCurrent);
  $('#sendAgain').addEventListener('click', () => {
    const tone = session && session.endTone;
    teardown();
    if (tone === 'ok') setFile(null);
    showSendForm();
  });
  $('#recvAgain').addEventListener('click', startReceive);
  $('#acceptBtn').addEventListener('click', acceptIncoming);
  $('#declineBtn').addEventListener('click', declineIncoming);

  // Lets the Android app's back button step out of a sub-view first.
  window.TapShareBack = () => {
    if (view === 'home') return false;
    cancelCurrent();
    return true;
  };

  const joinParam = normalizeCode(new URLSearchParams(location.search).get('join') || '');
  if (joinParam.length === CODE_LEN) {
    openSend();
    codeInput.value = formatCode(joinParam);
    updateSendBtn();
    $('#dropHint').textContent = 'then it goes to code ' + formatCode(joinParam);
  } else {
    go('home');
  }
})();
