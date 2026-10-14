# KDTube @VERSION@ installers

KDTube exists in two forms:

* **The app for TVs, sticks, phones and tablets** (Android, Fire OS). It is its own app now: the name
  is "KDTube", the id is `com.kdtube.app`. It installs next to SmartTube instead of replacing it.
* **The PC edition** for Windows and Ubuntu: YouTube in the form it has on a TV, with the same
  picture and sound options, and a player for the video and music files of the computer.

| Folder | File | What it is |
|---|---|---|
| `Windows/` | `KDTube-windows-x64-setup-v@VERSION@-@STAMP@.exe` | The PC edition for Windows 10 / 11. Double click, Install. |
| `Ubuntu/` | `KDTube-ubuntu-amd64-v@VERSION@-@STAMP@.deb` | The PC edition for Ubuntu / Debian: `sudo apt install ./KDTube-ubuntu-amd64-v@VERSION@-@STAMP@.deb` |
| `Ubuntu/` | `KDTube-linux-x64-v@VERSION@-@STAMP@.tar.gz` | The same for other Linux systems: unpack, run `KDTube/kdtube`. |
| `Android/` | `KDTube-android-v@VERSION@-@STAMP@.apk` | The app. Works on every ARM device. `-arm64` and `-arm32` are the same app, smaller, for one processor type. |
| `Android/From-a-PC/` | `KDTube-tv-installer-windows-x64-...exe`, `KDTube-tv-installer-ubuntu-all-...deb` | Tools that send the app from a computer to a TV / stick in the network and start it there. |
| `FireOS/Fire-TV-Stick-4K/` | `KDTube-fireos-firetv-stick-4k-...apk` | The 32-bit build: the only kind Fire TV devices run. |
| `FireOS/Fire-HD-10-2023/` | `KDTube-fireos-fire-hd-10-2023-...apk` | The 64-bit build for the tablet. |
| `Updates/` | `kdtube.json`, `kdtube_*.apk` | What the app looks for when it checks for updates: see `Updates/README.txt`. |

Each folder has a `README.txt` with the steps.

## New in this build

* **Play on another screen (casting), on every system.** The cast button (PC: in the bar on top;
  Android / Fire OS: in the player) lists the screens of the home network: smart TVs, Fire TV sticks,
  Chromecast / Google TV / Android TV boxes, consoles. The video continues there from the same spot,
  played by the YouTube app of that screen. "Link with TV code" reaches every other screen with a
  YouTube app, also outside of the home network.
  The other way round: KDTube is a target too. YouTube TV interface > Settings > Link with TV code
  (Android app: Settings > Remote control), then pick it in the YouTube app of a phone.
* **The same interface everywhere.** The Android / Fire OS app has the interface of the PC edition
  as an option: Settings > YouTube TV interface (it can also be the start screen). The app's own
  interface and player stay the default: they use less memory and processor.
* **Best picture.** Both interfaces ask for the highest resolution the screen shows, not 720p.
* **Best sound.** The Android player picks the best audio track by itself: 5.1 where a surround
  system or a Dolby decoder is there, otherwise the stereo track with the highest bitrate.
  "Virtual surround" is automatic: on with headphones and two speakers, off with a surround system.
* **Films and shows that YouTube sells or rents don't play** in the PC edition and in the YouTube TV
  interface (they need a licensed protection module). Everything else plays; the PC edition writes
  what the player does to `kdtube-player.log` in its data folder, for when something doesn't.

## Good to know

* **Coming from the earlier builds** (they were called "SmartTube beta", id `org.smarttube.beta`):
  KDTube is a separate app, so the settings, the sign-in and the list of downloads don't move by
  themselves. In the old app: Settings > Backup / restore > Backup. In KDTube: the same place, Restore.
  Then remove the old app. The downloaded video files themselves stay where they are (Download/KDTube)
  and show up under "Device media".
* **Updates.** The Android app no longer follows the releases of SmartTube. It looks at
  `github.com/dankrick50-bit/KDTube` (release `latest`); until something is published there it simply
  finds no update. Updates must be signed with the same key as these files, or devices refuse them.
* **Windows** shows "Windows protected your PC" for the setups: they aren't signed with a
  certificate. "More info" > "Run anyway".
* **Android version.** The app runs on Android 4.2 and newer. Downloading videos needs Android 5.0,
  as MP4: Android 7.1.

## What was tried, and what wasn't

Tried on this computer and on Android emulators: the Android app (start, playback, downloads, the
new sound decoders, the Dolby Vision fallback, memory handling); the Windows setup (install, start,
4K playback, uninstall); the PC edition playing a search result from start to end.

Tried on a real Fire HD 10 tablet (Fire OS 8, 4 GB): the app and its player at 1080p, the YouTube
TV interface at 1080p (a whole song, 4 of 5684 frames dropped), DTS and Dolby TrueHD files through
the decoder of the app, casting from the PC to the tablet with a TV code, and the search for
screens: it found a Fire TV stick, a Google TV box and a smart TV of the home network.

Not tried: starting a video on those three screens (they were found and asked what they can do;
nothing was started on them without being asked to). A real Ubuntu machine (the packages were
built and checked for their structure on Windows). A real Fire TV stick as the device the app runs
on. Sound through a real receiver.

Made by `packaging/make-installers.sh` (see the top of that file).
