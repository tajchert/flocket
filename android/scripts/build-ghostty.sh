#!/usr/bin/env bash
# Builds libghostty-vt for Android (arm64-v8a, x86_64) and vendors the result into
# android/core/terminal-ghostty. Documentation: android/docs/TERMINAL_ENGINE.md.
#
#   scripts/build-ghostty.sh            build (idempotent: skips when VERSION already matches)
#   scripts/build-ghostty.sh --force    rebuild even if up to date
#   scripts/build-ghostty.sh --verify   rebuild into a temp dir and compare against SHA256SUMS
#
# Needs: ANDROID_NDK_HOME (default: $ANDROID_HOME/ndk/$NDK_VERSION), curl, git, tar, shasum.
set -euo pipefail

# ---- pins ------------------------------------------------------------------------------------
GHOSTTY_COMMIT="83b57ac5ead868e133adb509f7ef0a4ffa3ffe01"
GHOSTTY_URL="https://github.com/ghostty-org/ghostty.git"
ZIG_VERSION="0.17.0" # = minimum_zig_version in ghostty build.zig.zon at GHOSTTY_COMMIT
NDK_VERSION="30.0.16248370"
ANDROID_API="29"
ABIS=("arm64-v8a:aarch64-linux-android" "x86_64:x86_64-linux-android")
# sha256 from https://ziglang.org/download/index.json
ZIG_SHA_aarch64_macos="b607e9b9234790a008116ae5bdb71c6243b84b9fb42a53a9e70fde41c06c536a"
ZIG_SHA_x86_64_macos="4f9a1c5269aa17ebda5e6d3c2b89d6cbf36f7d2b22a0306e9ab98f25f95529c6"
ZIG_SHA_x86_64_linux="1cbe9df9f27e6b78d14ccbca43b6703a404ef79ef1c463de901d7f088d4e2026"
# -----------------------------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/../core/terminal-ghostty" && pwd)"
PATCH_DIR="${SCRIPT_DIR}/ghostty/patches"
VENDOR_DIR="${MODULE_DIR}/third_party/ghostty-vt"
CACHE="${FLOCKET_CACHE:-${HOME}/.cache/flocket}"
SRC_DIR="${CACHE}/ghostty/${GHOSTTY_COMMIT}"

log() { printf '[build-ghostty] %s\n' "$*"; }
die() { printf '[build-ghostty] error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"; }

MODE=build
case "${1:-}" in
  "") ;;
  --force) MODE=force ;;
  --verify) MODE=verify ;;
  -h | --help) sed -n '2,9p' "$0"; exit 0 ;;
  *) die "unknown argument: $1" ;;
esac

need curl; need git; need tar; need shasum

# ---- NDK -------------------------------------------------------------------------------------
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/homebrew/share/android-commandlinetools "${HOME}/Library/Android/sdk"; do
    [[ -n "${root}" && -d "${root}/ndk/${NDK_VERSION}" ]] && ANDROID_NDK_HOME="${root}/ndk/${NDK_VERSION}" && break
  done
fi
[[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]] ||
  die "Android NDK ${NDK_VERSION} not found; set ANDROID_NDK_HOME"
export ANDROID_NDK_HOME
[[ "$(basename "${ANDROID_NDK_HOME}")" == "${NDK_VERSION}" ]] ||
  log "warning: NDK is $(basename "${ANDROID_NDK_HOME}"), pinned ${NDK_VERSION}"
STRIP="$(find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -path '*/bin/llvm-strip' -print -quit)"
[[ -x "${STRIP}" ]] || die "llvm-strip not found under ${ANDROID_NDK_HOME}"

