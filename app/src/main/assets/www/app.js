(function () {
  'use strict';
  if (window.__nexusInit) return;          // cegah inisialisasi / listener ganda
  window.__nexusInit = true;

  var $ = function (id) { return document.getElementById(id); };
  var TAGS = ['Hero', 'Emblem', 'Spell', 'Strategi', 'Lainnya'];
  var COLORS = ['#a855f7', '#22d3ee', '#f43f5e', '#22c55e', '#f59e0b', '#ffffff'];
  var DEFAULTS = { bubbleColor: '#a855f7', bubbleSize: 56, bubbleOpacity: 90, panelOpacity: 88, autoSnap: true, tab: 'notes' };
  var MAX_TIMER = 99 * 60 * 1000 + 59000;

  /* ---------------- Native bridge (dengan cadangan localStorage untuk uji di browser) ---------------- */
  var Native = {
    has: typeof window.NexusBridge !== 'undefined',
    load: function (k) {
      try { return this.has ? (window.NexusBridge.load(k) || '') : (localStorage.getItem('nx_' + k) || ''); }
      catch (e) { return ''; }
    },
    save: function (k, v) {
      try { if (this.has) window.NexusBridge.save(k, v); else localStorage.setItem('nx_' + k, v); }
      catch (e) { /* abaikan */ }
    },
    close: function () { try { if (this.has) window.NexusBridge.close(); } catch (e) {} },
    stop: function () { try { if (this.has) window.NexusBridge.stopService(); } catch (e) {} },
    input: function (on) { try { if (this.has) window.NexusBridge.inputMode(on); } catch (e) {} },
    info: function () { try { return this.has ? JSON.parse(window.NexusBridge.info()) : null; } catch (e) { return null; } }
  };

  function parse(s, fb) {
    try { if (!s) return fb; var v = JSON.parse(s); return (v && typeof v === 'object') ? v : fb; }
    catch (e) { return fb; }
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function clampNum(v, lo, hi, d) { v = Number(v); if (!isFinite(v)) return d; return Math.min(hi, Math.max(lo, v)); }

  /* ---------------- State ---------------- */
  var settings = Object.assign({}, DEFAULTS, parse(Native.load('settings'), {}));
  settings.bubbleSize = clampNum(settings.bubbleSize, 40, 80, 56);
  settings.bubbleOpacity = clampNum(settings.bubbleOpacity, 30, 100, 90);
  settings.panelOpacity = clampNum(settings.panelOpacity, 50, 100, 88);
  if (!/^#[0-9a-fA-F]{6}$/.test(settings.bubbleColor)) settings.bubbleColor = DEFAULTS.bubbleColor;
  settings.autoSnap = settings.autoSnap !== false;

  var notes = parse(Native.load('notes'), []);
  if (!Array.isArray(notes)) notes = [];
  notes = notes.filter(function (n) { return n && typeof n.id === 'string'; });

  function normClock(c) {
    var sw = (c && c.sw) || {}, tm = (c && c.tm) || {};
    var total = clampNum(tm.total, 1000, MAX_TIMER, 300000);
    return {
      sw: { running: !!sw.running, startAt: clampNum(sw.startAt, 0, 1e15, 0), acc: clampNum(sw.acc, 0, 1e12, 0) },
      tm: { running: !!tm.running, endAt: clampNum(tm.endAt, 0, 1e15, 0), total: total,
            remain: clampNum(tm.remain, 0, MAX_TIMER, total), done: !!tm.done }
    };
  }
  var clock = normClock(parse(Native.load('clock'), {}));

  var currentTab = 'notes';
  var timeMode = 'sw';

  /* ---------------- Settings ---------------- */
  var sTimer = 0;
  function saveSettings(now) {
    clearTimeout(sTimer);
    var go = function () { Native.save('settings', JSON.stringify(settings)); };
    if (now) go(); else sTimer = setTimeout(go, 150);
  }
  function applyTheme() {
    var h = settings.bubbleColor;
    var r = parseInt(h.slice(1, 3), 16), g = parseInt(h.slice(3, 5), 16), b = parseInt(h.slice(5, 7), 16);
    var s = document.documentElement.style;
    s.setProperty('--accent', h);
    s.setProperty('--rgb', r + ',' + g + ',' + b);
    s.setProperty('--pa', (settings.panelOpacity / 100).toFixed(2));
  }

  /* ---------------- Tabs ---------------- */
  function showTab(name) {
    if (currentTab === 'notes' && name !== 'notes') leaveEditor(true);
    currentTab = name;
    ['notes', 'time', 'calc', 'settings'].forEach(function (t) { $('tab-' + t).hidden = (t !== name); });
    Array.prototype.forEach.call($('tabs').children, function (b) {
      b.classList.toggle('on', b.getAttribute('data-tab') === name);
    });
    settings.tab = name;
    saveSettings(false);
    if (name === 'time') renderTime(Date.now());
    schedule();
  }

  /* ---------------- Catatan ---------------- */
  var filter = 'Semua', editingId = null, noteTimer = 0, noteDirty = false;

  function ago(ts) {
    var m = Math.floor((Date.now() - ts) / 60000);
    if (m < 1) return 'baru saja';
    if (m < 60) return m + ' mnt lalu';
    var h = Math.floor(m / 60);
    if (h < 24) return h + ' j lalu';
    return Math.floor(h / 24) + ' hr lalu';
  }
  function persistNotes() {
    Native.save('notes', JSON.stringify(notes));
    noteDirty = false;
    $('noteSaved').textContent = 'Tersimpan';
  }
  function renderFilter() {
    var box = $('noteFilter'); box.textContent = '';
    ['Semua'].concat(TAGS).forEach(function (t) {
      var b = el('button', 'chip' + (t === filter ? ' on' : ''), t);
      b.type = 'button'; b.setAttribute('data-f', t); box.appendChild(b);
    });
  }
  function renderNotes() {
    renderFilter();
    var ul = $('noteItems'); ul.textContent = '';
    var list = notes.filter(function (n) { return filter === 'Semua' || n.tag === filter; })
      .sort(function (a, b) { return (b.updated || 0) - (a.updated || 0); });
    $('noteEmpty').hidden = list.length > 0;
    list.forEach(function (n) {
      var li = el('li', 'item');
      li.setAttribute('data-id', n.id);
      li.appendChild(el('div', 'item-title', n.title || 'Tanpa judul'));
      li.appendChild(el('div', 'item-meta', (n.tag || 'Lainnya') + ' · ' + ago(n.updated || 0)));
      li.appendChild(el('div', 'item-snip', String(n.body || '').slice(0, 80).replace(/\s+/g, ' ')));
      ul.appendChild(li);
    });
  }
  function renderTags(active) {
    var box = $('noteTags'); box.textContent = '';
    TAGS.forEach(function (t) {
      var b = el('button', 'chip' + (t === active ? ' on' : ''), t);
      b.type = 'button'; b.setAttribute('data-t', t); box.appendChild(b);
    });
  }
  function findNote(id) { for (var i = 0; i < notes.length; i++) if (notes[i].id === id) return notes[i]; return null; }
  function openNote(id) {
    var n = findNote(id); if (!n) return;
    editingId = id;
    $('noteTitle').value = n.title || '';
    $('noteBody').value = n.body || '';
    renderTags(n.tag);
    $('noteSaved').textContent = 'Tersimpan';
    $('notesList').hidden = true;
    $('noteEditor').hidden = false;
  }
  function flushNote() {
    clearTimeout(noteTimer);
    if (noteDirty) persistNotes();
  }
  function leaveEditor(silent) {
    if (editingId == null) return;
    flushNote();
    var n = findNote(editingId);
    if (n && !n.title && !n.body) {            // catatan kosong tidak disimpan
      notes = notes.filter(function (x) { return x.id !== editingId; });
      persistNotes();
    }
    editingId = null;
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    $('noteEditor').hidden = true;
    $('notesList').hidden = false;
    if (!silent) renderNotes(); else renderNotes();
  }
  function touchNote() {
    var n = findNote(editingId); if (!n) return;
    n.title = $('noteTitle').value;
    n.body = $('noteBody').value;
    n.updated = Date.now();
    noteDirty = true;
    $('noteSaved').textContent = 'Menyimpan…';
    clearTimeout(noteTimer);
    noteTimer = setTimeout(persistNotes, 400);
  }
  var delArmed = 0;
  function onDelete() {
    var b = $('noteDelete');
    if (!delArmed) {
      b.textContent = 'Yakin?';
      delArmed = setTimeout(function () { delArmed = 0; b.textContent = 'Hapus'; }, 3000);
      return;
    }
    clearTimeout(delArmed); delArmed = 0; b.textContent = 'Hapus';
    notes = notes.filter(function (x) { return x.id !== editingId; });
    noteDirty = true; persistNotes();
    editingId = null;
    $('noteEditor').hidden = true; $('notesList').hidden = false;
    renderNotes();
  }
  function newNote() {
    if (notes.length >= 200) { $('noteEmpty').hidden = false; $('noteEmpty').textContent = 'Batas 200 catatan tercapai.'; return; }
    var n = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), title: '', body: '',
              tag: TAGS.indexOf(filter) >= 0 ? filter : 'Hero', updated: Date.now() };
    notes.unshift(n);
    openNote(n.id);
    $('noteTitle').focus();
  }

  /* ---------------- Stopwatch & Timer (berbasis timestamp, bukan hitungan animasi) ---------------- */
  var p2 = function (n) { return String(n).padStart(2, '0'); };
  function fmtSw(ms) {
    var t = Math.floor(ms / 100), tenth = t % 10, s = Math.floor(t / 10) % 60, m = Math.floor(t / 600) % 60, h = Math.floor(t / 36000);
    return (h ? h + ':' + p2(m) : p2(m)) + ':' + p2(s) + '.' + tenth;
  }
  function fmtTm(ms) {
    var t = Math.ceil(ms / 1000), s = t % 60, m = Math.floor(t / 60) % 60, h = Math.floor(t / 3600);
    return (h ? h + ':' : '') + p2(m) + ':' + p2(s);
  }
  function saveClock() { Native.save('clock', JSON.stringify(clock)); }
  function swElapsed(now) { return clock.sw.acc + (clock.sw.running ? Math.max(0, now - clock.sw.startAt) : 0); }
  function tmRemain(now) { return clock.tm.running ? Math.max(0, clock.tm.endAt - now) : clock.tm.remain; }

  function checkTimerDone(now) {
    if (clock.tm.running && now >= clock.tm.endAt) {
      clock.tm.running = false; clock.tm.remain = 0; clock.tm.done = true;
      saveClock();
    }
  }
  function renderTime(now) {
    if (currentTab !== 'time') return;
    if (timeMode === 'sw') {
      $('swDisplay').textContent = fmtSw(swElapsed(now));
      $('swToggle').textContent = clock.sw.running ? 'Jeda' : (clock.sw.acc > 0 ? 'Lanjut' : 'Mulai');
    } else {
      $('tmDisplay').textContent = fmtTm(tmRemain(now));
      $('tmToggle').textContent = clock.tm.running ? 'Jeda' : 'Mulai';
      $('tmDone').hidden = !clock.tm.done;
      var lock = clock.tm.running;
      Array.prototype.forEach.call(document.querySelectorAll('#tmPresets .chip,#tmAdjust .chip'), function (c) { c.disabled = lock; });
    }
  }
  function setMode(m) {
    timeMode = m;
    $('swView').hidden = (m !== 'sw'); $('tmView').hidden = (m !== 'tm');
    Array.prototype.forEach.call($('timeSeg').children, function (b) { b.classList.toggle('on', b.getAttribute('data-mode') === m); });
    renderTime(Date.now()); schedule();
  }

  var tick = 0;
  function schedule() {                       // selalu tepat satu timeout aktif, berhenti bila tak ada yang berjalan
    clearTimeout(tick); tick = 0;
    if (!clock.sw.running && !clock.tm.running) return;
    tick = setTimeout(onTick, currentTab === 'time' ? 100 : 1000);
  }
  function onTick() {
    tick = 0;
    var now = Date.now();
    checkTimerDone(now);
    renderTime(now);
    schedule();
  }

  function swToggle() {
    var now = Date.now(), s = clock.sw;
    if (s.running) { s.acc += Math.max(0, now - s.startAt); s.running = false; }
    else { s.startAt = now; s.running = true; }
    saveClock(); renderTime(now); schedule();
  }
  function swReset() { clock.sw = { running: false, startAt: 0, acc: 0 }; saveClock(); renderTime(Date.now()); schedule(); }
  function tmToggle() {
    var now = Date.now(), t = clock.tm;
    if (t.running) { t.remain = Math.max(0, t.endAt - now); t.running = false; }
    else {
      if (t.remain <= 0) t.remain = t.total;
      t.endAt = now + Math.round(t.remain); t.running = true; t.done = false;
    }
    saveClock(); renderTime(now); schedule();
  }
  function tmReset() { var t = clock.tm; t.running = false; t.done = false; t.remain = t.total; saveClock(); renderTime(Date.now()); schedule(); }
  function tmSet(ms) { var t = clock.tm; if (t.running) return; t.total = t.remain = clampNum(ms, 1000, MAX_TIMER, 300000); t.done = false; saveClock(); renderTime(Date.now()); }
  function tmAdd(d) { var t = clock.tm; if (t.running) return; tmSet((t.done ? t.total : t.remain) + d); }

  /* ---------------- Kalkulator ---------------- */
  var calc;
  function calcReset() { calc = { cur: '0', acc: null, op: null, fresh: true, expr: '' }; }
  function fmtNum(n) {
    if (!isFinite(n)) return 'Error';
    var r = parseFloat(n.toPrecision(12)), s = String(r);
    if (s.length > 14) s = r.toExponential(6);
    return s;
  }
  function compute(a, op, b) {
    switch (op) {
      case '+': return a + b;
      case '−': return a - b;
      case '×': return a * b;
      case '÷': return b === 0 ? NaN : a / b;
    }
    return b;
  }
  function calcRender() {
    $('calcExpr').textContent = calc.expr || '\u00a0';
    var o = $('calcOut'); o.textContent = calc.cur;
    o.classList.toggle('long', calc.cur.length > 10);
  }
  function calcPress(k) {
    if (calc.cur === 'Error' && k !== 'C') calcReset();
    if (/^[0-9]$/.test(k)) {
      if (calc.fresh) { calc.cur = k; calc.fresh = false; }
      else if (calc.cur === '0') calc.cur = k;
      else if (calc.cur === '-0') calc.cur = '-' + k;
      else if (calc.cur.replace(/[-.]/g, '').length < 12) calc.cur += k;
    } else if (k === '.') {
      if (calc.fresh) { calc.cur = '0.'; calc.fresh = false; }
      else if (calc.cur.indexOf('.') < 0) calc.cur += '.';
    } else if (k === 'C') {
      calcReset();
    } else if (k === '⌫') {
      if (calc.fresh) calc.cur = '0';
      else { calc.cur = calc.cur.slice(0, -1); if (calc.cur === '' || calc.cur === '-') calc.cur = '0'; }
    } else if (k === '±') {
      if (calc.cur !== '0') calc.cur = calc.cur.charAt(0) === '-' ? calc.cur.slice(1) : '-' + calc.cur;
    } else if (k === '%') {
      calc.cur = fmtNum(parseFloat(calc.cur) / 100); calc.fresh = true;
    } else if ('÷×−+'.indexOf(k) >= 0) {
      if (calc.op && !calc.fresh) {
        var r = compute(calc.acc, calc.op, parseFloat(calc.cur));
        if (!isFinite(r)) { calcReset(); calc.cur = 'Error'; calcRender(); return; }
        calc.acc = r; calc.cur = fmtNum(r);
      } else if (!calc.op) {
        calc.acc = parseFloat(calc.cur);
      }
      calc.op = k; calc.fresh = true; calc.expr = fmtNum(calc.acc) + ' ' + k;
    } else if (k === '=') {
      if (calc.op) {
        var b = parseFloat(calc.cur), res = compute(calc.acc, calc.op, b);
        calc.expr = fmtNum(calc.acc) + ' ' + calc.op + ' ' + fmtNum(b) + ' =';
        calc.cur = fmtNum(res); calc.acc = null; calc.op = null; calc.fresh = true;
      }
    }
    calcRender();
  }
  function buildCalc() {
    var layout = [['C', 'fn'], ['⌫', 'fn'], ['%', 'fn'], ['÷', 'op'],
                  ['7'], ['8'], ['9'], ['×', 'op'],
                  ['4'], ['5'], ['6'], ['−', 'op'],
                  ['1'], ['2'], ['3'], ['+', 'op'],
                  ['±', 'fn'], ['0'], ['.'], ['=', 'eq']];
    var box = $('calcKeys');
    layout.forEach(function (k) {
      var b = el('button', k[1] || '', k[0]);
      b.type = 'button'; b.setAttribute('data-k', k[0]); box.appendChild(b);
    });
  }

  /* ---------------- Pengaturan UI ---------------- */
  function renderSettings() {
    var sw = $('swatches'); sw.textContent = '';
    COLORS.forEach(function (c) {
      var b = el('button', 'sw' + (c === settings.bubbleColor ? ' on' : ''));
      b.type = 'button'; b.style.background = c; b.style.color = c;
      b.setAttribute('data-c', c); b.setAttribute('aria-label', 'Warna ' + c);
      sw.appendChild(b);
    });
    $('rSize').value = settings.bubbleSize; $('vSize').textContent = settings.bubbleSize + ' dp';
    $('rBOp').value = settings.bubbleOpacity; $('vBOp').textContent = settings.bubbleOpacity + '%';
    $('rPOp').value = settings.panelOpacity; $('vPOp').textContent = settings.panelOpacity + '%';
    $('tSnap').setAttribute('aria-checked', String(settings.autoSnap));
  }
  function bindRange(id, key) {
    var r = $(id);
    r.addEventListener('input', function () {
      settings[key] = Number(r.value); renderValues(); applyTheme(); saveSettings(false);
    });
    r.addEventListener('change', function () { saveSettings(true); });
  }
  function renderValues() {
    $('vSize').textContent = settings.bubbleSize + ' dp';
    $('vBOp').textContent = settings.bubbleOpacity + '%';
    $('vPOp').textContent = settings.panelOpacity + '%';
  }

  /* ---------------- Menutup panel ---------------- */
  var closing = false;
  function requestClose() {
    if (closing) return;
    closing = true;
    flushNote();
    saveSettings(true);
    $('app').classList.add('closing');
    setTimeout(function () { Native.close(); }, 150);
  }
  window.NexusUI = { requestClose: requestClose };

  /* ---------------- Mode input (keyboard) ---------------- */
  function isText(e) { return !!e && (e.tagName === 'TEXTAREA' || (e.tagName === 'INPUT' && (e.type === 'text' || e.type === 'search'))); }
  var inputOn = false, inputT = 0;
  document.addEventListener('focusin', function (e) {
    if (!isText(e.target)) return;
    clearTimeout(inputT);
    if (!inputOn) {
      inputOn = true; Native.input(true);
      var t = e.target;
      setTimeout(function () { if (inputOn && document.activeElement !== t) t.focus(); }, 180);
    }
  });
  document.addEventListener('focusout', function () {
    clearTimeout(inputT);
    inputT = setTimeout(function () {
      if (inputOn && !isText(document.activeElement)) { inputOn = false; Native.input(false); }
    }, 150);
  });

  /* ---------------- Init (semua listener dipasang sekali) ---------------- */
  function init() {
    applyTheme();
    buildCalc(); calcReset(); calcRender();
    renderSettings(); renderNotes();

    $('btnClose').addEventListener('click', requestClose);
    $('tabs').addEventListener('click', function (e) {
      var b = e.target.closest('[data-tab]'); if (b) showTab(b.getAttribute('data-tab'));
    });

    // catatan
    $('noteNew').addEventListener('click', newNote);
    $('noteBack').addEventListener('click', function () { leaveEditor(false); });
    $('noteDelete').addEventListener('click', onDelete);
    $('noteTitle').addEventListener('input', touchNote);
    $('noteBody').addEventListener('input', touchNote);
    $('noteFilter').addEventListener('click', function (e) {
      var b = e.target.closest('[data-f]'); if (b) { filter = b.getAttribute('data-f'); renderNotes(); }
    });
    $('noteItems').addEventListener('click', function (e) {
      var li = e.target.closest('[data-id]'); if (li) openNote(li.getAttribute('data-id'));
    });
    $('noteTags').addEventListener('click', function (e) {
      var b = e.target.closest('[data-t]'); if (!b) return;
      var n = findNote(editingId); if (!n) return;
      n.tag = b.getAttribute('data-t'); n.updated = Date.now(); noteDirty = true;
      renderTags(n.tag); persistNotes();
    });

    // waktu
    $('timeSeg').addEventListener('click', function (e) {
      var b = e.target.closest('[data-mode]'); if (b) setMode(b.getAttribute('data-mode'));
    });
    $('swToggle').addEventListener('click', swToggle);
    $('swReset').addEventListener('click', swReset);
    $('tmToggle').addEventListener('click', tmToggle);
    $('tmReset').addEventListener('click', tmReset);
    $('tmPresets').addEventListener('click', function (e) {
      var b = e.target.closest('[data-set]'); if (b) tmSet(Number(b.getAttribute('data-set')));
    });
    $('tmAdjust').addEventListener('click', function (e) {
      var b = e.target.closest('[data-add]'); if (b) tmAdd(Number(b.getAttribute('data-add')));
    });

    // kalkulator
    $('calcKeys').addEventListener('click', function (e) {
      var b = e.target.closest('[data-k]'); if (b) calcPress(b.getAttribute('data-k'));
    });

    // pengaturan
    $('swatches').addEventListener('click', function (e) {
      var b = e.target.closest('[data-c]'); if (!b) return;
      settings.bubbleColor = b.getAttribute('data-c'); applyTheme(); renderSettings(); saveSettings(true);
    });
    bindRange('rSize', 'bubbleSize');
    bindRange('rBOp', 'bubbleOpacity');
    bindRange('rPOp', 'panelOpacity');
    $('tSnap').addEventListener('click', function () {
      settings.autoSnap = !settings.autoSnap; renderSettings(); saveSettings(true);
    });
    $('btnStop').addEventListener('click', function () { saveSettings(true); Native.stop(); });

    var info = Native.info();
    $('about').textContent = 'NEXUS FLOAT hanya overlay alat bantu' +
      (info ? ' (Android ' + info.release + ', API ' + info.sdk + ')' : '') +
      '. Tidak membaca FPS, ping, suhu, atau data game, dan tidak mengotomatisasi game.';

    // pulihkan keadaan timer yang mungkin habis saat panel tertutup
    checkTimerDone(Date.now());
    var startTab = ['notes', 'time', 'calc', 'settings'].indexOf(settings.tab) >= 0 ? settings.tab : 'notes';
    showTab(startTab);
    if (clock.tm.running || clock.tm.done) setMode(clock.sw.running && !clock.tm.running ? 'sw' : 'tm');
    schedule();
  }

  window.addEventListener('pagehide', function () { clearTimeout(tick); tick = 0; flushNote(); });
  init();
})();
