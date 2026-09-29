// ==UserScript==
// @name         Xbox Cloud Touch-Controller
// @namespace    mein-node-server
// @version      1.1.0
// @description  Blendet auf xbox.com/play ein Touch-Gamepad ein und meldet es dem Browser als echten Xbox-Controller.
// @match        https://www.xbox.com/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(function (wurzel) {
  'use strict';

  // Standard-Mapping der Gamepad-API (https://w3c.github.io/gamepad/#remapping)
  const TASTE = {
    A: 0, B: 1, X: 2, Y: 3,
    LB: 4, RB: 5, LT: 6, RT: 7,
    ZURUECK: 8, START: 9, L3: 10, R3: 11,
    HOCH: 12, RUNTER: 13, LINKS: 14, RECHTS: 15,
    XBOX: 16,
  };
  const ACHSE = { LX: 0, LY: 1, RX: 2, RY: 3 };
  const PAD_ID = 'Xbox 360 Controller (STANDARD GAMEPAD Vendor: 045e Product: 028e)';

  // Ein virtuelles Gamepad: haelt Tasten und Achsen, liefert Momentaufnahmen.
  function erstellePad() {
    const tasten = Array.from({ length: 17 }, () => ({ pressed: false, touched: false, value: 0 }));
    const achsen = [0, 0, 0, 0];
    return {
      taste(nr, gedrueckt) {
        const t = tasten[nr];
        t.pressed = gedrueckt;
        t.touched = gedrueckt;
        t.value = gedrueckt ? 1 : 0;
      },
      achse(nr, wert) {
        achsen[nr] = wert;
      },
      zuruecksetzen() {
        tasten.forEach((_, i) => this.taste(i, false));
        achsen.fill(0);
      },
      schnappschuss(index) {
        return {
          id: PAD_ID,
          index,
          connected: true,
          mapping: 'standard',
          axes: achsen.slice(),
          buttons: tasten.map((t) => ({ pressed: t.pressed, touched: t.touched, value: t.value })),
          timestamp: performance.now(),
          vibrationActuator: null,
          hapticActuators: [],
        };
      },
    };
  }

  // Wandelt die Fingerposition relativ zur Stickmitte in Achsenwerte (-1..1) um.
  // Innerhalb der Totzone kommt 0 an, danach steigt der Wert stufenlos bis 1.
  function stickVektor(dx, dy, radius, totzone) {
    const abstand = Math.hypot(dx, dy);
    if (abstand === 0) return { x: 0, y: 0 };
    const norm = Math.min(abstand / radius, 1);
    if (norm < totzone) return { x: 0, y: 0 };
    const skaliert = (norm - totzone) / (1 - totzone);
    return { x: (dx / abstand) * skaliert, y: (dy / abstand) * skaliert };
  }

  const api = { TASTE, ACHSE, PAD_ID, erstellePad, stickVektor };

  // Im Test (Node) nur die Logik bereitstellen, nichts am Browser veraendern.
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
    return;
  }

  starte(wurzel);

  function starte(fenster) {
    const pad = erstellePad();
    const einstellungen = ladeEinstellungen();
    let sichtbar = einstellungen.sichtbar;
    let angemeldet = null; // Slot, unter dem das virtuelle Pad gemeldet wurde

    // --- Gamepad-API ueberschreiben --------------------------------------
    const echteFunktion = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : () => [];
    navigator.getGamepads = function () {
      const liste = Array.from(echteFunktion() || []);
      if (!sichtbar) return liste;
      let slot = liste.findIndex((g) => !g);
      if (slot === -1) slot = liste.length;
      liste[slot] = pad.schnappschuss(slot);
      return liste;
    };

    function meldeGamepad(art, schnappschuss) {
      const ereignis = new Event(art);
      Object.defineProperty(ereignis, 'gamepad', { value: schnappschuss });
      fenster.dispatchEvent(ereignis);
    }

    // Die Seite haengt ihren Listener oft erst nach dem Start an. Solche
    // Listener bekommen das bereits erfolgte Verbinden nachgeliefert.
    const echtesAddListener = fenster.addEventListener;
    fenster.addEventListener = function (art, listener, ...rest) {
      echtesAddListener.call(this, art, listener, ...rest);
      if (art === 'gamepadconnected' && angemeldet !== null && listener) {
        setTimeout(() => {
          const gp = navigator.getGamepads().find((g) => g && g.id === PAD_ID);
          if (!gp) return;
          const ereignis = new Event('gamepadconnected');
          Object.defineProperty(ereignis, 'gamepad', { value: gp });
          if (typeof listener === 'function') listener.call(fenster, ereignis);
          else if (listener.handleEvent) listener.handleEvent(ereignis);
        }, 0);
      }
    };

    function synchronisiereAnmeldung() {
      if (sichtbar && angemeldet === null) {
        const gp = navigator.getGamepads().find((g) => g && g.id === PAD_ID);
        if (gp) {
          angemeldet = gp.index;
          meldeGamepad('gamepadconnected', gp);
        }
      } else if (!sichtbar && angemeldet !== null) {
        pad.zuruecksetzen();
        const gp = pad.schnappschuss(angemeldet);
        gp.connected = false;
        meldeGamepad('gamepaddisconnected', gp);
        angemeldet = null;
      }
    }

    // --- Oberflaeche -------------------------------------------------------
    const CSS = `
      :host { all: initial; }
      .wurzel {
        position: fixed; inset: 0; z-index: 2147483647; pointer-events: none;
        --s: 1; --o: ${einstellungen.deckkraft};
        font: 600 calc(var(--s) * 14px) system-ui, sans-serif; color: #fff;
        -webkit-user-select: none; user-select: none; -webkit-touch-callout: none;
      }
      .wurzel.aus .steuerung { display: none; }
      .steuerung { position: absolute; inset: 0; opacity: var(--o); }
      .ctl, .kn, .zone, .menue-knopf, .panel { touch-action: none; pointer-events: auto; }
      .ctl {
        position: absolute; display: flex; align-items: center; justify-content: center;
        border-radius: 50%; background: rgba(30, 30, 30, .65); border: 2px solid rgba(255, 255, 255, .55);
        width: calc(var(--s) * 58px); height: calc(var(--s) * 58px);
      }
      .ctl.an { background: rgba(255, 255, 255, .55); color: #000; }
      .schulter { border-radius: calc(var(--s) * 14px); width: calc(var(--s) * 84px); height: calc(var(--s) * 46px); }
      .klein { width: calc(var(--s) * 44px); height: calc(var(--s) * 32px); border-radius: calc(var(--s) * 16px); font-size: calc(var(--s) * 11px); }
      .A { background-color: rgba(40, 160, 60, .7); } .B { background-color: rgba(200, 40, 40, .7); }
      .X { background-color: rgba(40, 90, 200, .7); } .Y { background-color: rgba(210, 170, 20, .7); }
      .zone {
        position: absolute; width: calc(var(--s) * 140px); height: calc(var(--s) * 140px);
        border-radius: 50%; background: rgba(30, 30, 30, .45); border: 2px solid rgba(255, 255, 255, .45);
      }
      .kn {
        position: absolute; left: 50%; top: 50%; width: 46%; height: 46%; margin: -23% 0 0 -23%;
        border-radius: 50%; background: rgba(255, 255, 255, .6); pointer-events: none;
      }
      .menue-knopf {
        position: absolute; top: calc(var(--s) * 46px); left: 50%; margin-left: calc(var(--s) * -20px);
        width: calc(var(--s) * 40px); height: calc(var(--s) * 40px); border-radius: 50%;
        background: rgba(0, 0, 0, .55); border: 2px solid rgba(255, 255, 255, .6);
        display: flex; align-items: center; justify-content: center; opacity: .8;
      }
      .panel {
        position: absolute; top: calc(var(--s) * 90px); left: 50%; transform: translateX(-50%);
        background: rgba(20, 20, 20, .92); border: 1px solid rgba(255, 255, 255, .4); border-radius: 12px;
        padding: 12px 16px; display: none; flex-direction: column; gap: 10px; font-size: 14px; min-width: 220px;
      }
      .panel.offen { display: flex; }
      .panel label { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
      .panel input[type=range] { width: 120px; }
    `;

    const HTML = `
      <div class="wurzel">
        <div class="steuerung">
          <div class="ctl schulter" data-taste="LT" style="left:3%;top:calc(var(--s)*8px)">LT</div>
          <div class="ctl schulter" data-taste="LB" style="left:3%;top:calc(var(--s)*62px)">LB</div>
          <div class="ctl schulter" data-taste="RT" style="right:3%;top:calc(var(--s)*8px)">RT</div>
          <div class="ctl schulter" data-taste="RB" style="right:3%;top:calc(var(--s)*62px)">RB</div>

          <div class="zone" data-stick="L" style="left:3%;bottom:calc(var(--s)*14px)"><div class="kn"></div></div>
          <div class="ctl klein" data-taste="L3" style="left:calc(3% + var(--s)*48px);bottom:calc(var(--s)*160px)">L3</div>

          <div class="ctl" data-taste="HOCH" style="left:calc(3% + var(--s)*196px);bottom:calc(var(--s)*92px)">▲</div>
          <div class="ctl" data-taste="LINKS" style="left:calc(3% + var(--s)*158px);bottom:calc(var(--s)*46px)">◀</div>
          <div class="ctl" data-taste="RECHTS" style="left:calc(3% + var(--s)*234px);bottom:calc(var(--s)*46px)">▶</div>
          <div class="ctl" data-taste="RUNTER" style="left:calc(3% + var(--s)*196px);bottom:calc(var(--s)*4px)">▼</div>

          <div class="ctl klein" data-taste="ZURUECK" style="left:calc(50% - var(--s)*74px);top:calc(var(--s)*8px)">Sicht</div>
          <div class="ctl klein" data-taste="XBOX" style="left:calc(50% - var(--s)*22px);top:calc(var(--s)*8px)">Xbox</div>
          <div class="ctl klein" data-taste="START" style="left:calc(50% + var(--s)*30px);top:calc(var(--s)*8px)">Menü</div>

          <div class="zone" data-stick="R" style="right:calc(3% + var(--s)*200px);bottom:calc(var(--s)*14px)"><div class="kn"></div></div>
          <div class="ctl klein" data-taste="R3" style="right:calc(3% + var(--s)*248px);bottom:calc(var(--s)*160px)">R3</div>

          <div class="ctl Y" data-taste="Y" style="right:calc(3% + var(--s)*66px);bottom:calc(var(--s)*128px)">Y</div>
          <div class="ctl X" data-taste="X" style="right:calc(3% + var(--s)*132px);bottom:calc(var(--s)*72px)">X</div>
          <div class="ctl B" data-taste="B" style="right:3%;bottom:calc(var(--s)*72px)">B</div>
          <div class="ctl A" data-taste="A" style="right:calc(3% + var(--s)*66px);bottom:calc(var(--s)*16px)">A</div>
        </div>
        <div class="menue-knopf" title="Touch-Controller">🎮</div>
        <div class="panel">
          <label>Größe <input type="range" data-einst="groesse" min="0.6" max="1.6" step="0.05"></label>
          <label>Deckkraft <input type="range" data-einst="deckkraft" min="0.2" max="1" step="0.05"></label>
          <label>Vibration <input type="checkbox" data-einst="vibration"></label>
          <label>Controller <input type="checkbox" data-einst="sichtbar"></label>
        </div>
      </div>
    `;

    const wirt = document.createElement('div');
    const schatten = wirt.attachShadow({ mode: 'open' });
    schatten.innerHTML = `<style>${CSS}</style>${HTML}`;

    // Beruehrungen gehoeren dem Controller, nicht der Xbox-Seite darunter.
    ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'touchstart', 'touchmove', 'touchend', 'click', 'contextmenu']
      .forEach((name) => wirt.addEventListener(name, (e) => {
        e.stopPropagation();
        if (name.startsWith('touch') || name === 'contextmenu') e.preventDefault();
      }, { passive: false }));

    const wurzelEl = schatten.querySelector('.wurzel');

    function haptik(ms) {
      if (einstellungen.vibration && navigator.vibrate) navigator.vibrate(ms);
    }

    // Tasten
    schatten.querySelectorAll('[data-taste]').forEach((el) => {
      const nr = TASTE[el.dataset.taste];
      const los = (e) => {
        if (!el.classList.contains('an')) return;
        el.classList.remove('an');
        pad.taste(nr, false);
      };
      el.addEventListener('pointerdown', (e) => {
        el.setPointerCapture(e.pointerId);
        el.classList.add('an');
        pad.taste(nr, true);
        haptik(12);
      });
      el.addEventListener('pointerup', los);
      el.addEventListener('pointercancel', los);
      el.addEventListener('lostpointercapture', los);
    });

    // Analogsticks
    schatten.querySelectorAll('[data-stick]').forEach((zone) => {
      const knauf = zone.querySelector('.kn');
      const [achseX, achseY] = zone.dataset.stick === 'L' ? [ACHSE.LX, ACHSE.LY] : [ACHSE.RX, ACHSE.RY];
      let aktiv = null;
      let mitte = null;
      const setze = (e) => {
        const radius = mitte.breite / 2;
        const v = stickVektor(e.clientX - mitte.x, e.clientY - mitte.y, radius, 0.08);
        pad.achse(achseX, v.x);
        pad.achse(achseY, v.y);
        knauf.style.transform = `translate(${v.x * radius * 0.5}px, ${v.y * radius * 0.5}px)`;
      };
      const ende = (e) => {
        if (aktiv !== e.pointerId) return;
        aktiv = null;
        pad.achse(achseX, 0);
        pad.achse(achseY, 0);
        knauf.style.transform = '';
      };
      zone.addEventListener('pointerdown', (e) => {
        if (aktiv !== null) return;
        aktiv = e.pointerId;
        zone.setPointerCapture(e.pointerId);
        const r = zone.getBoundingClientRect();
        mitte = { x: r.left + r.width / 2, y: r.top + r.height / 2, breite: r.width };
        setze(e);
      });
      zone.addEventListener('pointermove', (e) => { if (aktiv === e.pointerId) setze(e); });
      zone.addEventListener('pointerup', ende);
      zone.addEventListener('pointercancel', ende);
      zone.addEventListener('lostpointercapture', ende);
    });

    // Einstellungen
    const panel = schatten.querySelector('.panel');
    schatten.querySelector('.menue-knopf').addEventListener('click', () => panel.classList.toggle('offen'));
    schatten.querySelectorAll('[data-einst]').forEach((el) => {
      const name = el.dataset.einst;
      if (el.type === 'checkbox') el.checked = einstellungen[name];
      else el.value = einstellungen[name];
      el.addEventListener('input', () => {
        einstellungen[name] = el.type === 'checkbox' ? el.checked : Number(el.value);
        wende();
        speichereEinstellungen(einstellungen);
      });
    });

    // Entworfen fuer 380 px Hoehe; auf kuerzeren Bildschirmen (Querformat mit
    // Adressleiste) wird automatisch verkleinert, damit nichts ueberlappt.
    function skalierung() {
      const auto = Math.min(1, window.innerHeight / 380);
      return Math.round(einstellungen.groesse * auto * 100) / 100;
    }

    function wende() {
      wurzelEl.style.setProperty('--s', skalierung());
      wurzelEl.style.setProperty('--o', einstellungen.deckkraft);
      sichtbar = einstellungen.sichtbar;
      wurzelEl.classList.toggle('aus', !sichtbar);
      if (!sichtbar) pad.zuruecksetzen();
      synchronisiereAnmeldung();
    }

    // Im Vollbild ist nur das Vollbild-Element (und was darin liegt) zu sehen.
    function einhaengen() {
      const vollbild = document.fullscreenElement || document.webkitFullscreenElement;
      const ziel = vollbild && !/^(VIDEO|IFRAME|CANVAS)$/.test(vollbild.tagName) ? vollbild : document.body;
      if (wirt.parentNode !== ziel) ziel.appendChild(wirt);
    }

    function fertig() {
      einhaengen();
      wende();
    }
    window.addEventListener('resize', () => wurzelEl.style.setProperty('--s', skalierung()));
    document.addEventListener('fullscreenchange', einhaengen);
    document.addEventListener('webkitfullscreenchange', einhaengen);
    if (document.body) fertig();
    else document.addEventListener('DOMContentLoaded', fertig);
  }

  // --- Einstellungen merken -------------------------------------------------
  function ladeEinstellungen() {
    const standard = { groesse: 1, deckkraft: 0.85, vibration: true, sichtbar: true };
    try {
      return Object.assign(standard, JSON.parse(localStorage.getItem('xcloud-touch') || '{}'));
    } catch (e) {
      return standard;
    }
  }
  function speichereEinstellungen(e) {
    try { localStorage.setItem('xcloud-touch', JSON.stringify(e)); } catch (err) { /* privater Modus */ }
  }
})(typeof window !== 'undefined' ? window : globalThis);
