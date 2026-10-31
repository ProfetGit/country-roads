// Country Roads icon scene (pack-icon-animation skill), global `ROADS` (not `CR`: Blockbench has its own). Run inside Blockbench (free format project):
//   eval(require('fs').readFileSync('<this file>', 'utf8')); window.ROADS = ROADS; ROADS.loadTextures()   // then, in a later call:
//   ROADS.build(); ROADS.animate(); ROADS.camera()                                                       // then:
//   ROADS.render(first, last)                                                                       // 1600px frames -> frames/
// A gallows signpost drops onto the grass beside a bend, the landing thump ripples out and the grass tiles flip into road
// one after another (accelerating), wildflowers sprout on the verge, the sign sways. Then it lifts off and the road folds back.
// Keys are authored in seconds on a 60 fps grid and rendered at 60 fps (LEN 3.0 s = 180 frames).
// Screen space: the `screen` group is tilted to face the orthographic camera: x = right, y = up, z = toward the camera.
var ROADS = (function () {
  const fs = require('fs');
  const DIR = '/home/emppu/Projects/Minecraft Datapacks/mods/CountryRoads/dev/icon/';
  const TEX = DIR + 'sprites/';
  const FPS = 60, DT = 1 / FPS, LEN = 3.0;
  const CAM_POS = [0, 60, 104], CAM_TARGET = [0, 16, 0], CAM_PAN = [27, 12, 0], CAM_ZOOM = 0.4;
  const PITCH = -Math.atan2(CAM_POS[1] - CAM_TARGET[1], CAM_POS[2] - CAM_TARGET[2]) * 180 / Math.PI;
  const O = new THREE.Vector3(...CAM_TARGET);
  const RAD = Math.PI / 180;
  const Z_SIGN = 40, Z_BOARD = 41, Z_FLOWER = 30, Z_DEBRIS = 36, Z_FX = 50;

  // cells (cx, cz) on the 16-unit grid; the road runs (0,1) (1,1) (1,0) (2,0) (3,0); the sign stands on grass (2,1)
  const ROAD = [[2, 0], [1, 0], [3, 0], [1, 1], [0, 1]];          // flip order: next to the sign first
  const GRASS = [[0, 0], [2, 1], [3, 1], [0, 2]];
  const SIGN_FOOT = [32 + 1.5, 16, 16 + 3.5];                      // vein coords on the top of (2,1)
  const FLOWERS = [
    { cell: [0, 0], at: [-3.5, -2.5], tex: 'flower_red' },
    { cell: [3, 1], at: [3, 2.5], tex: 'flower_yellow' },
    { cell: [0, 2], at: [3.5, 3], tex: 'flower_blue' },
    { cell: [2, 1], at: [4, -4], tex: 'flower_red' },
    { cell: [3, 1], at: [-3, -3], tex: 'flower_blue' },
  ];

  const T = {
    drop: 0.28, land: 0.47,
    flips: [0.60, 0.70, 0.73, 0.82, 0.90],
    flowers: [1.00, 1.07, 1.13, 1.19, 1.25],
    lift: 2.40, gone: 2.64,
    unflips: [2.62, 2.58, 2.55, 2.50, 2.45],     // reverse of the flip order: the far end folds up first
    unflowers: [2.42, 2.46, 2.5, 2.54, 2.58],
  };

  const q = t => Math.round(t * FPS) / FPS;
  const worldToScreen = w => new THREE.Vector3(...w).sub(O).applyEuler(new THREE.Euler(-PITCH * RAD, 0, 0)).add(O);
  const veinToWorld = a => new THREE.Vector3(...a).applyEuler(new THREE.Euler(0, 45 * RAD, 0));
  const toScreen = a => worldToScreen(veinToWorld(a).toArray());

  const tex = {};
  function loadTextures() {
    Texture.all.slice().forEach(t => t.remove(true));
    for (const f of fs.readdirSync(TEX).filter(f => f.endsWith('.png') && !/^(bg|banner|kit)_/.test(f))) {
      const url = 'data:image/png;base64,' + fs.readFileSync(TEX + f).toString('base64');
      tex[f.slice(0, -4)] = new Texture({ name: f }).fromDataURL(url).add(false);
    }
    return Object.keys(tex).join(',');
  }
  function ensureTex() {
    if (!Object.keys(tex).length) Texture.all.forEach(t => { tex[t.name.replace('.png', '')] = t; });
  }
  function pixels(name) {
    const t = tex[name], img = t.img;
    const cv = document.createElement('canvas');
    cv.width = img.naturalWidth; cv.height = img.naturalHeight;
    const ctx = cv.getContext('2d'); ctx.drawImage(img, 0, 0);
    const d = ctx.getImageData(0, 0, cv.width, cv.height).data, out = [];
    for (let y = 0; y < cv.height; y++) for (let x = 0; x < cv.width; x++) if (d[(y * cv.width + x) * 4 + 3] > 127) out.push([x, y]);
    return out;
  }

  function group(name, origin, parent, rotation) {
    const g = new Group({ name, origin, rotation: rotation || [0, 0, 0] });
    g.addTo(parent); g.init();
    return g;
  }
  const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
  function cube(name, from, to, parent, faceTex, opts) {
    const c = new Cube(Object.assign({ name, from, to, box_uv: false }, opts || {}));
    c.addTo(parent); c.init();
    for (const f of FACES) {
      const spec = faceTex[f] || faceTex.all;
      if (spec) c.faces[f].extend({ texture: tex[spec[0]].uuid, uv: spec[1] });
      else c.faces[f].extend({ texture: null });
    }
    return c;
  }
  function plane(name, centre, size, parent, texName, uvSize, mirror) {
    const [x, y, z] = centre, h = size / 2;
    const uv = mirror ? [uvSize, 0, 0, uvSize] : [0, 0, uvSize, uvSize];
    return cube(name, [x - h, y - h, z], [x + h, y + h, z], parent, { south: [texName, uv] });
  }
  function extrude(prefix, texName, pivotPx, at, s, parent) {
    for (const [x, y] of pixels(texName)) {
      const x0 = at[0] + (x - pivotPx[0]) * s, y0 = at[1] + (pivotPx[1] - y - 1) * s;
      cube(prefix, [x0, y0, at[2] - s / 2], [x0 + s, y0 + s, at[2] + s / 2], parent, { all: [texName, [x, y, x + 1, y + 1]] });
    }
  }

  const SIGN_PX = 1.2;
  // post sprite 22x22: foot = bottom centre of the 3-wide post; chains hang under the arm at post x 8.5 and 18.5
  const POST_PIVOT = [2.5, 22], BOARD_ATTACH_POST = [13.5, 2], BOARD_PIVOT = [7.5, 0];
  const FLOWER_SIZE = 7, DEBRIS = [
    { size: 3.0, uv: [0, 0, 4, 4] }, { size: 2.6, uv: [4, 0, 8, 4] }, { size: 2.4, uv: [8, 0, 12, 4] },
  ];
  const SPARKS = [20, 75, 130, 170];
  const tileTop = c => toScreen([c[0] * 16, 16, c[1] * 16]);
  const cubeFaces = (top, left, right) => ({
    up: [top, [0, 0, 16, 16]], west: [left, [0, 0, 16, 16]], south: [right, [0, 0, 16, 16]],
    north: [right, [0, 0, 16, 16]], east: [left, [0, 0, 16, 16]], down: [right, [0, 0, 16, 16]],
  });

  const G = {};
  function build() {
    ensureTex();
    Animation.all.slice().forEach(a => a.remove(false));
    Outliner.root.slice().forEach(n => n.remove(false));

    G.vein = group('vein', [0, 0, 0], undefined, [0, 45, 0]);
    GRASS.forEach((c, i) => {
      const [cx, cz] = [c[0] * 16, c[1] * 16];
      cube('grass_block_' + i, [cx - 8, 0, cz - 8], [cx + 8, 16, cz + 8], G.vein, cubeFaces('grass_top', 'grass_side_left', 'grass_side_right'));
    });
    ROAD.forEach((c, i) => {
      const [cx, cz] = [c[0] * 16, c[1] * 16];
      const t = G['tile_' + i] = group('tile_' + i, [cx, 0, cz], G.vein);
      const gr = G['gr_' + i] = group('gr_' + i, [cx, 0, cz], t);
      cube('tile_grass_' + i, [cx - 8, 0, cz - 8], [cx + 8, 16, cz + 8], gr, cubeFaces('grass_top', 'grass_side_left', 'grass_side_right'));
      const rd = G['rd_' + i] = group('rd_' + i, [cx, 0, cz], t);
      cube('tile_road_' + i, [cx - 8, 0, cz - 8], [cx + 8, 16, cz + 8], rd, cubeFaces('road_top', 'road_side_left', 'road_side_right'));
      const fl = G['flash_' + i] = group('flash_' + i, [cx, 8, cz], t);
      cube('flash_cube_' + i, [cx - 8, 0, cz - 8], [cx + 8, 16, cz + 8], fl, { all: ['hit_flash', [0, 0, 16, 16]] }, { inflate: 0.35 });
    });

    G.screen = group('screen', O.toArray(), undefined, [PITCH, 0, 0]);
    const L = layout();

    G.sign = group('sign', [L.foot.x, L.foot.y, Z_SIGN], G.screen);
    extrude('post', 'signpost_post', POST_PIVOT, [L.foot.x, L.foot.y, Z_SIGN], SIGN_PX, G.sign);
    const att = [L.foot.x + (BOARD_ATTACH_POST[0] - POST_PIVOT[0]) * SIGN_PX, L.foot.y + (POST_PIVOT[1] - BOARD_ATTACH_POST[1]) * SIGN_PX];
    L.attach = att;
    G.board = group('board', [att[0], att[1], Z_BOARD], G.sign);
    extrude('board', 'sign_board', BOARD_PIVOT, [att[0], att[1], Z_BOARD], SIGN_PX, G.board);

    FLOWERS.forEach((f, i) => {
      const p = L.flowers[i];
      const g = G['flower_' + i] = group('flower_' + i, [p.x, p.y, Z_FLOWER + i * 0.1], G.screen);
      plane('flower_plane_' + i, [p.x, p.y + FLOWER_SIZE / 2, Z_FLOWER + i * 0.1], FLOWER_SIZE, g, f.tex, 16);
    });

    ROAD.forEach((c, i) => {
      const t = L.tops[i];
      G['puff_' + i] = group('puff_' + i, [t.x, t.y, Z_FX + i], G.screen);
      plane('puff_plane_' + i, [t.x, t.y, Z_FX + i], 12, G['puff_' + i], 'fx_dust', 16);
      for (let k = 0; k < DEBRIS.length; k++) {
        const d = G[`debris_${i}_${k}`] = group(`debris_${i}_${k}`, [t.x, t.y, Z_DEBRIS + k], G.screen);
        const h = DEBRIS[k].size / 2;
        cube('chunk', [t.x - h, t.y - h, Z_DEBRIS + k - h], [t.x + h, t.y + h, Z_DEBRIS + k + h], d, { all: ['chunks', DEBRIS[k].uv] });
      }
    });
    G.puff_l = group('puff_l', [L.foot.x, L.foot.y, Z_FX + 8], G.screen);
    plane('puff_l_plane', [L.foot.x, L.foot.y, Z_FX + 8], 11, G.puff_l, 'fx_dust', 16);
    G.puff_r = group('puff_r', [L.foot.x, L.foot.y, Z_FX + 8], G.screen);
    plane('puff_r_plane', [L.foot.x, L.foot.y, Z_FX + 8], 11, G.puff_r, 'fx_dust', 16, true);
    G.star = group('star', [L.foot.x, L.foot.y + 2, Z_FX + 9], G.screen);
    plane('star_plane', [L.foot.x, L.foot.y + 2, Z_FX + 9], 18, G.star, 'fx_star', 16);
    G.ring = group('ring', [L.foot.x, L.foot.y + 2, Z_FX + 7], G.screen);
    plane('ring_plane', [L.foot.x, L.foot.y + 2, Z_FX + 7], 24, G.ring, 'fx_ring', 16);
    SPARKS.forEach((a, k) => {
      G['spark_' + k] = group('spark_' + k, [L.foot.x, L.foot.y + 2, Z_FX + 10], G.screen);
      plane('spark_plane', [L.foot.x, L.foot.y + 2, Z_FX + 10], 6, G['spark_' + k], 'fx_spark', 16);
    });

    Canvas.updateAll();
    return Outliner.elements.length;
  }

  function layout() {
    const f = toScreen(SIGN_FOOT);
    const tops = ROAD.map(tileTop);
    const flowers = FLOWERS.map(fl => {
      const p = toScreen([fl.cell[0] * 16 + fl.at[0], 16, fl.cell[1] * 16 + fl.at[1]]);
      return { x: p.x, y: p.y };
    });
    const board = { x: f.x + (13.5 - 2.5) * SIGN_PX, y: f.y + (22 - 2 - 4) * SIGN_PX };
    return { foot: { x: f.x, y: f.y }, tops, flowers, board };
  }

  // ---- animation ----
  let A = null;
  function K(g, ch, t, v, interp) {
    const [x, y, z] = typeof v === 'number' ? [v, v, v] : v;
    A.getBoneAnimator(g).addKeyframe({ channel: ch, time: q(t), interpolation: interp || 'linear', data_points: [{ x, y, z }] });
  }
  const track = (g, ch, keys, interp) => keys.forEach(([t, v, i]) => K(g, ch, t, v, i || interp));
  function ensureG() {
    if (!Object.keys(G).length) Group.all.forEach(g => { G[g.name] = g; });
  }
  const f60 = n => n * DT;

  function animate() {
    ensureTex(); ensureG();
    Animation.all.slice().forEach(a => a.remove(false));
    A = new Animation({ name: 'icon_loop', length: LEN, loop: 'loop', snapping: FPS }).add(false);
    A.select();
    const L = layout(), warnings = [];
    const z = r => [0, 0, r];
    const land = T.land, Hs = 74;

    // sign: hidden, appears far above the frame, falls (accelerating, stretched), lands with a squash, rests, lifts off again
    const nF = Math.round((land - T.drop) / DT), posK = [[0, [0, Hs, 0], 'step'], [T.drop, [0, Hs, 0]]], scK = [[0, 0, 'step'], [T.drop, [0.88, 1.18, 1]]];
    for (let f = 1; f <= nF; f++) {
      const u = f / nF;
      posK.push([T.drop + f * DT, [0, Hs * (1 - u * u), 0]]);
    }
    scK.push([land - DT, [0.9, 1.16, 1]], [land, [1.28, 0.7, 1]], [land + f60(4), [1.2, 0.76, 1]], [land + f60(9), [0.92, 1.1, 1]],
      [land + f60(15), [1.04, 0.97, 1]], [land + f60(22), 1]);
    const lift = T.lift;
    posK.push([lift, [0, 0, 0]], [lift + f60(5), [0, -1.2, 0]], [lift + f60(9), [0, 6, 0]]);
    const nL = Math.round((T.gone - lift - f60(9)) / DT);
    for (let f = 1; f <= nL; f++) {
      const u = f / nL;
      posK.push([lift + f60(9) + f * DT, [0, 6 + (Hs - 6) * u * u, 0]]);
    }
    scK.push([lift, 1], [lift + f60(5), [1.12, 0.84, 1]], [lift + f60(9), [0.88, 1.18, 1]], [T.gone - DT, [0.9, 1.16, 1]], [T.gone, 0, 'step']);
    track(G.sign, 'position', posK);
    track(G.sign, 'scale', scK);
    // the board trails up while falling, kicks forward on landing, then swings out
    const swing = [[0, z(0)], [T.drop, z(-6)], [land - DT, z(-10)], [land, z(-6)],
      [land + f60(6), z(15)], [land + f60(16), z(-11)], [land + f60(27), z(8)], [land + f60(38), z(-5)],
      [land + f60(50), z(3)], [land + f60(62), z(-1.5)], [land + f60(76), z(0.6)], [land + f60(92), z(0)], [lift, z(0)],
      [lift + f60(5), z(4)], [lift + f60(9), z(-8)], [T.gone, z(-10)], [LEN, z(0)]];
    track(G.board, 'rotation', swing, 'catmullrom');

    // landing FX: star, ring, dust spreading to both sides, sparks, a short shake of the blocks
    const I = land;
    track(G.star, 'scale', [[0, 0], [I - DT, 0], [I, 1.35], [I + f60(2), 1.0], [I + f60(5), 0.5], [I + f60(7), 0]]);
    track(G.star, 'rotation', [[I, z(0)], [I + f60(7), z(30)]]);
    track(G.ring, 'scale', [[0, 0], [I - DT, 0], [I, 0.45], [I + f60(2), 0.9], [I + f60(5), 1.4], [I + f60(8), 0]]);
    [['puff_l', -1], ['puff_r', 1]].forEach(([n, s]) => {
      track(G[n], 'scale', [[0, 0], [I, 0], [I + f60(2), 0.7], [I + f60(5), 1.2], [I + f60(10), 1.05], [I + f60(15), 0.6], [I + f60(19), 0]]);
      track(G[n], 'position', [[I, [0, 0, 0]], [I + f60(19), [s * 20, 2, 0]]]);
    });
    SPARKS.forEach((a, k) => {
      const keys = [[0, 0], [I - DT, 0]], pos = [];
      for (let f = 0; f <= 7; f++) {
        const t = I + f * DT, d = 19 * (1 - Math.pow(1 - f / 7, 2));
        keys.push([t, f === 7 ? 0 : 1 - f * 0.12]);
        pos.push([t, [Math.cos(a * RAD) * d, Math.sin(a * RAD) * d, 0]]);
      }
      track(G['spark_' + k], 'scale', keys);
      track(G['spark_' + k], 'position', pos);
    });
    track(G.vein, 'position', [[I - DT, [0, 0, 0]], [I, [0, -1.1, 0]], [I + f60(2), [0.8, 0.5, 0]], [I + f60(4), [-0.6, 0, 0]], [I + f60(6), [0.3, 0, 0]], [I + f60(8), [0, 0, 0]]]);

    // road tiles: squash, stretch + swap to road with a flash, settle; folded back at the end
    ROAD.forEach((c, i) => {
      const tf = T.flips[i], tu = T.unflips[i], t0 = L.tops[i];
      const tile = G['tile_' + i];
      track(tile, 'scale', [
        [0, 1], [tf - f60(4), 1], [tf - f60(2), [1.1, 0.86, 1.1]], [tf, [0.88, 1.2, 0.88]], [tf + f60(2), [1.1, 0.88, 1.1]],
        [tf + f60(4), [0.97, 1.04, 0.97]], [tf + f60(6), 1],
        [tu - f60(3), 1], [tu, [1.1, 0.84, 1.1]], [tu + f60(3), [0.95, 1.07, 0.95]], [tu + f60(6), 1], [LEN, 1],
      ]);
      track(G['gr_' + i], 'scale', [[0, 1, 'step'], [tf, 0, 'step'], [tu, 1, 'step']]);
      track(G['rd_' + i], 'scale', [[0, 0, 'step'], [tf, 1, 'step'], [tu, 0, 'step']]);
      track(G['flash_' + i], 'scale', [[0, 0, 'step'], [tf, 1, 'step'], [tf + f60(2), 0, 'step']]);

      const p0 = tf;
      track(G['puff_' + i], 'scale', [[0, 0], [tf, 0], [p0 + f60(2), 0.7], [p0 + f60(5), 1.25], [p0 + f60(9), 1.1], [p0 + f60(13), 0.6], [p0 + f60(17), 0],
        [tu, 0], [tu + f60(2), 0.5], [tu + f60(5), 0.8], [tu + f60(9), 0]]);
      track(G['puff_' + i], 'position', [[tf, [0, 0, 0]], [tf + f60(17), [0, 5, 0]], [tu, [0, 0, 0]], [tu + f60(9), [0, 3, 0]]]);
      DEBRIS.forEach((d, k) => {
        const g = G[`debris_${i}_${k}`];
        const ang = (i * 47 + k * 120 + 35) % 360, sp = 70 + 25 * k, grav = 900;
        const vx = Math.cos(ang * RAD) * sp, vy = Math.abs(Math.sin(ang * RAD)) * sp + 60;
        const pos = [], rot = [];
        for (let f = 0; f <= 18; f++) {
          const t = f * DT;
          pos.push([tf + t, [vx * t, vy * t - grav * t * t / 2, 0]]);
          rot.push([tf + t, [f * 20, 0, f * (k % 2 ? 27 : -27)]]);
        }
        track(g, 'position', pos);
        track(g, 'rotation', rot);
        track(g, 'scale', [[0, 0], [tf, 0], [tf + DT, 1], [tf + f60(13), 1], [tf + f60(17), 0.5], [tf + f60(19), 0]]);
      });
    });

    // flowers: boing in, folded out at the end
    FLOWERS.forEach((f, i) => {
      const tr = T.flowers[i], tu = T.unflowers[i], g = G['flower_' + i];
      track(g, 'scale', [
        [0, 0], [tr, 0], [tr + f60(2), [0.8, 1.3, 1]], [tr + f60(4), [1.25, 0.78, 1]], [tr + f60(6), [0.92, 1.1, 1]], [tr + f60(8), [1.04, 0.97, 1]], [tr + f60(10), 1],
        [tu, 1], [tu + f60(3), [1.15, 1.2, 1]], [tu + f60(6), 0], [LEN, 0],
      ]);
    });
    Animator.preview();
    return warnings.length ? warnings.join('; ') : layoutInfo();
  }
  function layoutInfo() {
    const L = layout(), r = v => Math.round(v * 10) / 10;
    return JSON.stringify({ foot: [r(L.foot.x), r(L.foot.y)], tops: L.tops.map(t => [r(t.x), r(t.y)]) });
  }

  // ---- camera + render ----
  function camera(zoom) {
    const p = Preview.selected;
    p.setProjectionMode(true);
    const pos = CAM_POS.map((v, i) => v + CAM_PAN[i]), tgt = CAM_TARGET.map((v, i) => v + CAM_PAN[i]);
    p.camera.position.set(...pos);
    p.controls.target.set(...tgt);
    p.camera.lookAt(...tgt);
    p.camera.zoom = zoom || CAM_ZOOM; p.camera.updateProjectionMatrix();
    p.controls.update();
  }
  function setTime(t) {
    Timeline.setTime(t);
    Animator.preview();
  }
  function render(first, last, res, dir) {
    res = res || 1600;
    dir = dir || DIR + 'frames/';
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
    const shot = f => new Promise(done => {
      setTime(f * DT);
      Screencam.advancedScreenshot(Preview.selected, { angle_preset: 'view', resolution: [res, res], anti_aliasing: 'none', shading: false }, url => {
        fs.writeFileSync(dir + 'frame_' + String(f).padStart(3, '0') + '.png', Buffer.from(url.split(',')[1], 'base64'));
        done();
      });
    });
    return (async () => { for (let f = first; f <= last; f++) await shot(f); return `rendered ${first}..${last}`; })();
  }
  function scaleSweep() {
    const bad = [];
    for (let f = 0; f <= Math.round(LEN * FPS); f++) {
      setTime(f * DT);
      Group.all.forEach(g => { const s = g.mesh.scale; if (s.x < 0 || s.y < 0 || s.z < 0) bad.push(g.name + '@' + f); });
    }
    return bad.length ? bad.join(',') : 'no negative scale';
  }

  return { FPS, DT, LEN, T, PITCH, G, tex, loadTextures, build, animate, camera, render, setTime, scaleSweep, layoutInfo, layout, pixels, worldToScreen, veinToWorld, q };
})();
