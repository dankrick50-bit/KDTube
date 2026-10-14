"use strict";
// The memory keeper of the desktop edition ("virtual RAM").
//
// 1. The session cache: an area on the disk for what the browser engine would otherwise keep in
//    memory or load again (the answers of the network, pieces of video). It has a size limit and it
//    belongs to one run of the app: it's emptied when the app exits, and what a killed run left behind
//    is removed at the next start.
// 2. Limits for what has to stay in memory: the buffers of the video and of the sound. They follow the
//    amount of RAM of the computer.
// 3. The work goes to the hardware where there is some: video is decoded by the graphics card and
//    drawn without copies, the pages are drawn by the GPU.
//
// NOTE: when the system runs short of memory the engine gives back its caches by itself; there is
// nothing to add to that here.
const fs = require("fs");
const os = require("os");
const path = require("path");

const GB = 1024 * 1024 * 1024;
const MB = 1024 * 1024;
// The folders of the engine (inside of the data folder of the app) that make up the session cache
const SESSION_FOLDERS = ["Cache", "blob_storage"];

let dataDir = null;
let limits = null;

function remove(dir) {
  try {
    fs.rmSync(dir, { recursive: true, force: true, maxRetries: 2, retryDelay: 100 });
  } catch (e) {
    // in use: the next start takes care of it
  }
}

function getLimits() {
  const total = os.totalmem();
  if (total <= 4.5 * GB) return { videoMb: 64, audioMb: 8, cacheMb: 256, lowMemory: true };
  if (total <= 9 * GB) return { videoMb: 96, audioMb: 12, cacheMb: 384, lowMemory: false };
  return { videoMb: 150, audioMb: 12, cacheMb: 512, lowMemory: false };
}

/** Has to be called before the app is ready: the switches are read once, at the start of the engine. */
function init(app, settings) {
  dataDir = app.getPath("userData");
  limits = getLimits();

  // What a run that was killed left behind. Nothing of the engine runs yet: the files are free.
  for (const name of SESSION_FOLDERS) remove(path.join(dataDir, name));

  const cli = app.commandLine;
  cli.appendSwitch("disk-cache-size", String(limits.cacheMb * MB));
  cli.appendSwitch("mse-video-buffer-size-limit-mb", String(limits.videoMb));
  cli.appendSwitch("mse-audio-buffer-size-limit-mb", String(limits.audioMb));
  if (limits.lowMemory) {
    // 4 GB and less: the engine keeps its own caches (decoded images, fonts, compiled code) small
    cli.appendSwitch("enable-low-end-device-mode");
  }

  if (settings.hardwareAcceleration === false) {
    app.disableHardwareAcceleration();
  } else {
    // Old or unusual graphics drivers are on a list of the engine and get software drawing.
    // Decoding 4K on the processor is the worse choice for a video app.
    cli.appendSwitch("ignore-gpu-blocklist");
    cli.appendSwitch("enable-gpu-rasterization");
    cli.appendSwitch("enable-zero-copy");
    const features = ["PlatformHEVCDecoderSupport"];
    if (process.platform === "linux") {
      // Video decoding by the graphics card (VA-API). The names changed between the versions of the engine.
      features.push("AcceleratedVideoDecodeLinuxGL", "AcceleratedVideoDecodeLinuxZeroCopyGL", "VaapiVideoDecoder",
        "VaapiVideoDecodeLinuxGL", "VaapiIgnoreDriverChecks");
    }
    cli.appendSwitch("enable-features", features.join(","));
  }
}

/** Empties the session cache. The engine does it itself: it knows which of its files are open. */
async function cleanup(session) {
  try {
    await session.clearCache();
  } catch (e) {
    // the start of the next run removes the folders
  }
}

function folderSize(dir, depth) {
  let total = 0;
  let entries = [];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    return 0;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    try {
      if (entry.isDirectory()) {
        if (depth < 6) total += folderSize(full, depth + 1);
      } else {
        total += fs.statSync(full).size;
      }
    } catch (e) {
      // changed under our feet
    }
  }
  return total;
}

/** What the "Memory" part of the settings shows. */
function getInfo(app) {
  let appBytes = 0;
  try {
    for (const metric of app.getAppMetrics()) {
      const memory = metric.memory || {};
      // Kilobytes. The private part where the system tells it (Windows): the rest is shared between
      // the processes of the app and would be counted several times.
      appBytes += (memory.privateBytes || memory.workingSetSize || 0) * 1024;
    }
  } catch (e) {
    // not ready yet
  }
  let scratchBytes = 0;
  if (dataDir) {
    for (const name of SESSION_FOLDERS) scratchBytes += folderSize(path.join(dataDir, name), 0);
  }
  return { totalBytes: os.totalmem(), freeBytes: os.freemem(), appBytes, scratchBytes, limits };
}

module.exports = { init, cleanup, getInfo };
