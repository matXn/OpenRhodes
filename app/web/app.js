/*
 * RhodesLight web preview.
 *
 * Gestures, brightness curve, specular reflection, gradient ink and breath
 * output are ported from the Android sources:
 *   domain/CardLighting.kt  domain/CardPresentation.kt  domain/BreathVisual.kt
 *   domain/Lamp.kt          ui/CardInk.kt               ui/LampCard.kt
 *
 * On top of that skeleton sits my own card design language, 「光体 Lumen」:
 * the card is treated as a lamp, not as a brightness readout.
 *   1. Colour temperature follows luminous flux (cardSurface).
 *   2. Light spills into the dark (#halo, driven by flux * filament).
 *   3. Light has depth (CSS z-planes over the same 3D tilt).
 *   4. Light is touchable (drawTouchLight, blooms under the finger).
 *   5. Light is alive (filament ignition / cool-down, setPower + stepFilament).
 *   6. Light is the scale (#railFill renders live output as a length of light).
 * A static micro-grain pass removes gradient banding from the surface.
 */
(() => {
  'use strict';

  // ---------------------------------------------------------------- domain

  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);
  const lerp = (a, b, t) => a + (b - a) * t;

  const CardLighting = {
    MAX_TILT: 12,
    CAMERA_DISTANCE_DP: 500,
    LIGHT_PERIOD_MS: 48000,
  };

  /** Lamp.kt — Breath.wave */
  function breathWave(breath, phase) {
    const floor = breath.floor / 100;
    const wave = clamp(0.5 + 0.5 * Math.cos(phase), 0, 1);
    return floor + (1 - floor) * Math.pow(wave, breath.curve / 100);
  }

  /** Lamp.kt — LampState.phaseAt */
  function phaseAt(state, now) {
    return state.phase + Math.max(0, now - state.sampledAt) * (2 * Math.PI) / state.breath.periodMs;
  }

  /** BreathVisual.kt — outputPercent */
  function outputPercent(state, now) {
    if (!state.on) return 0;
    if (state.breathing) return clamp(state.brightness * breathWave(state.breath, phaseAt(state, now)), 0, 100);
    return state.brightness;
  }

  /** BreathVisual.kt — cardEnergy */
  function cardEnergy(state, now) {
    if (!state.on) return 0.03;
    const base = Math.pow(state.brightness / 100, 1.3) * 0.85;
    if (!state.breathing) return base;
    const wave = breathWave(state.breath, phaseAt(state, now));
    return clamp(0.14 + base * 0.8 + (wave - 0.5) * 0.26, 0, 1);
  }

  /**
   * Lumen 1 — colour temperature instead of a flat orange.
   * Dim light is deep amber and saturated; bright light desaturates towards
   * incandescent white, the way a real filament behaves.
   */
  function cardSurface(energy, on) {
    const flux = clamp(energy, 0, 1) / 0.85;
    const body = clamp(0.06 + 0.72 * Math.pow(flux, 1.15), 0, 1);
    return {
      hue: 20 + 30 * Math.pow(flux, 0.9),
      saturation: on ? clamp(0.95 - 0.35 * flux, 0, 1) : 0.12,
      body,
      core: clamp(body + 0.1 * flux, 0, 1),
    };
  }

  /** CardLighting.kt — reflection */
  function cardReflection(tiltX, tiltY, lightX, lightY) {
    const ax = tiltX * Math.PI / 180;
    const ay = tiltY * Math.PI / 180;
    const nx = Math.sin(ay) * Math.cos(ax);
    const ny = -Math.sin(ax);
    const nz = Math.cos(ay) * Math.cos(ax);
    const length = Math.sqrt(1 + lightX * lightX + lightY * lightY);
    const lx = lightX / length;
    const ly = lightY / length;
    const lz = 1 / length;
    const dot = nx * lx + ny * ly + nz * lz;
    const reflectedX = 2 * dot * nx - lx;
    const reflectedY = 2 * dot * ny - ly;
    const reflectedZ = 2 * dot * nz - lz;
    const specular = (0.12 + Math.pow(Math.max(reflectedZ, 0), 18)) / (0.12 + Math.pow(lz, 18));
    const strength = clamp(1 + (specular - 1) * 1.6, 0.2, 1.9);
    return {
      shiftX: -(reflectedX + lx) * 1.1,
      shiftY: -(reflectedY + ly) * 0.8,
      angle: 35 + nx * 40 - ny * 25,
      alpha: 0.44 * strength,
    };
  }

  /** ui/CardInk.kt — cardInk */
  function cardInk(energy) {
    const position = clamp((energy / 0.85 - 0.25) / 0.5, 0, 1);
    const mix = position * position * (3 - 2 * position);
    return [
      mixColor([255, 250, 237], [63, 35, 10], mix),
      mixColor([232, 210, 176], [25, 13, 4], mix),
    ];
  }

  function mixColor(a, b, t) {
    return [
      Math.round(lerp(a[0], b[0], t)),
      Math.round(lerp(a[1], b[1], t)),
      Math.round(lerp(a[2], b[2], t)),
    ];
  }

  function rgb(c, alpha) {
    return alpha === undefined
      ? `rgb(${c[0]}, ${c[1]}, ${c[2]})`
      : `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${alpha})`;
  }

  function hsl(h, s, l) {
    s = clamp(s, 0, 1);
    l = clamp(l, 0, 1);
    const c = (1 - Math.abs(2 * l - 1)) * s;
    const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    const m = l - c / 2;
    let r = 0, g = 0, b = 0;
    if (h < 60) [r, g, b] = [c, x, 0];
    else if (h < 120) [r, g, b] = [x, c, 0];
    else if (h < 180) [r, g, b] = [0, c, x];
    else if (h < 240) [r, g, b] = [0, x, c];
    else if (h < 300) [r, g, b] = [x, 0, c];
    else [r, g, b] = [c, 0, x];
    return [
      Math.round((r + m) * 255),
      Math.round((g + m) * 255),
      Math.round((b + m) * 255),
    ];
  }

  /** LampCard.kt — slideBrightness: two full swipes, 60% of travel for the bottom 30%. */
  function slideBrightness(start, travel) {
    const b = start / 100;
    const origin = b <= 0.3 ? b * 2 : 0.6 + (b - 0.3) * 0.4 / 0.7;
    const s = clamp(origin + travel, 0, 1);
    return clamp(Math.round((s <= 0.6 ? s * 0.5 : 0.3 + (s - 0.6) * 0.7 / 0.4) * 100), 0, 100);
  }

  /** domain/CardPresentation.kt */
  class CardPresentation {
    constructor() {
      this.mode = null;
      this.previous = null;
      this.started = 0;
      this.outputOffset = 0;
      this.energyOffset = 0;
    }

    sample(state, now) {
      const output = outputPercent(state, now);
      const energy = cardEnergy(state, now);
      const nextMode = `${state.on}:${state.breathing}`;
      if (this.mode !== nextMode) {
        if (this.previous) {
          this.outputOffset = this.previous.output - output;
          this.energyOffset = this.previous.energy - energy;
          this.started = now;
        }
        this.mode = nextMode;
      }
      const elapsed = Math.max(0, now - this.started);
      const progress = clamp(elapsed / 400, 0, 1);
      const remaining = 1 - progress * progress * (3 - 2 * progress);
      const frame = {
        output: clamp(output + this.outputOffset * remaining, 0, 100),
        energy: clamp(energy + this.energyOffset * remaining, 0, 1),
        transitioning: elapsed < 400 && (this.outputOffset !== 0 || this.energyOffset !== 0),
      };
      this.previous = frame;
      return frame;
    }
  }

  // ---------------------------------------------------------------- state

  const now0 = performance.now();
  const state = {
    on: true,
    brightness: 56,
    breathing: false,
    breath: { periodMs: 6000, floor: 0, curve: 100 },
    phase: 0,
    sampledAt: now0,
  };

  const presentation = new CardPresentation();

  // Lumen 5 — filament temperature. Ignition is quick, cooling is slow.
  let lit = 1;
  let litFrom = 1;
  let litTo = 1;
  let litStart = 0;
  let litDuration = 1;

  function setPower(on) {
    if (state.on === on) return;
    state.on = on;
    litFrom = lit;
    litTo = on ? 1 : 0;
    litStart = performance.now();
    litDuration = on ? 520 : 820;
  }

  function stepFilament(now) {
    if (lit === litTo) return;
    const p = clamp((now - litStart) / litDuration, 0, 1);
    lit = lerp(litFrom, litTo, p * p * (3 - 2 * p));
    if (p >= 1) lit = litTo;
  }

  // Lumen 4 — the fingertip carries a little light of its own.
  let touchX = 0;
  let touchY = 0;
  let touchTarget = 0;
  let touchStrength = 0;

  // ---------------------------------------------------------------- dom

  const card = document.getElementById('card');
  const plane = document.getElementById('cardPlane');
  const canvas = document.getElementById('cardCanvas');
  const ctx = canvas.getContext('2d');
  const halo = document.getElementById('halo');
  const railFill = document.getElementById('railFill');
  const percentEl = document.getElementById('percent');
  const pStatic = document.getElementById('pStatic');
  const pLower = document.getElementById('pLower');
  const pUpper = document.getElementById('pUpper');
  const modeBar = document.getElementById('mode');

  let dpr = 1;
  let cardW = 0;
  let cardH = 0;

  function resize() {
    const rect = card.getBoundingClientRect();
    dpr = Math.min(window.devicePixelRatio || 1, 3);
    cardW = rect.width;
    cardH = rect.height;
    canvas.width = Math.round(cardW * dpr);
    canvas.height = Math.round(cardH * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  // Static micro-grain: no flicker, no banding, a little photographic texture.
  const noisePattern = (() => {
    const n = document.createElement('canvas');
    n.width = n.height = 128;
    const nctx = n.getContext('2d');
    const img = nctx.createImageData(128, 128);
    for (let i = 0; i < img.data.length; i += 4) {
      const v = 108 + Math.random() * 40;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = v;
      img.data[i + 3] = 255;
    }
    nctx.putImageData(img, 0, 0);
    return ctx.createPattern(n, 'repeat');
  })();

  // ---------------------------------------------------------------- tilt

  let tiltX = 0; // degrees, matches graphicsLayer rotationX
  let tiltY = 0; // degrees, matches graphicsLayer rotationY
  let tiltVX = 0;
  let tiltVY = 0;
  let targetTiltX = 0;
  let targetTiltY = 0;

  function springStep(dt) {
    const stiffness = 420;
    const damping = 2 * 1 * Math.sqrt(stiffness);
    const ax = stiffness * (targetTiltX - tiltX) - damping * tiltVX;
    const ay = stiffness * (targetTiltY - tiltY) - damping * tiltVY;
    tiltVX += ax * dt;
    tiltVY += ay * dt;
    tiltX += tiltVX * dt;
    tiltY += tiltVY * dt;
  }

  function applyTilt() {
    plane.style.transform = `rotateX(${tiltX.toFixed(3)}deg) rotateY(${tiltY.toFixed(3)}deg)`;
  }

  // ---------------------------------------------------------------- render

  let inkTop = cardInk(cardEnergy(state, now0))[0];
  let inkBottom = cardInk(cardEnergy(state, now0))[1];
  let numberSize = 62;

  function drawCard(lighting, now) {
    const w = cardW;
    const h = cardH;
    if (!w || !h) return;

    const surface = hsl(lighting.hue, lighting.saturation, lighting.body);
    const core = hsl(lighting.hue, lighting.saturation, lighting.core);

    const radial = ctx.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, Math.min(w, h) / 2);
    radial.addColorStop(0, rgb(core));
    radial.addColorStop(1, rgb(surface));
    ctx.fillStyle = radial;
    ctx.fillRect(0, 0, w, h);

    // Lumen 5 — the filament: a warm core that expands on ignition and
    // collapses as the lamp cools down.
    if (lit > 0.001) {
      const eased = 1 - Math.pow(1 - lit, 3);
      const radius = Math.hypot(w, h) * 0.62 * (0.18 + 0.82 * eased);
      const fx = w * 0.5;
      const fy = h * 0.52;
      const glow = ctx.createRadialGradient(fx, fy, 0, fx, fy, radius);
      glow.addColorStop(0, `rgba(255, 236, 205, ${0.22 * lit})`);
      glow.addColorStop(0.4, `rgba(255, 220, 170, ${0.07 * lit})`);
      glow.addColorStop(1, 'rgba(255, 210, 150, 0)');
      ctx.globalCompositeOperation = 'lighter';
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, w, h);
      ctx.globalCompositeOperation = 'source-over';
    }

    // The key light orbits the card every 48 seconds.
    const orbit = ((now % CardLighting.LIGHT_PERIOD_MS) / CardLighting.LIGHT_PERIOD_MS) * 2 * Math.PI;
    const lightX = 0.5 * Math.sin(orbit);
    const lightY = -0.18 + 0.36 * Math.cos(orbit);
    const reflected = cardReflection(tiltX, tiltY, lightX, lightY);
    const specular = reflected.alpha * (state.on ? 1 : 0.35);
    const cx = (0.42 + lightX * 0.22 + reflected.shiftX) * w;
    const cy = (0.5 + lightY * 0.15 + reflected.shiftY) * h;
    const angle = reflected.angle * Math.PI / 180;
    const reach = Math.sqrt(w * w + h * h) * 0.8;
    const dx = Math.cos(angle) * reach;
    const dy = Math.sin(angle) * reach;

    const band = ctx.createLinearGradient(cx - dx, cy - dy, cx + dx, cy + dy);
    band.addColorStop(0, 'rgba(255,255,255,0)');
    band.addColorStop(0.28, 'rgba(255,255,255,0)');
    band.addColorStop(0.42, `rgba(255,255,255,${specular * 0.1})`);
    band.addColorStop(0.48, `rgba(255,255,255,${specular * 0.4})`);
    band.addColorStop(0.5, `rgba(255,255,255,${specular})`);
    band.addColorStop(0.53, `rgba(255,255,255,${specular * 0.3})`);
    band.addColorStop(0.7, 'rgba(255,255,255,0)');
    band.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = band;
    ctx.fillRect(0, 0, w, h);

    // Lumen 4 — light blooms where the finger touches, then fades after release.
    if (touchStrength > 0.004) {
      const r = w * 0.52;
      const glow = ctx.createRadialGradient(touchX, touchY, 0, touchX, touchY, r);
      glow.addColorStop(0, `rgba(255, 244, 224, ${0.3 * touchStrength})`);
      glow.addColorStop(0.4, `rgba(255, 230, 190, ${0.1 * touchStrength})`);
      glow.addColorStop(1, 'rgba(255, 220, 170, 0)');
      ctx.globalCompositeOperation = 'lighter';
      ctx.fillStyle = glow;
      ctx.fillRect(0, 0, w, h);
      ctx.globalCompositeOperation = 'source-over';
    }

    // Micro-grain, overlaid once so the surface never bands.
    ctx.save();
    ctx.globalCompositeOperation = 'overlay';
    ctx.globalAlpha = 0.055;
    ctx.fillStyle = noisePattern;
    ctx.fillRect(0, 0, w, h);
    ctx.restore();

    ctx.strokeStyle = 'rgba(216, 173, 101, 0.25)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    roundRect(ctx, 0.5, 0.5, w - 1, h - 1, 28);
    ctx.stroke();
  }

  function roundRect(c, x, y, w, h, r) {
    const radius = Math.min(r, w / 2, h / 2);
    c.moveTo(x + radius, y);
    c.arcTo(x + w, y, x + w, y + h, radius);
    c.arcTo(x + w, y + h, x, y + h, radius);
    c.arcTo(x, y + h, x, y, radius);
    c.arcTo(x, y, x + w, y, radius);
    c.closePath();
  }

  function outputMarkup(text) {
    return text.endsWith('%')
      ? `${text.slice(0, -1)}<span class="pct-sign">%</span>`
      : text;
  }

  let staticText = '';
  let rollLower = -1;
  let rollUpper = -1;

  function setRolling(value, size) {
    const bounded = clamp(value, 0, 100);
    const lower = Math.floor(bounded);
    const upper = Math.min(lower + 1, 100);
    const fraction = bounded - lower;
    const line = size * 1.2;
    if (lower !== rollLower) {
      pLower.innerHTML = outputMarkup(`${lower}%`);
      rollLower = lower;
    }
    if (upper !== rollUpper) {
      pUpper.innerHTML = outputMarkup(`${upper}%`);
      rollUpper = upper;
    }
    pLower.style.transform = `translateY(${(-fraction * line).toFixed(2)}px)`;
    pUpper.style.transform = `translateY(${((1 - fraction) * line).toFixed(2)}px)`;
  }

  function setStatic(text) {
    if (text === staticText) return;
    staticText = text;
    pStatic.innerHTML = outputMarkup(text);
  }

  let last = performance.now();

  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;

    springStep(dt);
    applyTilt();
    stepFilament(now);

    const touchRate = touchTarget > touchStrength ? 0.07 : 0.24;
    touchStrength += (touchTarget - touchStrength) * (1 - Math.exp(-dt / touchRate));

    const f = presentation.sample(state, now);
    const lighting = cardSurface(f.energy, state.on);
    drawCard(lighting, now);

    // Lumen 2 — the halo breathes with the same flux as the surface.
    const flux = clamp(f.energy / 0.85, 0, 1);
    const haloColor = hsl(lighting.hue, lighting.saturation, Math.min(lighting.core + 0.06, 0.9));
    halo.style.setProperty('--halo-color', rgb(haloColor));
    halo.style.opacity = (lit * (0.22 + 0.62 * flux)).toFixed(3);

    // Lumen 6 — live output as a length of light along the lower edge.
    const railLevel = state.on ? clamp(f.output, 0, 100) : 0;
    railFill.style.width = `${railLevel.toFixed(2)}%`;
    const railHot = hsl(lighting.hue, Math.min(lighting.saturation, 0.75), Math.min(lighting.core + 0.22, 0.92));
    railFill.style.setProperty('--rail-hot', rgb(railHot));
    railFill.style.setProperty('--rail-dim', rgb(railHot, 0.55));

    // Gradient ink follows the card energy (120ms colour tween upstream).
    const targetInk = cardInk(f.energy);
    const k = 1 - Math.exp(-dt / 0.05);
    inkTop = mixColor(inkTop, targetInk[0], k);
    inkBottom = mixColor(inkBottom, targetInk[1], k);
    document.documentElement.style.setProperty('--ink-top', rgb(inkTop));
    document.documentElement.style.setProperty('--ink-bottom', rgb(inkBottom));

    const live = state.on && state.breathing;
    const normalSize = clamp((cardW - 40) * 0.25, 32, 62);
    const targetSize = normalSize * (live ? 0.92 : 1);
    numberSize += (targetSize - numberSize) * (1 - Math.exp(-dt / 0.13));
    const line = numberSize * 1.2;
    percentEl.style.setProperty('--num-size', `${numberSize.toFixed(2)}px`);
    percentEl.style.setProperty('--num-line', `${line.toFixed(2)}px`);

    if (!state.on) {
      percentEl.classList.remove('is-rolling');
      setStatic('关');
    } else if (live && !f.transitioning) {
      percentEl.classList.add('is-rolling');
      setRolling(f.output, numberSize);
    } else {
      percentEl.classList.remove('is-rolling');
      setStatic(`${state.brightness}%`);
    }

    requestAnimationFrame(frame);
  }

  // ---------------------------------------------------------------- gestures

  let drag = null;

  function updateTilt(event) {
    const rect = card.getBoundingClientRect();
    const nx = clamp(((event.clientX - rect.left) / rect.width) * 2 - 1, -1, 1);
    const ny = clamp(((event.clientY - rect.top) / rect.height) * 2 - 1, -1, 1);
    targetTiltX = -ny * CardLighting.MAX_TILT;
    targetTiltY = nx * CardLighting.MAX_TILT;
    touchX = ((nx + 1) / 2) * cardW;
    touchY = ((ny + 1) / 2) * cardH;
  }

  card.addEventListener('pointerdown', (event) => {
    if (drag) return;
    card.setPointerCapture(event.pointerId);
    drag = {
      id: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      startTime: performance.now(),
      initial: state.on ? state.brightness : 0,
      maxDistance: 0,
      last: state.on ? state.brightness : 0,
    };
    card.classList.add('is-pressed');
    updateTilt(event);
    touchTarget = 1;
  });

  card.addEventListener('pointermove', (event) => {
    if (!drag || event.pointerId !== drag.id) return;
    updateTilt(event);
    const dx = event.clientX - drag.startX;
    const dy = event.clientY - drag.startY;
    drag.maxDistance = Math.max(drag.maxDistance, Math.hypot(dx, dy));
    if (drag.maxDistance > 8) {
      const next = slideBrightness(drag.initial, (dx - dy) / (cardW * 2));
      if (next !== drag.last) {
        drag.last = next;
        state.brightness = next;
        setPower(next > 0);
      }
    }
  });

  function endDrag(event) {
    if (!drag || event.pointerId !== drag.id) return;
    const held = performance.now() - drag.startTime;
    if (drag.maxDistance <= 8 && held < 600) {
      setPower(!state.on);
    }
    drag = null;
    touchTarget = 0;
    targetTiltX = 0;
    targetTiltY = 0;
    card.classList.remove('is-pressed');
  }

  card.addEventListener('pointerup', endDrag);
  card.addEventListener('pointercancel', endDrag);

  modeBar.addEventListener('click', (event) => {
    const button = event.target.closest('.seg');
    if (!button) return;
    const breathing = button.dataset.breathing === '1';
    state.breathing = breathing;
    modeBar.querySelectorAll('.seg').forEach((seg) => {
      const on = (seg.dataset.breathing === '1') === breathing;
      seg.classList.toggle('is-on', on);
      seg.setAttribute('aria-checked', on ? 'true' : 'false');
    });
  });

  window.addEventListener('resize', resize);
  resize();
  requestAnimationFrame(frame);

  // Small hook so the host page can be driven from automation or the console.
  window.__rhodes = {
    state,
    presentation,
    cardSurface,
    cardInk,
    slideBrightness,
    setPower,
    get lit() { return lit; },
    get touchStrength() { return touchStrength; },
    get tilt() { return { x: tiltX, y: tiltY }; },
  };
})();
