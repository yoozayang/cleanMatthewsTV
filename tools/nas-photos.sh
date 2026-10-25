#!/usr/bin/env bash
# Connect the launcher's NAS photo frame to a WebDAV share (this fork only; see tools/nas-photos.md).
#
# Usage: TV_HOST=<tv ip> tools/nas-photos.sh <share url> <user>    connect, check, and switch the frame on
#        TV_HOST=<tv ip> tools/nas-photos.sh --off                 switch the frame off, keep the connection
#        TV_HOST=<tv ip> tools/nas-photos.sh --clear               forget the connection on the TV
#
#   <share url>  the photo share over WebDAV, e.g. https://192.168.1.50:5001/Photos
#   <user>       a NAS account with read-only access to that share and nothing else
#
# The password comes from the macOS keychain (service "mitv-nas", account <user>; add it once with
# `security add-generic-password -s mitv-nas -a <user> -w`), or from NAS_PASSWORD, or a prompt. It is never
# written to the repo. For https the NAS certificate's SHA-256 is read now and pinned on the TV, so a
# self-signed NAS certificate works and no other server is trusted in its place.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
: "${TV_HOST:?set TV_HOST to the IP address of the TV}"
RECEIVER=com.gathin.tvhome/.NasPhotoConfig

send() {
  # Every value is single-quoted for the TV's shell, so a password with spaces or quotes arrives intact.
  local cmd="am broadcast -n $RECEIVER" q sq="'"
  while [ $# -gt 0 ]; do
    q=${3//$sq/$sq\\$sq$sq}
    cmd="$cmd $1 $2 $sq$q$sq"
    shift 3
  done
  "$PY" -I tools/tv.py "$cmd"
}

case "${1:-}" in
  --off) send --ez enabled false; exit 0 ;;
  --clear) send --ez clear true; exit 0 ;;
  ''|-h|--help) sed -n '2,15p' "$0"; exit 0 ;;
esac

URL="${1%/}"
USER_NAME="${2:?usage: nas-photos.sh <share url> <user>}"
SERVICE="${NAS_KEYCHAIN_SERVICE:-mitv-nas}"
PASSWORD="${NAS_PASSWORD:-}"
if [ -z "$PASSWORD" ] && command -v security >/dev/null 2>&1; then
  PASSWORD=$(security find-generic-password -s "$SERVICE" -a "$USER_NAME" -w 2>/dev/null || true)
fi
if [ -z "$PASSWORD" ]; then
  read -r -s -p "Password for $USER_NAME: " PASSWORD
  echo
fi

PIN=""
case "$URL" in
  https://*)
    HOSTPORT=$(printf '%s' "$URL" | sed -E 's#^https://([^/]+).*#\1#')
    case "$HOSTPORT" in *:*) ;; *) HOSTPORT="$HOSTPORT:443" ;; esac
    PIN=$(openssl s_client -connect "$HOSTPORT" </dev/null 2>/dev/null \
      | openssl x509 -outform DER 2>/dev/null | shasum -a 256 | awk '{print $1}')
    [ ${#PIN} -eq 64 ] || { echo "could not read the NAS certificate from $HOSTPORT" >&2; exit 1; }
    echo "NAS certificate SHA-256: $PIN"
    ;;
  http://*) echo "Note: plain http sends the password unencrypted on your network; https is better." >&2 ;;
  *) echo "the share url starts with https:// or http://" >&2; exit 1 ;;
esac

# The TV fetches <share>/.tvphotos/index.txt.gz first; check it here, where an error is easy to read.
INDEX_URL="$URL/.tvphotos/index.txt.gz"
CODE=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 20 -u "$USER_NAME:$PASSWORD" "$INDEX_URL" || true)
case "$CODE" in
  200) echo "The photo list is readable as $USER_NAME." ;;
  401) echo "The NAS refused $USER_NAME's password (HTTP 401)." >&2; exit 1 ;;
  403) echo "$USER_NAME may not read the share (HTTP 403): give it read access to the photo folder." >&2; exit 1 ;;
  404) echo "No photo list at $INDEX_URL (HTTP 404): run tools/nas-photo-index.sh on the NAS first." >&2; exit 1 ;;
  *) echo "Could not fetch $INDEX_URL (HTTP $CODE): is WebDAV on, and is the url right?" >&2; exit 1 ;;
esac

send --es url "$URL" --es user "$USER_NAME" --es password "$PASSWORD" --es pin "$PIN" --ez enabled true
echo "Done. Press Home on the remote: the frame starts within a few seconds."
