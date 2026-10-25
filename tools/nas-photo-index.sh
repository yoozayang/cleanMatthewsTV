#!/bin/sh
# Writes the photo list the launcher's NAS photo frame reads (this fork only; see tools/nas-photos.md).
# Runs on the NAS itself, from cron, with nothing but busybox: find, sed, sort and gzip.
#
# Usage: nas-photo-index.sh <photo share folder> [min size in KB, default 100]
#        e.g. nas-photo-index.sh /share/Photos
#
# Output: <share>/.tvphotos/index.txt.gz, one share-relative path per line, sorted. Only JPEG and HEIC
# files of at least the minimum size are listed (smaller ones are thumbnails and app icons); RAW, video
# and everything else never are. QNAP's thumbnail, recycle-bin, snapshot and upload-cache folders
# (.@__thumb, @Recycle, @Recently-Snapshot, .@upload_cache), Synology's @eaDir and macOS "._" files are
# skipped. The list is written next to it and renamed into place, so the TV never reads half a file.
set -eu

ROOT="${1:?usage: nas-photo-index.sh <photo share folder> [min size in KB]}"
MIN_KB="${2:-100}"
ROOT="${ROOT%/}"
[ -d "$ROOT" ] || { echo "not a folder: $ROOT" >&2; exit 1; }
case "$MIN_KB" in ''|*[!0-9]*) echo "min size must be a number of KB" >&2; exit 1;; esac

OUT_DIR="$ROOT/.tvphotos"
mkdir -p "$OUT_DIR"
TMP="$OUT_DIR/index.txt.gz.part"
trap 'rm -f "$TMP"' EXIT
# Low priority where the NAS has nice (QuTS hero's busybox has none); the scan takes seconds either way.
NICE=""
command -v nice >/dev/null 2>&1 && NICE="nice -n 19"

{
  echo "# NAS photo frame list, $(date '+%Y-%m-%d %H:%M'), JPEG and HEIC of ${MIN_KB} KB and up"
  cd "$ROOT"
  # Folders starting with "@" or ".@" are the NAS's own (recycle bin, snapshots, thumbnails, caches).
  find . \( -name '@*' -o -name '.@*' -o -name '.tvphotos' -o -name '.streams' \) -prune -o \
    -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.heic' -o -iname '*.heif' \) \
    ! -name '._*' -size +"${MIN_KB}"k -print 2>/dev/null \
    | sed 's|^\./||' | LC_ALL=C sort
} | $NICE gzip -6 > "$TMP"

chmod 644 "$TMP"
mv -f "$TMP" "$OUT_DIR/index.txt.gz"
trap - EXIT
echo "$(gzip -dc "$OUT_DIR/index.txt.gz" | grep -vc '^#') photos listed in $OUT_DIR/index.txt.gz"
