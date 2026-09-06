import * as THREE from '/vendor/three-r171/three.module.min.js';
import { createPageSurface } from '/scene/book-surface.js';
import { createPaperBlock, pageRestRelief } from '/scene/book-binding.js';

// All artwork is local. Canvas is only a high-resolution print surface for live pages.
const PAGE_W = 1.55, PAGE_H = 2.1;
const PRINT_W = 768, PRINT_H = 1024;
const ASSETS = {
  brand: '/assets/yx-mark-v13.png',
  endorsement: '/assets/book-endorsement-v13r8.png',
  paper: '/assets/book-paper-v13r7.jpg',
};
const TOPICS = ['培训运营', '师资推荐', '课程交付', '项目管理'];

export function createBookScene(host, { onInvalidate = () => {}, onContextLost = () => {} } = {}) {
  let disposed = false, renderer, shadowLight;
  const geometries = new Set(), materials = new Set(), textures = new Set(), pendingImages = [];
  const scene = new THREE.Scene();
  const book = new THREE.Group();
  scene.add(book);
  const geo = (value) => { geometries.add(value); return value; };
  const mat = (value) => { materials.add(value); return value; };
  const images = {}, prints = [], paperMaterials = [];
  let lastState = { topic: 0, transition: null, pitch: 0, yaw: 0 };

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
    // Fixed paper surfaces need no animated shadow map; avoid edge acne at mobile scale.
    renderer.shadowMap.enabled = false;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.domElement.className = 'book-three-canvas';
    renderer.domElement.setAttribute('aria-hidden', 'true');
    renderer.domElement.addEventListener('webglcontextlost', lost);
    host.appendChild(renderer.domElement);
    const camera = new THREE.PerspectiveCamera(30, 1, .1, 30);
    camera.position.set(0, .05, 6.5);
    camera.lookAt(0, 0, 0);
    scene.add(new THREE.HemisphereLight(0xf6f8ff, 0x8994a2, 1.2));
    const key = new THREE.DirectionalLight(0xffffff, 2.7);
    shadowLight = key;
    key.position.set(-3.2, 4.5, 5.5);
    key.castShadow = false;
    key.shadow.mapSize.set(1024, 1024);
    Object.assign(key.shadow.camera, { left: -3, right: 3, top: 3, bottom: -3, near: .5, far: 15 });
    key.shadow.bias = -.0007;
    key.shadow.normalBias = .006;
    scene.add(key);
    const fill = new THREE.DirectionalLight(0xc0d3ee, .5);
    fill.position.set(3, 1.3, 4); scene.add(fill);

    function print(kind, mirror = false) {
      const canvas = document.createElement('canvas'); canvas.width = PRINT_W; canvas.height = PRINT_H;
      const ctx = canvas.getContext('2d');
      if (!ctx) throw new Error('Page print unavailable');
      const texture = new THREE.CanvasTexture(canvas);
      texture.colorSpace = THREE.SRGBColorSpace;
      texture.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy());
      if (mirror) { texture.repeat.x = -1; texture.offset.x = 1; }
      textures.add(texture);
      function repaint() {
        ctx.fillStyle = '#ffffff'; ctx.fillRect(0, 0, PRINT_W, PRINT_H);
        if (images.paper) { ctx.globalAlpha = .12; ctx.drawImage(images.paper, 0, 0, PRINT_W, PRINT_H); ctx.globalAlpha = 1; }
        const edge = ctx.createLinearGradient(0, 0, PRINT_W, 0);
        if (kind === 'left') { edge.addColorStop(.935, 'rgba(68,64,59,0)'); edge.addColorStop(1, 'rgba(68,64,59,.075)'); }
        else { edge.addColorStop(0, 'rgba(68,64,59,.075)'); edge.addColorStop(.065, 'rgba(68,64,59,0)'); }
        ctx.fillStyle = edge; ctx.fillRect(0, 0, PRINT_W, PRINT_H);
        ctx.textBaseline = 'alphabetic';
        if (kind === 'left') {
          ctx.textAlign = 'left'; ctx.font = '600 112px "PingFang SC", "Microsoft YaHei", sans-serif';
          const ink = ctx.createLinearGradient(138, 0, 582, 0);
          ink.addColorStop(0, '#344bcb'); ink.addColorStop(1, '#277e9e'); ctx.fillStyle = ink;
          const swap = lastState.transition;
          ctx.save(); ctx.beginPath(); ctx.rect(110, 350, 550, 175); ctx.clip();
          if (swap) {
            const t = Math.max(0, Math.min(1, swap.progress));
            const exit = Math.min(1, t / .58), enter = Math.max(0, (t - .2) / .8);
            ctx.globalAlpha = (1 - exit) ** 2;
            ctx.fillText(TOPICS[lastState.topic], 138, 490 - 56 * exit);
            ctx.globalAlpha = 1 - (1 - enter) ** 3;
            ctx.fillText(TOPICS[swap.next], 138, 490 + 64 * (1 - enter) ** 3);
          } else ctx.fillText(TOPICS[lastState.topic], 138, 490);
          ctx.restore();
          if (images.endorsement) {
            // Preserve the generated art and alpha. Account for its transparent margins optically.
            ctx.drawImage(images.endorsement, 59, 495, 551, 551 / 3);
          } else {
            // Readable fallback while the optional local brand print loads.
            ctx.fillStyle = '#26374e'; ctx.font = '500 94px "PingFang SC", "Microsoft YaHei", sans-serif';
            ctx.fillText('就用', 138, 625);
            ctx.strokeStyle = '#425be2'; ctx.lineWidth = 8; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
            ctx.beginPath(); ctx.moveTo(375, 594); ctx.lineTo(507, 594);
            ctx.moveTo(478, 568); ctx.lineTo(507, 594); ctx.lineTo(478, 620); ctx.stroke();
          }
        } else if (kind === 'brand') {
          if (images.brand) ctx.drawImage(images.brand, 304, 320, 160, 160);
          ctx.textAlign = 'center'; ctx.fillStyle = '#263b5a';
          ctx.font = '600 116px "PingFang SC", "Microsoft YaHei", sans-serif'; ctx.fillText('研序', 384, 625);
        }
        texture.needsUpdate = true;
      }
      prints.push(repaint); repaint(); return { texture, repaint };
    }
    const paper = (map, side = THREE.FrontSide) => {
      const value = mat(new THREE.MeshStandardMaterial({ map, color: 0xffffff, roughness: .91, metalness: 0, side }));
      paperMaterials.push(value); return value;
    };
    const coverMaterial = mat(new THREE.MeshStandardMaterial({ color: 0xcbd3df, roughness: .8, metalness: .03 }));
    const edgeCanvas = document.createElement('canvas'); edgeCanvas.width = 256; edgeCanvas.height = 128;
    const edgeContext = edgeCanvas.getContext('2d');
    if (!edgeContext) throw new Error('Paper edge print unavailable');
    edgeContext.fillStyle = '#e9eaE7'; edgeContext.fillRect(0, 0, 256, 128);
    for (let row = 1; row < 128; row += 3) {
      edgeContext.fillStyle = row % 16 === 1 ? 'rgba(85,76,62,.09)' : 'rgba(85,76,62,.035)';
      edgeContext.fillRect(0, row, 256, .65);
    }
    const edgeTexture = new THREE.CanvasTexture(edgeCanvas); edgeTexture.colorSpace = THREE.SRGBColorSpace; textures.add(edgeTexture);
    const blockMaterial = mat(new THREE.MeshStandardMaterial({ map: edgeTexture, roughness: .96, metalness: 0 }));
    function roundedCover(width, height) {
      const r = .048, x = -width / 2, y = -height / 2;
      const shape = new THREE.Shape();
      shape.moveTo(x + r, y); shape.lineTo(x + width - r, y); shape.quadraticCurveTo(x + width, y, x + width, y + r);
      shape.lineTo(x + width, y + height - r); shape.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
      shape.lineTo(x + r, y + height); shape.quadraticCurveTo(x, y + height, x, y + height - r);
      shape.lineTo(x, y + r); shape.quadraticCurveTo(x, y, x + r, y);
      return geo(new THREE.ExtrudeGeometry(shape, { depth: .03, bevelEnabled: true, bevelSize: .008, bevelThickness: .006, bevelSegments: 3, steps: 1, curveSegments: 8 }));
    }
    const hitMeshes = [];
    for (const side of [-1, 1]) {
      const cover = new THREE.Mesh(roundedCover(PAGE_W + .065, PAGE_H + .075), coverMaterial);
      cover.position.set(side * (PAGE_W / 2 + .002), 0, -.11); cover.castShadow = true; book.add(cover); hitMeshes.push(cover);
      // Fine page layers are printed in the edge map, not subpixel ridges that alias on phones.
      const binding = createPaperBlock({ width: PAGE_W, height: PAGE_H, side, layers: 1 });
      const bindingGeometry = geo(new THREE.BufferGeometry());
      bindingGeometry.setAttribute('position', new THREE.BufferAttribute(binding.positions, 3));
      bindingGeometry.setAttribute('uv', new THREE.BufferAttribute(binding.uvs, 2));
      bindingGeometry.setIndex(new THREE.BufferAttribute(binding.indices, 1)); bindingGeometry.computeVertexNormals();
      const block = new THREE.Mesh(bindingGeometry, blockMaterial);
      block.castShadow = true; block.receiveShadow = true; book.add(block);
    }
    const spine = new THREE.Mesh(geo(new THREE.CylinderGeometry(.04, .04, PAGE_H + .025, 24)), coverMaterial);
    spine.scale.x = 2.05; spine.position.z = -.07; book.add(spine);
    // A soft contact pool grounds the book without a hard rectangular backdrop.
    const shadowCanvas = document.createElement('canvas'); shadowCanvas.width = shadowCanvas.height = 128;
    const shadowContext = shadowCanvas.getContext('2d');
    if (shadowContext) {
      const gradient = shadowContext.createRadialGradient(64, 64, 5, 64, 64, 64);
      gradient.addColorStop(0, 'rgba(3,8,18,.18)');
      gradient.addColorStop(.5, 'rgba(3,8,18,.08)');
      gradient.addColorStop(1, 'rgba(3,8,18,0)');
      shadowContext.fillStyle = gradient; shadowContext.fillRect(0, 0, 128, 128);
      const shadowTexture = new THREE.CanvasTexture(shadowCanvas); shadowTexture.colorSpace = THREE.SRGBColorSpace; textures.add(shadowTexture);
      const pool = new THREE.Mesh(geo(new THREE.PlaneGeometry(4.4, 2.9)), mat(new THREE.MeshBasicMaterial({ map: shadowTexture, transparent: true, depthWrite: false, toneMapped: false })));
      pool.position.set(.07, -.2, -.82); scene.add(pool);
    }

    function surface(frontTexture, backTexture, baseZ) {
      const data = createPageSurface({ width: PAGE_W, height: PAGE_H, cols: 48, rows: 12, bend: .45, cornerCurl: .055 });
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
          a[index + 2] += pageRestRelief(u, v, progress, hover);
        }
        geometry.attributes.position.needsUpdate = true; geometry.computeVertexNormals();
      } };
    }
    // A left-side page must expose its back after folding, so use a mirrored back map.
    const leftPrint = print('left', true), rightPrint = print('brand');
    const leftBaseBack = surface(null, leftPrint.texture, .005);
    leftBaseBack.set(1);
    const rightBase = surface(rightPrint.texture, null, .003); rightBase.set(0);
    const raycaster = new THREE.Raycaster(), pointer = new THREE.Vector2();
    function render(state = lastState) {
      if (disposed) return;
      const textChanged = state.topic !== lastState.topic || state.transition?.progress !== lastState.transition?.progress;
      lastState = state;
      book.rotation.set(-.18 + state.pitch, -.09 + state.yaw, .035);
      if (textChanged) leftPrint.repaint();
      renderer.render(scene, camera);
    }
    function resize() {
      if (disposed) return;
      const width = Math.max(1, host.clientWidth), height = Math.max(1, host.clientHeight);
      const ratio = Math.min(window.devicePixelRatio || 1, 1.7, Math.sqrt(1500000 / (width * height)));
      renderer.setPixelRatio(ratio); renderer.setSize(width, height, false);
      camera.aspect = width / height;
      const viewHeight = Math.max(2.8, 3.65 / camera.aspect);
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
      const load = () => {
        if (disposed) return; images[name] = image;
        if (name === 'paper') {
          const relief = new THREE.Texture(image); relief.needsUpdate = true; textures.add(relief);
          relief.anisotropy = Math.min(4, renderer.capabilities.getMaxAnisotropy());
          paperMaterials.forEach(material => { material.bumpMap = relief; material.bumpScale = .002; material.needsUpdate = true; });
        }
        prints.forEach((repaint) => repaint()); onInvalidate();
      };
      const error = () => { if (!disposed) onInvalidate(); };
      pendingImages.push({ image, load, error }); image.addEventListener('load', load); image.addEventListener('error', error); image.src = url;
    });
    resize();
    return { render, resize, pick, dispose, revision: THREE.REVISION };
  } catch (error) { dispose(); throw error; }
}
