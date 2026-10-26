#!/bin/bash
# Intercepts patchelf calls from linuxdeploy to protect the Node.js sidecar binary
# from having its byte offsets corrupted by ELF modifications.

# Check if any argument targets the sidecar
IS_SIDECAR=false
for arg in "$@"; do
  if [[ "$arg" == *"open-sime-server"* ]]; then
    IS_SIDECAR=true
    break
  fi
done

# Resolve real patchelf binary location
REAL_PATCHELF=""
if [ -x /usr/bin/patchelf.real ]; then
  REAL_PATCHELF="/usr/bin/patchelf.real"
elif [ -x /usr/local/bin/patchelf.real ]; then
  REAL_PATCHELF="/usr/local/bin/patchelf.real"
else
  SELF="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
  REAL_PATCHELF=$(which -a patchelf 2>/dev/null | grep -F -v "$SELF" | head -n 1)
  if [ -z "$REAL_PATCHELF" ] && [ -x /usr/bin/patchelf ] && [ "$SELF" != "/usr/bin/patchelf" ]; then
    REAL_PATCHELF="/usr/bin/patchelf"
  fi
fi

if [ "$IS_SIDECAR" = true ]; then
  # Allow read-only inspect operations (--print-rpath, --print-needed) to pass through
  # so linuxdeploy dependency analysis succeeds without corrupting the binary.
  IS_READONLY=false
  for arg in "$@"; do
    case "$arg" in
      --print-*)
        IS_READONLY=true
        break
        ;;
    esac
  done

  if [ "$IS_READONLY" = true ] && [ -n "$REAL_PATCHELF" ] && [ -x "$REAL_PATCHELF" ]; then
    exec "$REAL_PATCHELF" "$@"
  fi

  # Skip any modifying operation (--set-rpath, --replace-needed, etc.)
  echo "[patchelf shim] Skipping patchelf for sidecar: $*" >&2
  exit 0
fi

if [ -n "$REAL_PATCHELF" ] && [ -x "$REAL_PATCHELF" ]; then
  exec "$REAL_PATCHELF" "$@"
fi

exit 0
