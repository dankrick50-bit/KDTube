"use strict";
// The settings of the desktop edition: one json file in the data folder of the app.
const fs = require("fs");
const path = require("path");

const DEFAULTS = {
  // Skipping
  adblock: true,
  sponsorblock: true,
  sponsorCategories: ["sponsor", "selfpromo", "interaction"],
  // Picture
  bestQuality: true,
  sharpen: "off", // off | light | strong
  // Sound
  levelling: "off", // off | on | night
  dialogue: false,
  wide: false,
  // System
  hardwareAcceleration: true,
  startFullscreen: false,
  touchScroll: true,
  updateCheck: true,
  // Device media: the folders the user added, and whether all the drives are searched
  folders: [],
  allDrives: false,
  window: null,
};

let file = null;
let values = Object.assign({}, DEFAULTS);

function load(app) {
  file = path.join(app.getPath("userData"), "kdtube-settings.json");
  try {
    const stored = JSON.parse(fs.readFileSync(file, "utf8"));
    for (const key of Object.keys(DEFAULTS)) {
      if (stored[key] !== undefined && typeof stored[key] === typeof DEFAULTS[key]) values[key] = stored[key];
      // null is the default of 'window': anything goes
      if (DEFAULTS[key] === null && stored[key]) values[key] = stored[key];
    }
  } catch (e) {
    // the first start, or a broken file: the defaults
  }
  return values;
}

function save() {
  if (!file) return;
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file + ".tmp", JSON.stringify(values, null, 2));
    fs.renameSync(file + ".tmp", file);
  } catch (e) {
    // a full disk shouldn't take the app down
  }
}

function get() {
  return values;
}

function set(key, value) {
  if (!(key in DEFAULTS)) return false;
  values[key] = value;
  save();
  return true;
}

/** The part a page needs: no paths of the computer in it. */
function forPage() {
  const { adblock, sponsorblock, sponsorCategories, bestQuality, sharpen, levelling, dialogue, wide, touchScroll } = values;
  return { adblock, sponsorblock, sponsorCategories, bestQuality, sharpen, levelling, dialogue, wide, touchScroll };
}

module.exports = { DEFAULTS, load, save, get, set, forPage };
