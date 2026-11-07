#!/usr/bin/env bash
set -euo pipefail
OUT=${1:-runtime}
ROOT=$(cd "$OUT" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# PRoot and its Android-native shared libraries are taken from the official
# Termux ARM64 repository. Keep versions and hashes pinned for reproducibility.
BASE=https://packages.termux.dev/apt/termux-main
fetch_deb() {
  local rel="$1" sha="$2"
  local dest="$WORK/$(basename "$rel")"
  python3 - "$BASE/$rel" "$dest" <<'PY'
import sys, urllib.request
urllib.request.urlretrieve(sys.argv[1], sys.argv[2])
PY
  echo "$sha  $dest" | sha256sum -c -
  dpkg-deb -x "$dest" "$WORK/extract"
}
fetch_deb pool/main/p/proot/proot_5.1.107.102_aarch64.deb 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030
fetch_deb pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
fetch_deb pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
TERMUX="$WORK/extract/data/data/com.termux/files/usr"
mkdir -p "$ROOT/bin" "$ROOT/lib" "$ROOT/libexec/proot" "$ROOT/rootfs"
install -m 0755 "$TERMUX/bin/proot" "$ROOT/bin/proot"
install -m 0755 "$TERMUX/libexec/proot/loader" "$ROOT/libexec/proot/loader"
if [[ -f "$TERMUX/libexec/proot/loader32" ]]; then install -m 0755 "$TERMUX/libexec/proot/loader32" "$ROOT/libexec/proot/loader32"; fi
cp -a "$TERMUX"/lib/libandroid-shmem.so* "$ROOT/lib/"
cp -a "$TERMUX"/lib/libtalloc.so* "$ROOT/lib/"
install -m 0755 "$ROOT/rootfs/usr/local/bin/node" "$ROOT/bin/node"

# Fail early if the exported image is missing the exact DSH runtime layout.
for f in usr/local/bin/node usr/local/bin/dsh opt/dsh-template/profiles/web opt/ds-bot/docker.patch.yml; do
  [[ -e "$ROOT/rootfs/$f" ]] || { echo "Missing image path: $f" >&2; exit 1; }
done
cat > "$ROOT/runtime.json" <<'JSON'
{
  "version": "0.1.0",
  "abi": "arm64-v8a",
  "archive": "tar.gz",
  "runtime": "DSH web profile with ds-bot",
  "proot": "Termux proot 5.1.107.102"
}
JSON
mkdir -p dist
# tar preserves Linux modes, symlinks, hard links and ownership metadata.
tar --numeric-owner --xattrs -czpf dist/ds-bot-linux-arm64.tar.gz -C "$ROOT" bin lib libexec rootfs runtime.json
sha256sum dist/ds-bot-linux-arm64.tar.gz > dist/ds-bot-linux-arm64.tar.gz.sha256
