#!/bin/bash
# Intercepts patchelf calls from linuxdeploy to protect the Node.js sidecar binary
# from having its byte offsets corrupted.
TARGET="${@: -1}"
if [[ "$TARGET" == *"open-sime-server"* ]]; then
  echo "[patchelf shim] Skipping patchelf for sidecar: $TARGET"
  exit 0
fi

SELF="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
REAL_PATCHELF=$(which -a patchelf 2>/dev/null | grep -F -v "$SELF" | head -n 1)
if [ -z "$REAL_PATCHELF" ] && [ -x /usr/bin/patchelf ] && [ "$SELF" != "/usr/bin/patchelf" ]; then
  REAL_PATCHELF="/usr/bin/patchelf"
fi

if [ -n "$REAL_PATCHELF" ] && [ -x "$REAL_PATCHELF" ]; then
  exec "$REAL_PATCHELF" "$@"
fi

exit 0
