import * as THREE from '/vendor/three-r171/three.module.min.js';
import { createPageSurface, easeBookProgress } from '/scene/book-surface.js';

// All artwork is local. Canvas is only a high-resolution print surface for live pages.
const PAGE_W = 1.55, PAGE_H = 2.1;
const PRINT_W = 768, PRINT_H = 1024;
const ASSETS = {
  brand: '/assets/yx-mark-v13.png',
  projects: '/assets/icons/projects-v13.png',
  faculty: '/assets/icons/faculty-v13.png',
  calendar: '/assets/icons/calendar-v13.png',
};
const CONTENT = [
  { title: '培训运营', tags: '需求 · 项目 · 进度', art: 'projects', lines: ['专业匹配', '有据可依。'] },
  { title: '师资推荐', tags: '简历 · 经验 · 匹配', art: 'faculty', lines: ['进度清晰', '交付有序。'] },
  { title: '课程交付', tags: '排期 · 执行 · 评估', art: 'calendar', lines: ['培训运营', '从容有序。'] },
];

export function createBookScene(host, { onInvalidate = () => {}, onContextLost = () => {} } = {}) {
  let disposed = false, renderer, shadowLight;
  const geometries = new Set(), materials = new Set(), textures = new Set(), pendingImages = [];
  const scene = new THREE.Scene();
  const book = new THREE.Group();
  scene.add(book);
  const geo = (value) => { geometries.add(value); return value; };
  const mat = (value) => { materials.add(value); return value; };
  const images = {}, prints = [];
  let lastState = { page: 0, turn: null, pitch: 0, yaw: 0, hover: 0 };

  function dispose() {
    if (disposed) return;
    disposed = true;
    pendingImages.forEach(({ image, load, error }) => { image.removeEventListener('load', load); image.removeEventListener('error', error); });
    renderer?.domElement.removeEventListener('webglcontextlost', lost);
    geometries.forEach((item) => item.dispose());
    materials.forEach((item) => item.dispose());
    textures.forEach((item) => item.dispose());
    shadowLight?.shadow.dispose();
    renderer?.dispose();
    renderer?.forceContextLoss();
    renderer?.domElement.remove();
  }
  function lost(event) { event.preventDefault(); if (!disposed) onContextLost(); }
  try {
    renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' });
    renderer.setClearColor(0x000000, 0);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.NeutralToneMapping;
    renderer.toneMappingExposure = 1;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.domElement.className = 'book-three-canvas';
    renderer.domElement.setAttribute('aria-hidden', 'true');
    renderer.domElement.addEventListener('webglcontextlost', lost);
    host.appendChild(renderer.domElement);
    const camera = new THREE.PerspectiveCamera(30, 1, .1, 30);
    camera.position.set(0, .05, 6.5);
    camera.lookAt(0, 0, 0);
    scene.add(new THREE.HemisphereLight(0xffffff, 0xadb6cd, 1.7));
    const key = new THREE.DirectionalLight(0xfffcf5, 2.8);
    shadowLight = key;
    key.position.set(-3.5, 5, 6);
    key.castShadow = true;
    key.shadow.mapSize.set(1024, 1024);
    Object.assign(key.shadow.camera, { left: -3, right: 3, top: 3, bottom: -3, near: .5, far: 15 });
    key.shadow.bias = -.0004;
    key.shadow.normalBias = .012;
    key.shadow.radius = 3;
    scene.add(key);
    const fill = new THREE.DirectionalLight(0xc9ddff, .65);
    fill.position.set(4, -1, 4); scene.add(fill);

    function print(kind, data, mirror = false) {
      const canvas = document.createElement('canvas'); canvas.width = PRINT_W; canvas.height = PRINT_H;
      const ctx = canvas.getContext('2d');
      if (!ctx) throw new Error('Page print unavailable');
      const texture = new THREE.CanvasTexture(canvas);
      texture.colorSpace = THREE.SRGBColorSpace;
      texture.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
      if (mirror) { texture.repeat.x = -1; texture.offset.x = 1; }
      textures.add(texture);
      function repaint() {
        ctx.fillStyle = '#fcfcfa'; ctx.fillRect(0, 0, PRINT_W, PRINT_H);
        // Very low-contrast paper grain, deterministic and subtle at screen size.
        for (let row = 0; row < PRINT_H; row += 3) {
          ctx.fillStyle = row % 9 === 0 ? 'rgba(74,85,109,.014)' : 'rgba(255,255,255,.08)';
          ctx.fillRect(0, row, PRINT_W, 1);
        }
        const edge = ctx.createLinearGradient(0, 0, PRINT_W, 0);
        if (kind === 'left') { edge.addColorStop(.91, 'rgba(83,94,121,0)'); edge.addColorStop(1, 'rgba(83,94,121,.11)'); }
        else { edge.addColorStop(0, 'rgba(83,94,121,.11)'); edge.addColorStop(.09, 'rgba(83,94,121,0)'); }
        ctx.fillStyle = edge; ctx.fillRect(0, 0, PRINT_W, PRINT_H);
        ctx.textBaseline = 'alphabetic';
        if (kind === 'left') {
          ctx.fillStyle = '#a6b3c8'; ctx.fillRect(365, 380, 38, 3);
          ctx.fillStyle = '#62718a'; ctx.textAlign = 'center'; ctx.font = '400 60px "PingFang SC", "Microsoft YaHei", sans-serif';
          data.lines.forEach((line, i) => ctx.fillText(line, 384, 495 + i * 103));
        } else if (kind === 'brand') {
          if (images.brand) ctx.drawImage(images.brand, 278, 308, 212, 212);
          ctx.textAlign = 'center'; ctx.fillStyle = '#263b5a';
          ctx.font = '500 76px "PingFang SC", "Microsoft YaHei", sans-serif'; ctx.fillText('研 序', 384, 632);
        } else {
          if (images[data.art]) ctx.drawImage(images[data.art], 299, 290, 170, 170);
          ctx.textAlign = 'center'; ctx.fillStyle = '#2e425f';
          ctx.font = '500 66px "PingFang SC", "Microsoft YaHei", sans-serif'; ctx.fillText(data.title, 384, 558);
          ctx.fillStyle = '#8793a7'; ctx.font = '400 28px "PingFang SC", "Microsoft YaHei", sans-serif'; ctx.fillText(data.tags, 384, 634);
        }
        texture.needsUpdate = true;
      }
      prints.push(repaint); repaint(); return texture;
    }
    const paper = (map, side = THREE.FrontSide) => mat(new THREE.MeshStandardMaterial({ map, color: 0xffffff, roughness: .94, metalness: 0, side }));
    const coverMaterial = mat(new THREE.MeshPhysicalMaterial({ color: 0xa1afc5, roughness: .68, metalness: .04, clearcoat: .1, clearcoatRoughness: .74 }));
    const blockMaterial = mat(new THREE.MeshStandardMaterial({ color: 0xeff0ee, roughness: .95 }));
    const edgeMaterial = mat(new THREE.MeshStandardMaterial({ color: 0xd7dce4, roughness: 1 }));
    function roundedCover(width, height) {
      const r = .035, x = -width / 2, y = -height / 2;
      const shape = new THREE.Shape();
      shape.moveTo(x + r, y); shape.lineTo(x + width - r, y); shape.quadraticCurveTo(x + width, y, x + width, y + r);
      shape.lineTo(x + width, y + height - r); shape.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
      shape.lineTo(x + r, y + height); shape.quadraticCurveTo(x, y + height, x, y + height - r);
      shape.lineTo(x, y + r); shape.quadraticCurveTo(x, y, x + r, y);
      return geo(new THREE.ExtrudeGeometry(shape, { depth: .025, bevelEnabled: true, bevelSize: .012, bevelThickness: .009, bevelSegments: 3, steps: 1, curveSegments: 8 }));
    }
    const hitMeshes = [];
    for (const side of [-1, 1]) {
      const cover = new THREE.Mesh(roundedCover(PAGE_W + .065, PAGE_H + .075), coverMaterial);
      cover.position.set(side * (PAGE_W / 2 + .002), 0, -.094); cover.castShadow = true; book.add(cover); hitMeshes.push(cover);
      const block = new THREE.Mesh(geo(new THREE.BoxGeometry(PAGE_W - .006, PAGE_H - .015, .05)), blockMaterial);
      block.position.set(side * PAGE_W / 2, 0, -.026); block.castShadow = true; block.receiveShadow = true; book.add(block);
      for (let i = 0; i < 4; i += 1) {
        const edge = new THREE.Mesh(geo(new THREE.BoxGeometry(PAGE_W, .0011, .002)), edgeMaterial);
        edge.position.set(side * PAGE_W / 2, -PAGE_H / 2 + .003, -.047 + i * .012); book.add(edge);
      }
    }
    const spine = new THREE.Mesh(geo(new THREE.CylinderGeometry(.048, .048, PAGE_H + .035, 20)), coverMaterial);
    spine.position.z = -.049; book.add(spine);
    // A soft contact pool grounds the book without a hard rectangular backdrop.
    const shadowCanvas = document.createElement('canvas'); shadowCanvas.width = shadowCanvas.height = 128;
    const shadowContext = shadowCanvas.getContext('2d');
    if (shadowContext) {
      const gradient = shadowContext.createRadialGradient(64, 64, 5, 64, 64, 64);
      gradient.addColorStop(0, 'rgba(45,65,100,.22)');
      gradient.addColorStop(.5, 'rgba(45,65,100,.10)');
      gradient.addColorStop(1, 'rgba(45,65,100,0)');
      shadowContext.fillStyle = gradient; shadowContext.fillRect(0, 0, 128, 128);
      const shadowTexture = new THREE.CanvasTexture(shadowCanvas); shadowTexture.colorSpace = THREE.SRGBColorSpace; textures.add(shadowTexture);
      const pool = new THREE.Mesh(geo(new THREE.PlaneGeometry(4.4, 2.9)), mat(new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, depthWrite: false, toneMapped: false })));
      pool.position.set(.07, -.2, -.82); scene.add(pool);
    }

    function surface(frontTexture, backTexture, baseZ) {
      const data = createPageSurface({ width: PAGE_W, height: PAGE_H, cols: 48, rows: 12 });
      const geometry = geo(new THREE.BufferGeometry());
      geometry.setAttribute('position', new THREE.BufferAttribute(data.positions, 3).setUsage(THREE.DynamicDrawUsage));
      geometry.setAttribute('uv', new THREE.BufferAttribute(data.uvs, 2)); geometry.setIndex(new THREE.BufferAttribute(data.indices, 1));
      geometry.boundingSphere = new THREE.Sphere(new THREE.Vector3(), data.boundingSphere.radius + .12);
      const meshes = [];
      if (frontTexture) {
        const front = new THREE.Mesh(geometry, paper(frontTexture));
        front.castShadow = true; front.receiveShadow = true; front.frustumCulled = false; front.position.z = baseZ; book.add(front); hitMeshes.push(front); meshes.push(front);
      }
      if (backTexture) {
        const back = new THREE.Mesh(geometry, paper(backTexture, THREE.BackSide));
        back.castShadow = true; back.receiveShadow = true; back.frustumCulled = false; back.position.z = baseZ; book.add(back); hitMeshes.push(back); meshes.push(back);
      }
      let previous = NaN;
      return { set(progress, hover = 0, elevation = baseZ) {
        meshes.forEach((mesh) => { mesh.position.z = elevation; });
        const signature = progress + hover * .001;
        if (signature === previous) return; previous = signature;
        data.update(progress);
        const a = data.positions;
        for (let row = 0; row <= 12; row += 1) for (let col = 0; col <= 48; col += 1) {
          const u = col / 48, v = row / 12, index = (row * 49 + col) * 3;
          a[index + 2] += .024 * Math.sin(Math.PI * u) + hover * .07 * u ** 5 * (1 - v) ** 4;
        }
        geometry.attributes.position.needsUpdate = true; geometry.computeVertexNormals();
      } };
    }
    // A left-side page must expose its back after folding, so use a mirrored back map.
    const leftBaseBack = surface(null, print('left', { lines: ['需求明确', '推进有序。'] }, true), .005);
    leftBaseBack.set(1);
    const rightBase = surface(print('brand'), null, .003); rightBase.set(0);
    const pages = CONTENT.map((content, i) => surface(print('front', content), print('left', content, true), .014 + i * .006));
    const raycaster = new THREE.Raycaster(), pointer = new THREE.Vector2();
    function render(state = lastState) {
      if (disposed) return;
      lastState = state;
      book.rotation.set(-.24 + state.pitch, -.06 + state.yaw, .065);
      pages.forEach((pageSurface, i) => {
        const t = state.turn?.index === i ? state.turn.progress : i < state.page ? 1 : 0;
        const eased = easeBookProgress(t);
        const elevation = state.turn?.index === i
          ? .014 + ((3 - i) * (1 - eased) + (i + 1) * eased) * .006 + .036 * Math.sin(Math.PI * eased)
          : .014 + (i < state.page ? i + 1 : 3 - i) * .006;
        pageSurface.set(t, !state.turn && i === state.page ? state.hover : 0, elevation);
      });
      renderer.render(scene, camera);
    }
    function resize() {
      if (disposed) return;
      const width = Math.max(1, host.clientWidth), height = Math.max(1, host.clientHeight);
      const ratio = Math.min(window.devicePixelRatio || 1, 1.7, Math.sqrt(1500000 / (width * height)));
      renderer.setPixelRatio(ratio); renderer.setSize(width, height, false);
      camera.aspect = width / height;
      const viewHeight = Math.max(2.85, 3.85 / camera.aspect);
      camera.fov = THREE.MathUtils.radToDeg(2 * Math.atan(viewHeight / (2 * camera.position.z)));
      camera.updateProjectionMatrix(); render();
    }
    function pick(clientX, clientY) {
      if (disposed) return null;
      const rect = host.getBoundingClientRect();
      pointer.set((clientX - rect.left) / rect.width * 2 - 1, -(clientY - rect.top) / rect.height * 2 + 1);
      raycaster.setFromCamera(pointer, camera);
      const hit = raycaster.intersectObjects(hitMeshes, false)[0];
      if (!hit) return null;
      const local = book.worldToLocal(hit.point.clone());
      return { side: local.x < 0 ? 'left' : 'right', x: local.x, y: local.y };
    }
    Object.entries(ASSETS).forEach(([name, url]) => {
      const image = new Image();
      const load = () => { if (disposed) return; images[name] = image; prints.forEach((repaint) => repaint()); onInvalidate(); };
      const error = () => { if (!disposed) onInvalidate(); };
      pendingImages.push({ image, load, error }); image.addEventListener('load', load); image.addEventListener('error', error); image.src = url;
    });
    resize();
    return { render, resize, pick, dispose, revision: THREE.REVISION };
  } catch (error) { dispose(); throw error; }
}
