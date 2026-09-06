// Decode local prints as one batch, before any book material is shown.
// A missing image becomes a stable text fallback; late arrivals never repaint it.
export function loadBookArtwork(assets, { createImage = () => new Image(), timeout = 5000,
  setTimer = setTimeout, clearTimer = clearTimeout } = {}) {
  return Promise.all(Object.entries(assets).map(([name, url]) => new Promise((resolve) => {
    let image, timer, settled = false, decoding = false;
    function finish(ok) {
      if (settled) return;
      settled = true;
      clearTimer(timer);
      if (image) { image.onload = null; image.onerror = null; }
      resolve([name, ok ? image : null]);
    }
    async function loaded() {
      if (settled || decoding) return;
      decoding = true;
      try {
        if (typeof image.decode === 'function') await image.decode();
        finish(image.naturalWidth > 0 && image.naturalHeight > 0);
      } catch (_) { finish(false); }
    }
    timer = setTimer(() => finish(false), timeout);
    try {
      image = createImage();
      image.decoding = 'async';
      image.onload = loaded;
      image.onerror = () => finish(false);
      image.src = url;
      if (image.complete) {
        if (image.naturalWidth > 0) loaded();
        else finish(false);
      }
    } catch (_) { finish(false); }
  }))).then((entries) => Object.freeze(Object.fromEntries(entries.filter(([, image]) => image))));
}
