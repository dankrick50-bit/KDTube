# KDTube for Samsung TVs (Tizen)

Samsung TVs run Tizen, not Android: the Android app can't be installed there. KDTube comes to them as a
module of [TizenBrew](https://github.com/reisxd/TizenBrew), a free app that opens a website on the TV and runs
scripts in it. KDTube's module is YouTube's TV interface with the same page scripts as the PC edition and the
Android app (`desktop/app/inject/tube.js`):

* no adverts (taken out of the answers of the server before the page sees them)
* sponsored parts skipped (SponsorBlock)
* the best resolution of the screen, 4K and 8K included
* the red button of the remote: KDTube's settings

Picture and sound options aren't here: the TV does those in its own chip.

## Install (once per TV)

1. Install TizenBrew with its installer for the PC, as described in
   [its guide](https://github.com/reisxd/TizenBrew/blob/main/docs/README.md) (TV in developer mode: Apps, type
   `12345`, Developer mode on, the IP address of the PC). Works on Samsung TVs from 2017 on.
2. On the TV: TizenBrew > Module Manager > Add a GitHub module, and type

       dankrick50-bit/KDTube@kdtube/tizen

3. KDTube is in the list of TizenBrew now. Optional: make it start with TizenBrew (its settings: autolaunch).

## Updates

The module is read from this repository each time TizenBrew starts: nothing to install again.
After a change:

    node packaging/tizen/build-tizen.js          # tizen/package.json and tizen/dist/userScript.js
    git commit, git push                         # the branch kdtube
    node packaging/tizen/build-tizen.js --purge  # TVs take it at their next start (otherwise within 12 hours)

## Files

| | |
|---|---|
| `src/bridge.js` | What the page scripts talk to on the TV (`window.kdtube`): settings in the storage of the page, SponsorBlock, the size of the panel (4K / 8K), the settings panel on the red button. ES5 only: the web engine of a 2018 TV is old. |
| `dist/userScript.js` | Built: the bridge + `tube.js`. TizenBrew runs this one. |
| `package.json` | Built: what TizenBrew reads (`packageType: mods`, `websiteURL`, the keys of the remote it registers). |

## Things to know

* If 4K isn't offered on a 4K TV: red button > Screen > 4K. "Auto" asks the TV, and not every model answers.
* Protected videos (films that are sold or rented) play: the TV has the modules for them.
* Checked by loading YouTube's TV page with the identity of a Samsung TV and the module injected the way
  TizenBrew does it. Not run on a Samsung TV yet.