# ---- Zig -------------------------------------------------------------------------------------
ensure_zig() {
  local os arch key dir
  case "$(uname -s)" in Darwin) os=macos ;; Linux) os=linux ;; *) die "unsupported host OS $(uname -s)" ;; esac
  case "$(uname -m)" in arm64 | aarch64) arch=aarch64 ;; x86_64) arch=x86_64 ;; *) die "unsupported host arch $(uname -m)" ;; esac
  key="${arch}_${os}"
  local var="ZIG_SHA_${key}"
  local expected="${!var:-}"
  [[ -n "${expected}" ]] || die "no pinned Zig checksum for ${arch}-${os}"
  dir="${CACHE}/zig/${ZIG_VERSION}"
  ZIG="${dir}/zig"
  if [[ -x "${ZIG}" && "$("${ZIG}" version)" == "${ZIG_VERSION}" ]]; then return; fi
  log "downloading Zig ${ZIG_VERSION} (${arch}-${os})"
  local tmp; tmp="$(mktemp -d)"
  curl -fsSL "https://ziglang.org/download/${ZIG_VERSION}/zig-${arch}-${os}-${ZIG_VERSION}.tar.xz" -o "${tmp}/zig.tar.xz"
  [[ "$(shasum -a 256 "${tmp}/zig.tar.xz" | cut -d' ' -f1)" == "${expected}" ]] || die "Zig tarball checksum mismatch"
  rm -rf "${dir}"; mkdir -p "${dir}"
  tar -xJ --strip-components=1 -C "${dir}" -f "${tmp}/zig.tar.xz"
  rm -rf "${tmp}"
  [[ "$("${ZIG}" version)" == "${ZIG_VERSION}" ]] || die "unexpected zig version"
}

