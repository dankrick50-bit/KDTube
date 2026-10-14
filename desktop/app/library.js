"use strict";
// "Device media": the video and music files of this computer.
// The usual folders of the user are always searched; folders can be added, and all the drives
// can be searched too (the folders of the system and of programs are left out).
const fs = require("fs");
const os = require("os");
const path = require("path");

const VIDEO = new Set(["mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp", "ogv"]);
const AUDIO = new Set(["mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "mka"]);
/** Smaller files are ring tones and clips of programs, not something to watch */
const MIN_BYTES = 200 * 1024;
const MAX_FILES = 6000;
const MAX_DEPTH = 8;
const MAX_MS = 45000;
const SKIP = new Set(["node_modules", "$recycle.bin", "system volume information", "windows", "program files", "program files (x86)",
  "programdata", "appdata", "recovery", "msocache", "perflogs", "proc", "sys", "dev", "run", "snap", "usr", "lib", "lib64", "bin",
  "sbin", "etc", "boot", "var", "tmp", "lost+found", "cache", "caches", "temp", "site-packages", "__pycache__"]);

function userFolders(app) {
  const folders = [];
  for (const name of ["videos", "music", "downloads", "desktop"]) {
    try {
      folders.push(app.getPath(name));
    } catch (e) {
      // the system has no such folder
    }
  }
  return folders;
}

function driveRoots() {
  const roots = [];
  if (process.platform === "win32") {
    for (let code = 67; code <= 90; code++) { // C..Z
      const root = String.fromCharCode(code) + ":\\";
      try {
        if (fs.existsSync(root)) roots.push(root);
      } catch (e) {
        // an empty card reader
      }
    }
  } else {
    roots.push(os.homedir());
    for (const mount of ["/media", "/mnt", "/run/media"]) {
      if (fs.existsSync(mount)) roots.push(mount);
    }
  }
  return roots;
}

async function walk(dir, depth, state) {
  if (state.files.length >= MAX_FILES || Date.now() > state.deadline) {
    state.partial = true;
    return;
  }
  let entries;
  try {
    entries = await fs.promises.readdir(dir, { withFileTypes: true });
  } catch (e) {
    return; // no access
  }
  for (const entry of entries) {
    const name = entry.name;
    if (name.startsWith(".") || name.startsWith("$")) continue;
    const full = path.join(dir, name);
    if (entry.isDirectory()) {
      if (depth < MAX_DEPTH && !SKIP.has(name.toLowerCase()) && !state.seenDirs.has(full.toLowerCase())) {
        state.seenDirs.add(full.toLowerCase());
        await walk(full, depth + 1, state);
      }
    } else if (entry.isFile()) {
      const ext = path.extname(name).slice(1).toLowerCase();
      const audio = AUDIO.has(ext);
      if (!audio && !VIDEO.has(ext)) continue;
      const key = full.toLowerCase();
      if (state.seenFiles.has(key)) continue;
      try {
        const stat = await fs.promises.stat(full);
        if (stat.size < MIN_BYTES) continue;
        state.seenFiles.add(key);
        state.files.push({
          path: full,
          name: path.basename(name, path.extname(name)),
          ext,
          audio,
          size: stat.size,
          modified: stat.mtimeMs,
          folder: path.basename(dir),
        });
      } catch (e) {
        // gone in the meantime
      }
      if (state.files.length >= MAX_FILES) {
        state.partial = true;
        return;
      }
    }
  }
}

/**
 * @returns {Promise<{files: object[], partial: boolean, folders: string[]}>} the newest files first
 */
async function scan(app, settings) {
  const roots = userFolders(app).concat(settings.folders || []);
  if (settings.allDrives) roots.push(...driveRoots());
  const state = { files: [], seenDirs: new Set(), seenFiles: new Set(), deadline: Date.now() + MAX_MS, partial: false };
  for (const root of roots) {
    const key = root.toLowerCase();
    if (state.seenDirs.has(key)) continue;
    state.seenDirs.add(key);
    // NOTE: the folders of the user go first on purpose: the limits above cut the drives, not them
    await walk(root, 0, state);
  }
  state.files.sort((a, b) => b.modified - a.modified);
  return { files: state.files, partial: state.partial, folders: roots };
}

module.exports = { scan };
