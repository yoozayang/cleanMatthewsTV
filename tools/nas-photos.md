# NAS photo frame (this fork only)

The launcher shows photos from a NAS share in the empty space between the clock and the dock: one at a
time, crossfading, with every edge faded to transparent so the photo melts into the backdrop. It is off
until a NAS is connected, and it can be switched off on the TV at any time.

This is a fork-only feature. The upstream launcher has no network access by design (AGENTS.md, "No network
in the launcher"); this fork adds the `INTERNET` permission for it, and the launcher talks to nothing but the
share set up here.

## How it works

| Where | What |
|---|---|
| NAS | [`nas-photo-index.sh`](nas-photo-index.sh) lists the share's JPEG and HEIC files into `<share>/.tvphotos/index.txt.gz`, skipping RAW, video, files under 100 KB and the NAS's own folders (`@Recycle`, `@Recently-Snapshot`, `.@__thumb`, ...). Run it from cron once a day. |
| TV | While Home is on screen, the launcher fetches that list (when it changed, at most every 6 hours), keeps it in its cache and picks a photo every interval. Each photo is downloaded once, decoded at the frame's size and deleted. Nothing runs while another app is open. |
| Computer | [`nas-photos.sh`](nas-photos.sh) writes the share's address, the account and the certificate pin to the TV over adb. The password never goes into the repo or a theme. |

## Set it up

1. **NAS:** turn WebDAV on, and make an account that can only read the photo share.
2. **NAS:** copy `tools/nas-photo-index.sh` to a folder that survives a reboot (your home folder) and run
   it once: `nas-photo-index.sh /share/<photo share>`. It prints how many photos it listed.
3. **NAS, daily:** add one line to the persistent crontab (`/etc/config/crontab` on QNAP), then reload it
   as an administrator (`crontab /etc/config/crontab`):
   `30 4 * * * /share/homes/<you>/tvphotos/nas-photo-index.sh "/share/<photo share>" >/dev/null 2>&1`
4. **Computer:** store the account's password in the keychain once:
   `security add-generic-password -s mitv-nas -a <account> -w`
5. **Computer:** `TV_HOST=<tv ip> tools/nas-photos.sh https://<nas ip>:<https port>/<photo share> <account>`.
   It checks that the list can be read before it touches the TV, and pins the NAS certificate.
6. **TV:** press Home. Then **Settings** (hold OK) → **Launcher settings** → **NAS photos**.

## On the TV

Launcher settings → **NAS photos**:

| Row | Choices |
|---|---|
| Photo frame | On / Off |
| Change every | 15 s, 30 s, 1 min, 5 min, 15 min |
| Folders | All, or any folders two levels deep, with their photo counts |
| Order | Random (each photo once before any repeats) / In order |
| JPEG, HEIC | Either can be turned off, not both |
| Folder name under the photo | On / Off |
| Hidden photos and folders | Tick any to show them again, or show all |
| Fetch the photo list again | Forgets the cached list |

**Hide a photo:** on Home, press Up from the dock to select the photo (a ring shows and it stops changing),
then OK: **Don't show this photo again**, **Don't show photos from <folder>** or **Next photo**. Up again
goes on to the top bar. The hidden list lives on the TV (`files/nas_hidden.txt`, one path per line, folders
end in `/`), so it lasts through new photo lists and launcher updates; only clearing the launcher's data
forgets it.

The frame hides while focus is down in the grid (the tiles scroll into its space) and comes back with the
dock. With the NAS asleep or unreachable it keeps the last list and retries every two minutes.

## Turn it off or remove it

- `TV_HOST=<tv ip> tools/nas-photos.sh --off` switches it off; `--clear` forgets the connection.
- Remove the cron line and the `<share>/.tvphotos` folder on the NAS.
