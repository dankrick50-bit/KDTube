# KDTube for the PC (Windows, Linux)

The Android app can't run on a PC, so this is a separate, small program: a window around YouTube in
the form it has on a TV (made for arrow keys and a big screen), plus what KDTube adds to it.

| | |
|---|---|
| `app/main.js` | The window, the two layers (interface of the app below, YouTube above), keys, permissions. |
| `app/tube-preload.js`, `app/inject/tube.js` | What runs inside of the YouTube page: adverts taken out of the answers of the server, sponsored parts skipped, capability questions answered properly (this is what unlocks 1440p / 4K), best resolution for the window, a finger drag turned into arrow keys. |
| `app/inject/enhance.js` | Picture & sound for a `<video>`: sharpen, volume levelling, dialogue boost, wide stereo. Same numbers as the Android app. Used by the YouTube page and by the player of Device media. |
| `app/ui/` | The interface of the app: bar, settings panel, Device media and its player. |
| `app/library.js` | Finds the video and music files of the computer. |
| `app/memory.js` | The memory keeper: session cache on the disk (emptied on exit), buffer limits by the RAM of the computer, hardware decoding and drawing. |
| `app/settings.js` | The settings file (`kdtube-settings.json` in the data folder of the app). |
| `app/cast.js` | Casting: finds the screens of the home network (DIAL search for TVs / Fire TV, mDNS for Chromecast-like devices), links others by TV code, starts the video through YouTube's remote control service. |
| `app/updater.js` | Updates over the air: at the start it reads `kdtube-pc.json` of the release "latest", offers a newer version with what's new, downloads and checks it (sha256), then runs the setup with `/silent` (Windows) or opens the `.deb` (Ubuntu). `--kd-update-url=<address>` points it at another update file, also in a run from the sources. |
| `app/castv2.js` | The cast channel of Chromecast / Google TV / Android TV: asks for the YouTube app, starts it, reads its screen id. |

## Run it from the sources

Unpack an Electron release (the version is in `packaging/desktop/build-desktop.js`) and start it with
the folder of the app:

    electron desktop/app

Started from the terminal of an editor that is itself built on Electron, unset `ELECTRON_RUN_AS_NODE` first.

Options for testing: `--kd-shot=<file.png>` saves pictures of both layers and what the player reports
after `--kd-shot-delay=<ms>`, then exits; `--kd-keys=Down,Enter,wait2000,...` presses keys in the page;
`--kd-url=<address>`, `--kd-show=panel|library`, `--kd-size=1280x764`. In `--kd-keys` a finger is
`swipe:x1/y1/x2/y2` or `tap:x/y` (in units of the page). Use `--user-data-dir=<folder>`
to keep test runs away from the real profile.

## Build the installers

    node packaging/desktop/build-desktop.js --out installers

Downloads the runtime once (checked against the checksums of its publisher) and writes the Windows
setup, the `.deb` and a portable `.tar.gz`. `packaging/make-installers.sh` calls it together with the
Android part.

## Things to know

* The device identity sent to YouTube is the one of a game console (`USER_AGENT` in `main.js`). Other
  TV identities were tried and are given 720p only.
* The servers of the video (googlevideo.com) answer the TV interface without the permission headers a
  browser wants. `allowVideoServers` in `main.js` adds them, for those servers and the YouTube page only.
* The page is drawn one pixel per unit (`applyPixelScale`): the TV interface offers resolutions by the
  size of its page, and on a scaled 4K display it would never see more than 1440 lines otherwise.
* Protection (Widevine). A player that says it can play protected video may be sent the protected
  copy of an ordinary video too, and without a licensed module that ends in "This video format is not
  supported". So the page is told there's no protection at all (`settings.protection` in
  `inject/tube.js`): ordinary videos come unprotected, films that are sold or rented don't play.
  A runtime with the module built in was tried: YouTube refuses it a licence unless the build is
  signed by the vendor of that runtime. On Android the module works, but caps the picture at 720p.
* Touch. The TV interface moves with the arrow keys and has no scrolling of its own, so the drag of a
  finger (touch screen or pen) is turned into those keys (`touchScroll` in `inject/tube.js`): one key for
  the length of the item that has the focus, in the direction that makes the list follow the finger, and a
  few more after a quick flick. A tap is a click for the page. `touch-action: none` keeps the engine from
  taking a drag for itself; it is a style sheet made in script, because the content policy of the page
  refuses a `<style>` element. "Scroll YouTube by touch" in the settings switches it off.
* What the player does (offers of the server, source buffers, errors, frames) goes to
  `kdtube-player.log` in the data folder.
* Casting. Fire TV answers the question about its YouTube app only with `Origin: https://www.youtube.com`
  (403 otherwise). Google TV / Android TV have no YouTube among their DIAL apps (404): they are asked
  through the cast channel (`castv2.js`). `--kd-cast-test=1` lists what the search finds,
  `--kd-cast-code=<TV code>` sends what plays to the screen with that code. `KD_CAST_DEBUG=1` prints
  why a device was left out.
* The engine has no decoders for Dolby and DTS sound: such files are handed to the player of the system.
