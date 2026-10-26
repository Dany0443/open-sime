#!/bin/bash
# Intercepts strip calls from linuxdeploy to protect the Node.js sidecar binary
# from having its byte offsets corrupted by stripping symbols.

# Check if any argument targets the sidecar
IS_SIDECAR=false
for arg in "$@"; do
  if [[ "$arg" == *"open-sime-server"* ]]; then
    IS_SIDECAR=true
    break
  fi
done

# Resolve real strip binary location
REAL_STRIP=""
if [ -x /usr/bin/strip.real ]; then
  REAL_STRIP="/usr/bin/strip.real"
elif [ -x /usr/local/bin/strip.real ]; then
  REAL_STRIP="/usr/local/bin/strip.real"
else
  SELF="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  REAL_STRIP=$(which -a strip 2>/dev/null | grep -F -v "$SELF" | head -n 1)
  if [ -z "$REAL_STRIP" ] && [ -x /usr/bin/strip ] && [ "$SELF" != "/usr/bin/strip" ]; then
    REAL_STRIP="/usr/bin/strip"
  fi
fi

if [ "$IS_SIDECAR" = true ]; then
  echo "[strip shim] Skipping strip for sidecar: $*" >&2
  exit 0
fi

if [ -n "$REAL_STRIP" ] && [ -x "$REAL_STRIP" ]; then
  exec "$REAL_STRIP" "$@"
fi

exit 0
