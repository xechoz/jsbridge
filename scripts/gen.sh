#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="$ROOT/proto"
DESC="$ROOT/build/generated/proto/demo.desc"
KOTLIN_STUB_OUT="$ROOT/demo/src/main/kotlin"
TS_OUT="$ROOT/web/src/generated"
MANIFEST="$ROOT/build/generated/jsbridge-manifest.json"
WIRE_OUT="$ROOT/demo/build/generated/source/wire"
MODE="${1:-generate}"

PROTOC="${PROTOC:-protoc}"
PLUGIN="$ROOT/web/node_modules/.bin/protoc-gen-ts_proto"

if [[ ! -x "$PLUGIN" ]]; then
  echo "missing $PLUGIN; run 'npm install' in $ROOT/web" >&2
  exit 1
fi

mkdir -p "$(dirname "$DESC")"
"$PROTOC" -I "$PROTO_DIR" --include_imports --descriptor_set_out="$DESC" "$PROTO_DIR/demo.proto"

"$ROOT/gradlew" -q :demo:generateDebugProtos
"$ROOT/gradlew" -q :codegen:installDist
CODEGEN="$ROOT/codegen/build/install/codegen/bin/codegen"

run_gen() {
  local kotlin_out="$1"
  local ts_out="$2"
  local manifest="$3"

  mkdir -p "$kotlin_out" "$ts_out" "$(dirname "$manifest")"

  "$PROTOC" -I "$PROTO_DIR" \
    --plugin=protoc-gen-ts_proto="$PLUGIN" \
    --ts_proto_out="$ts_out" \
    --ts_proto_opt=esModuleInterop=true,outputServices=false,outputJsonMethods=false,outputPartialMethods=true,useOptionals=messages \
    "$PROTO_DIR/demo.proto"

  "$CODEGEN" \
    --descriptor "$DESC" \
    --kotlin-out "$kotlin_out" \
    --ts-out "$ts_out" \
    --manifest-out "$manifest" \
    --bridge-package me.xechoz.jsbridge \
    --bridge-import ../bridge
}

if [[ "$MODE" == "--check" ]]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  run_gen "$TMP/kotlin" "$TMP/ts" "$TMP/manifest.json"
  diff -ru "$ROOT/demo/src/main/kotlin/me/xechoz/jsbridge/demo/proto/bridge" "$TMP/kotlin/me/xechoz/jsbridge/demo/proto/bridge"
  diff -ru "$ROOT/web/src/generated" "$TMP/ts"
  echo "jsbridge generated code is up to date"
else
  run_gen "$KOTLIN_STUB_OUT" "$TS_OUT" "$MANIFEST"
  echo "generated:"
  echo "  wire:         $WIRE_OUT"
  echo "  kotlin stubs: $KOTLIN_STUB_OUT/me/xechoz/jsbridge/demo/proto/bridge"
  echo "  ts:           $TS_OUT"
  echo "  manifest:     $MANIFEST"
fi