# ---- Ghostty source --------------------------------------------------------------------------
ensure_source() {
  if [[ ! -d "${SRC_DIR}/.git" ]]; then
    log "fetching ghostty ${GHOSTTY_COMMIT:0:10} (shallow)"
    mkdir -p "${SRC_DIR}"
    git -C "${SRC_DIR}" init -q
    git -C "${SRC_DIR}" remote add origin "${GHOSTTY_URL}"
    git -C "${SRC_DIR}" fetch -q --depth=1 origin "${GHOSTTY_COMMIT}"
    git -C "${SRC_DIR}" checkout -q --detach FETCH_HEAD
  fi
  [[ "$(git -C "${SRC_DIR}" rev-parse HEAD)" == "${GHOSTTY_COMMIT}" ]] || die "cache at ${SRC_DIR} is not ${GHOSTTY_COMMIT}"
  git -C "${SRC_DIR}" reset -q --hard "${GHOSTTY_COMMIT}"
  git -C "${SRC_DIR}" clean -qfd
  shopt -s nullglob
  local p
  for p in "${PATCH_DIR}"/*.patch; do
    log "applying $(basename "${p}")"
    git -C "${SRC_DIR}" apply "${p}"
  done
  shopt -u nullglob
}

# The upstream SONAME is "libghostty-vt.so.0", but an APK can only ship "libghostty-vt.so" and the
# Android loader resolves DT_NEEDED by name. Shorten the SONAME string in .dynstr in place
# (same offset, trailing NUL), so the JNI shim records DT_NEEDED "libghostty-vt.so".
fix_soname() {
  need python3
  python3 -I - "$1" <<'PY'
import sys
p = sys.argv[1]
b = open(p, "rb").read()
old = b"libghostty-vt.so.0\x00"
if b.count(old) != 1:
    sys.exit("expected exactly one SONAME string, found %d" % b.count(old))
open(p, "wb").write(b.replace(old, b"libghostty-vt.so\x00\x00\x00"))
PY
}

# ---- build -----------------------------------------------------------------------------------
build_all() { # $1 = output dir (receives <abi>/libghostty-vt.so and include/)
  local out="$1" entry abi target prefix
  for entry in "${ABIS[@]}"; do
    abi="${entry%%:*}"; target="${entry#*:}"
    prefix="${out}/prefix-${abi}"
    log "building ${abi} (${target}.${ANDROID_API})"
    (
      cd "${SRC_DIR}"
      export ZIG_GLOBAL_CACHE_DIR="${CACHE}/zig-global-cache"
      "${ZIG}" build -Demit-lib-vt \
        -Dtarget="${target}.${ANDROID_API}" \
        -Doptimize=ReleaseFast -Dstrip=true -Dsimd=false -Dcpu=baseline \
        --prefix "${prefix}" \
    )
    local so; so="$(find "${prefix}/lib" -maxdepth 1 -name 'libghostty-vt.so*' -type f -print -quit)"
    [[ -n "${so}" ]] || die "no libghostty-vt.so produced for ${abi}"
    mkdir -p "${out}/${abi}"
    cp "${so}" "${out}/${abi}/libghostty-vt.so"
    "${STRIP}" --strip-unneeded "${out}/${abi}/libghostty-vt.so"
    fix_soname "${out}/${abi}/libghostty-vt.so"
  done
  rm -rf "${out}/include"
  cp -R "${out}/prefix-arm64-v8a/include" "${out}/include"
}

checksums() { # $1 = dir containing <abi>/libghostty-vt.so
  (cd "$1" && for e in "${ABIS[@]}"; do shasum -a 256 "${e%%:*}/libghostty-vt.so"; done)
}

version_file() {
  printf 'ghostty_commit=%s\nzig_version=%s\nndk_version=%s\nandroid_api=%s\nbuilt=%s\n' \
    "${GHOSTTY_COMMIT}" "${ZIG_VERSION}" "$(basename "${ANDROID_NDK_HOME}")" "${ANDROID_API}" "$(date -u +%Y-%m-%d)"
}

ensure_zig
if [[ "${MODE}" == build && -f "${VENDOR_DIR}/VERSION" ]] &&
  grep -q "^ghostty_commit=${GHOSTTY_COMMIT}\$" "${VENDOR_DIR}/VERSION" &&
  grep -q "^zig_version=${ZIG_VERSION}\$" "${VENDOR_DIR}/VERSION" &&
  [[ -f "${MODULE_DIR}/src/main/jniLibs/arm64-v8a/libghostty-vt.so" && -f "${MODULE_DIR}/src/main/jniLibs/x86_64/libghostty-vt.so" ]]; then
  log "up to date (use --force to rebuild)"; exit 0
fi
ensure_source

WORK="$(mktemp -d)"; trap 'rm -rf "${WORK}"' EXIT
build_all "${WORK}"

if [[ "${MODE}" == verify ]]; then
  [[ -f "${VENDOR_DIR}/SHA256SUMS" ]] || die "no SHA256SUMS to verify against"
  if diff <(checksums "${WORK}") "${VENDOR_DIR}/SHA256SUMS" >/dev/null; then
    log "OK: rebuilt libraries are bit-for-bit identical to SHA256SUMS"
  else
    log "MISMATCH (build is not bit-for-bit reproducible, or inputs changed):"
    diff <(checksums "${WORK}") "${VENDOR_DIR}/SHA256SUMS" || true
    exit 2
  fi
  exit 0
fi

for entry in "${ABIS[@]}"; do
  abi="${entry%%:*}"
  mkdir -p "${MODULE_DIR}/src/main/jniLibs/${abi}"
  cp "${WORK}/${abi}/libghostty-vt.so" "${MODULE_DIR}/src/main/jniLibs/${abi}/libghostty-vt.so"
done
mkdir -p "${VENDOR_DIR}"
rm -rf "${VENDOR_DIR}/include"
cp -R "${WORK}/include" "${VENDOR_DIR}/include"
cp "${SRC_DIR}/LICENSE" "${VENDOR_DIR}/LICENSE"
checksums "${WORK}" > "${VENDOR_DIR}/SHA256SUMS"
version_file > "${VENDOR_DIR}/VERSION"
log "done:"; cat "${VENDOR_DIR}/SHA256SUMS"
