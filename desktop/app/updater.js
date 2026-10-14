"use strict";
// Updates over the air, the same way as the Android app: at the start the app looks at the update file of
// the release "latest" (packaging/publish-update.js puts it there). A newer version is offered with what's new
// in it; "Install now" downloads the setup, checks it against the checksum of the update file, and:
//   Windows: runs the setup without its window (/silent): it closes KDTube, installs, and starts it again.
//   Linux:   hands the .deb to the installer of the system (it asks for the password of the computer).
// Nothing happens without the "yes" of the user, and nothing at all in a run from the sources.
const { app, dialog, net, shell } = require("electron");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const UPDATE_URL = "https://github.com/dankrick50-bit/KDTube/releases/download/latest/kdtube-pc.json";
// The app shows itself first: the question comes a moment later
const START_DELAY_MS = 8000;

/** -1, 0, 1 for "32.67.0" style versions */
function compareVersions(a, b) {
  const left = String(a).split(".").map((part) => parseInt(part, 10) || 0);
  const right = String(b).split(".").map((part) => parseInt(part, 10) || 0);
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const difference = (left[i] || 0) - (right[i] || 0);
    if (difference) return difference > 0 ? 1 : -1;
  }
  return 0;
}

/** The file of this computer in the update file: windows, or the .deb on Linux */
function pickPackage(info) {
  if (process.platform === "win32" && process.arch === "x64") return info.windows || null;
  if (process.platform === "linux" && process.arch === "x64") return info.deb || null;
  return null;
}

async function fetchInfo(url) {
  const response = await net.fetch(url, { headers: { "Cache-Control": "no-cache" } });
  if (!response.ok) throw new Error("The update file answered " + response.status);
  return response.json();
}

async function download(url, file, onProgress) {
  const response = await net.fetch(url);
  if (!response.ok || !response.body) throw new Error("The download answered " + response.status);
  const total = Number(response.headers.get("content-length")) || 0;
  const hash = crypto.createHash("sha256");
  const out = fs.createWriteStream(file + ".part");
  let received = 0;
  const reader = response.body.getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = Buffer.from(value);
      hash.update(chunk);
      received += chunk.length;
      if (!out.write(chunk)) await new Promise((resolve) => out.once("drain", resolve));
      if (onProgress && total) onProgress(received / total);
    }
  } finally {
    await new Promise((resolve) => out.end(resolve));
  }
  fs.renameSync(file + ".part", file);
  return hash.digest("hex");
}

/**
 * @param {object} options win: the window (for the dialogs and the progress in the task bar),
 *   isEnabled: () => boolean (the setting), url: another update file (a test aid), force: also in a run from the sources
 */
function start(options) {
  const url = options.url || UPDATE_URL;
  if (!app.isPackaged && !options.force) return;
  setTimeout(() => {
    if (options.isEnabled && !options.isEnabled()) return;
    check(options.win, url).catch((e) => console.log("update check: " + e.message));
  }, options.delayMs !== undefined ? options.delayMs : START_DELAY_MS);
}

async function check(win, url) {
  const info = await fetchInfo(url);
  if (!info || !info.version || compareVersions(info.version, app.getVersion()) <= 0) return;
  const pack = pickPackage(info);
  if (!pack || !pack.url || !pack.sha256) return;
  if (!win || win.isDestroyed()) return;

  const changes = Array.isArray(info.changelog) && info.changelog.length ? "\n\nWhat's new:\n" + info.changelog.map((line) => "- " + line).join("\n") : "";
  const answer = await dialog.showMessageBox(win, {
    type: "info",
    buttons: ["Install now", "Later"],
    defaultId: 0,
    cancelId: 1,
    title: "KDTube update",
    message: "KDTube " + info.version + " is available (you have " + app.getVersion() + ")",
    detail: (process.platform === "win32" ? "It downloads, installs, and KDTube starts again by itself." :
      "It downloads and opens in the installer of the system.") + changes,
  });
  if (answer.response !== 0) return;

  try {
    await install(win, pack);
  } catch (e) {
    // The user said yes: a failure is told, not just written down
    if (!win.isDestroyed()) {
      await dialog.showMessageBox(win, { type: "error", title: "KDTube update", message: "The update couldn't be installed",
        detail: (e && e.message ? e.message : String(e)) + "\n\nIt's offered again at the next start." });
    }
  }
}

async function install(win, pack) {
  const folder = path.join(app.getPath("temp"), "kdtube-update");
  fs.mkdirSync(folder, { recursive: true });
  const file = path.join(folder, path.basename(new URL(pack.url).pathname));
  let sum;
  try {
    sum = await download(pack.url, file, (share) => {
      if (!win.isDestroyed()) win.setProgressBar(share);
    });
  } finally {
    if (!win.isDestroyed()) win.setProgressBar(-1);
  }
  if (sum !== String(pack.sha256).toLowerCase()) {
    fs.rmSync(file, { force: true });
    throw new Error("The downloaded file isn't the one of the update (checksum)");
  }

  if (process.platform === "win32") {
    // The setup waits for this app to close (it closes it if needed) and starts the new one
    spawn(file, ["/silent"], { detached: true, stdio: "ignore" }).unref();
    app.quit();
  } else {
    const error = await shell.openPath(file);
    if (error) shell.showItemInFolder(file);
  }
}

module.exports = { start, check, compareVersions };
