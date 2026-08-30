#!/usr/bin/env bash
# Prepare every runtime file required by the Android PRoot sandbox.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

TERMUX_BASE="https://packages.termux.dev/apt/termux-main"
PROOT_REL="pool/main/p/proot/proot_5.1.107.92_aarch64.deb"
PROOT_SHA256="1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9"
TALLOC_REL="pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
TALLOC_SHA256="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
SHMEM_REL="pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
SHMEM_SHA256="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"

mkdir -p "$ASSETS_DIR" "$JNILIBS_DIR"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

download_checked() {
    local url="$1" expected="$2" output="$3"
    curl -fSL --retry 3 -o "$output" "$url"
    local actual
    actual="$(shasum -a 256 "$output" | awk '{print $1}')"
    if [ "$actual" != "$expected" ]; then
        echo "Checksum mismatch for $url" >&2
        echo "expected=$expected actual=$actual" >&2
        exit 1
    fi
}

extract_deb() {
    local deb="$1" output="$2"
    mkdir -p "$output"
    if tar --version 2>&1 | grep -qi 'bsdtar'; then
        tar xf "$deb" -C "$output"
        tar xf "$output/data.tar.xz" -C "$output"
        return
    fi
    if command -v ar >/dev/null 2>&1 && command -v xz >/dev/null 2>&1; then
        (cd "$output" && ar x "$deb" && tar xf data.tar.xz)
        return
    fi

    local seven_zip=""
    if command -v 7z >/dev/null 2>&1; then
        seven_zip="$(command -v 7z)"
    elif [ -x "/c/Program Files/7-Zip/7z.exe" ]; then
        seven_zip="/c/Program Files/7-Zip/7z.exe"
    fi
    if [ -n "$seven_zip" ]; then
        "$seven_zip" x -y "-o$output" "$deb" >/dev/null
        mkdir -p "$output/data-archive" "$output/data"
        "$seven_zip" x -y "-o$output/data-archive" "$output/data.tar.xz" >/dev/null
        "$seven_zip" x -y "-o$output/data" "$output/data-archive/data.tar" >/dev/null
        return
    fi

    echo "Cannot extract .deb: install bsdtar, ar+xz, or 7-Zip." >&2
    exit 1
}

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
if [ ! -s "$ROOTFS_FILE" ]; then
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    curl -fSL --retry 3 -o "$ROOTFS_FILE" "$ALPINE_URL"
fi

download_checked "$TERMUX_BASE/$PROOT_REL" "$PROOT_SHA256" "$WORK_DIR/proot.deb"
download_checked "$TERMUX_BASE/$TALLOC_REL" "$TALLOC_SHA256" "$WORK_DIR/talloc.deb"
download_checked "$TERMUX_BASE/$SHMEM_REL" "$SHMEM_SHA256" "$WORK_DIR/shmem.deb"
extract_deb "$WORK_DIR/proot.deb" "$WORK_DIR/proot"
extract_deb "$WORK_DIR/talloc.deb" "$WORK_DIR/talloc"
extract_deb "$WORK_DIR/shmem.deb" "$WORK_DIR/shmem"

TERMUX_PREFIX="data/data/com.termux/files/usr"
PROOT_SOURCE="$WORK_DIR/proot/$TERMUX_PREFIX/bin/proot"
LOADER_SOURCE="$WORK_DIR/proot/$TERMUX_PREFIX/libexec/proot/loader"
LOADER32_SOURCE="$WORK_DIR/proot/$TERMUX_PREFIX/libexec/proot/loader32"
TALLOC_SOURCE="$WORK_DIR/talloc/$TERMUX_PREFIX/lib/libtalloc.so.2.4.3"
SHMEM_SOURCE="$WORK_DIR/shmem/$TERMUX_PREFIX/lib/libandroid-shmem.so"

install -m 0755 "$PROOT_SOURCE" "$ASSETS_DIR/proot-aarch64"
install -m 0755 "$PROOT_SOURCE" "$JNILIBS_DIR/libproot.so"
install -m 0755 "$LOADER_SOURCE" "$JNILIBS_DIR/libproot-loader.so"
install -m 0755 "$LOADER32_SOURCE" "$JNILIBS_DIR/libproot-loader32.so"
install -m 0644 "$TALLOC_SOURCE" "$JNILIBS_DIR/libtalloc.so"
install -m 0644 "$SHMEM_SOURCE" "$JNILIBS_DIR/libandroid-shmem.so"

# Termux names this dependency libtalloc.so.2. Android's jniLibs packager only
# accepts names ending in .so, so keep the byte length of the ELF string table
# entry but terminate it after libtalloc.so. Validate that exactly one entry was
# replaced before allowing the artifact into the APK.
perl -0777 -i -pe '
    BEGIN { $count = 0 }
    $count += s/libtalloc[.]so[.]2\x00/libtalloc.so\x00\x00/g;
    END { die "Expected one libtalloc dependency entry, replaced $count\n" unless $count == 1 }
' "$JNILIBS_DIR/libproot.so"
install -m 0755 "$JNILIBS_DIR/libproot.so" "$ASSETS_DIR/proot-aarch64"

echo "Android sandbox runtime prepared:"
for file in \
    "$ROOTFS_FILE" \
    "$ASSETS_DIR/proot-aarch64" \
    "$JNILIBS_DIR/libproot.so" \
    "$JNILIBS_DIR/libproot-loader.so" \
    "$JNILIBS_DIR/libproot-loader32.so" \
    "$JNILIBS_DIR/libtalloc.so" \
    "$JNILIBS_DIR/libandroid-shmem.so"; do
    printf '  %8s  %s\n' "$(wc -c < "$file" | tr -d ' ')" "$file"
done
