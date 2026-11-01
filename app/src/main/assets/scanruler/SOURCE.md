# ScanRuler offline bundle (vendored)

Upstream: `CNCKitchen/ScanRuler` - https://github.com/CNCKitchen/scanruler
Pinned tag: `v0.9.4`
Pinned commit: `f183e9d074a2ce95d1059174a173b8150b3b71eb`
Licence: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`),
Copyright Stefan Hermann / CNC Kitchen; the upstream `LICENSE` is staged verbatim beside
this file.

The files under `assets/`, `fonts/`, `index.html` and the icons are the
unmodified `dist/` output of the pinned upstream commit. Do not edit them: SHA256SUMS pins
every file and `scripts/verify-scanruler-assets.py` fails on any change or extra file.

## Build reproduction

```
scripts/fetch-scanruler-assets.sh
```

It clones the pinned commit into a temporary work directory (never into the app tree), runs
`npm ci` (the upstream package-lock pins all 146 dependencies) and
`npm run build -- --base=/assets/scanruler/`, then stages `dist/*` (minus `dist/samples/`,
see below) and the upstream `LICENSE`. Node 22.23.4 / npm 10.9.9 were used for this copy (the
script pins and checks them). The build is a static Vite/React bundle: no network access is
needed at runtime. The staged tree is about 28 MB; a zipped copy is about 9.4 MB, so the APK
grows by roughly that (the exact APK delta is measured by CI/release packaging).

The app serves this tree at
`https://appassets.androidplatform.net/assets/scanruler/index.html` through
`WebViewAssetLoader` with an `AssetsPathHandler` mounted on `/assets/`.
Because the build was made with `--base=/assets/scanruler/`, every asset URL in
`index.html` is already the absolute `/assets/scanruler/...` path the loader resolves -
the bundle must not be rewritten afterwards.

## Serving contract (for the WebView host)

- `WebViewAssetLoader.Builder().addPathHandler("/assets/", AssetsPathHandler(this))`
  and load `https://appassets.androidplatform.net/assets/scanruler/index.html`.
- Absolute `/assets/scanruler/...` URLs resolve through that `/assets/` handler; relative
  URLs resolve against the page's own directory. Do not use `file://` and do not rewrite
  the built URLs.
- Javascript and DOM storage enabled (`javaScriptEnabled = true`,
  `domStorageEnabled = true`); the app keeps checkpoints/settings in the browser profile
  (localStorage; the page also uses IndexedDB), so the WebView must not clear them between
  visits. No SharedArrayBuffer/COOP-COEP headers are required.
- `.wasm` files must be served as `application/wasm` (`WebAssembly.instantiateStreaming`
  rejects the wrong type). `WebViewAssetLoader` guesses MIME types from the file extension
  and may not know `.wasm`/`.woff2`/`.3mf`; if the device walk shows a wasm fetch/content
  type failure, wrap `AssetsPathHandler` in a small `PathHandler` that sets the type for
  `.wasm` -> `application/wasm`, `.js` -> `text/javascript`, `.css` ->
  `text/css`, `.json` -> `application/json`, `.woff2` -> `font/woff2`, `.svg` ->
  `image/svg+xml`, `.3mf` -> `application/octet-stream` (or `model/3mf`). The
  checks in `scripts/verify-scanruler-assets.py` prove the files and magic bytes, not the
  WebView MIME behaviour - that is the device walk's item.
- The upstream build's `dist/samples/` directory (the 4.2 MB
  `fridgeBracket_01-reduced.3mf` used only by the repo's e2e test script) is deliberately NOT
  staged: the page does not reference it, and it would cost 4.2 MB of APK. The verifier fails
  if a `samples` directory appears in the bundle. Everything the page needs at boot is here;
  the rest of the UI (file picker, download/save bridge) belongs to the host activity, not this
  asset tree.