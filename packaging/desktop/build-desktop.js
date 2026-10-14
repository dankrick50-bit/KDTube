#!/usr/bin/env node
"use strict";
// Builds the PC edition of KDTube out of desktop/app and the Electron runtime.
//
//   node packaging/desktop/build-desktop.js --out installers [--stamp 20261013-0000] [--only windows|linux]
//
//   <out>/Windows/KDTube-windows-x64-setup-v<version>-<stamp>.exe     the setup (the app is inside)
//   <out>/Ubuntu/KDTube-ubuntu-amd64-v<version>-<stamp>.deb           sudo apt install ./KDTube-....deb
//   <out>/Ubuntu/KDTube-linux-x64-v<version>-<stamp>.tar.gz           unpack anywhere and run KDTube/kdtube
//
// Nothing has to be installed: the runtime is downloaded once (and checked against the list of its
// publisher), the archives are written by packaging/lib/archive.js. The Windows setup is compiled with
// the C# compiler of Windows, so that part needs a Windows machine; the Linux packages are made anywhere.
const fs = require("fs");
const path = require("path");
const https = require("https");
const zlib = require("zlib");
const crypto = require("crypto");
const { execFileSync } = require("child_process");
const archive = require("../lib/archive");

const ELECTRON_VERSION = "44.7.2";
const RCEDIT_URL = "https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe";
const ROOT = path.resolve(__dirname, "..", "..");
const APP_DIR = path.join(ROOT, "desktop", "app");
const WORK = path.join(ROOT, "packaging", "build");
const ELECTRON_DIR = path.join(WORK, "electron");
const CSC = "C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe";
const INSTALL_DIR = "opt/KDTube";

function parseArgs() {
  const args = { out: path.join(ROOT, "installers") };
  for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  if (!args.stamp) {
    const now = new Date();
    const two = (value) => String(value).padStart(2, "0");
    args.stamp = now.getFullYear() + two(now.getMonth() + 1) + two(now.getDate()) + "-" + two(now.getHours()) + two(now.getMinutes());
  }
  return args;
}

function download(url, file, redirects) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { "User-Agent": "kdtube-build" } }, (response) => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        response.resume();
        if ((redirects || 0) > 8) return reject(new Error("Too many redirects: " + url));
        return resolve(download(new URL(response.headers.location, url).href, file, (redirects || 0) + 1));
      }
      if (response.statusCode !== 200) {
        response.resume();
        return reject(new Error(response.statusCode + " for " + url));
      }
      const out = fs.createWriteStream(file + ".part");
      response.pipe(out);
      out.on("finish", () => out.close(() => {
        fs.renameSync(file + ".part", file);
        resolve(file);
      }));
      out.on("error", reject);
    }).on("error", reject);
  });
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

/** The runtime for a platform ("win32-x64", "linux-x64"), checked against the checksums of its release */
async function getElectron(platform) {
  fs.mkdirSync(ELECTRON_DIR, { recursive: true });
  const name = "electron-v" + ELECTRON_VERSION + "-" + platform + ".zip";
  const base = "https://github.com/electron/electron/releases/download/v" + ELECTRON_VERSION + "/";
  const file = path.join(ELECTRON_DIR, name);
  const sums = path.join(ELECTRON_DIR, "SHASUMS256-" + ELECTRON_VERSION + ".txt");
  if (!fs.existsSync(sums)) await download(base + "SHASUMS256.txt", sums);
  if (!fs.existsSync(file)) {
    console.log("   downloading " + name);
    await download(base + name, file);
  }
  const line = fs.readFileSync(sums, "utf8").split(/\r?\n/).find((item) => item.endsWith("*" + name) || item.endsWith(" " + name));
  if (!line) throw new Error("No checksum for " + name);
  if (sha256(file) !== line.split(/\s+/)[0]) {
    fs.unlinkSync(file);
    throw new Error("The checksum of " + name + " is wrong. It was removed: run again.");
  }
  return file;
}

function appFiles() {
  const files = [];
  const walk = (dir, prefix) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) walk(path.join(dir, entry.name), prefix + entry.name + "/");
      else files.push({ name: prefix + entry.name, data: fs.readFileSync(path.join(dir, entry.name)) });
    }
  };
  walk(APP_DIR, "");
  return files;
}

// ---------- Windows ----------

async function buildWindows(args, version) {
  console.log("== Windows");
  if (process.platform !== "win32") {
    console.log("   skipped: the setup is compiled with the C# compiler of Windows");
    return;
  }
  const dir = path.join(WORK, "desktop", "win");
  const appDir = path.join(dir, "KDTube");
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(appDir, { recursive: true });

  const runtime = archive.readZip(await getElectron("win32-x64"));
  for (const entry of runtime) {
    if (entry.isDirectory || entry.name === "resources/default_app.asar") continue;
    const name = entry.name === "electron.exe" ? "KDTube.exe" : entry.name;
    const target = path.join(appDir, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, entry.read());
  }
  for (const file of appFiles()) {
    const target = path.join(appDir, "resources", "app", file.name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, file.data);
  }

  // The name, the version and the icon of the program file itself
  const rcedit = path.join(ELECTRON_DIR, "rcedit-x64.exe");
  if (!fs.existsSync(rcedit)) await download(RCEDIT_URL, rcedit);
  const icon = path.join(APP_DIR, "assets", "icon.ico");
  execFileSync(rcedit, [path.join(appDir, "KDTube.exe"), "--set-icon", icon,
    "--set-version-string", "ProductName", "KDTube", "--set-version-string", "FileDescription", "KDTube",
    "--set-version-string", "CompanyName", "KDTube", "--set-version-string", "LegalCopyright", "MIT License",
    "--set-version-string", "OriginalFilename", "KDTube.exe", "--set-file-version", version, "--set-product-version", version],
    { stdio: "inherit" });

  console.log("   packing the app");
  const payload = path.join(dir, "payload.zip");
  function* packed(folder, prefix) {
    for (const entry of fs.readdirSync(folder, { withFileTypes: true })) {
      const full = path.join(folder, entry.name);
      if (entry.isDirectory()) yield* packed(full, prefix + entry.name + "/");
      else yield { name: prefix + entry.name, data: fs.readFileSync(full) };
    }
  }
  archive.writeZip(payload, packed(appDir, ""));

  console.log("   compiling the setup");
  const versionFile = path.join(dir, "Version.cs");
  fs.writeFileSync(versionFile, '[assembly: System.Reflection.AssemblyVersion("' + version + '.0")]\n' +
    '[assembly: System.Reflection.AssemblyFileVersion("' + version + '.0")]\n');
  const outDir = path.join(args.out, "Windows");
  fs.mkdirSync(outDir, { recursive: true });
  const setup = path.join(outDir, "KDTube-windows-x64-setup-v" + args.display + "-" + args.stamp + ".exe");
  execFileSync(CSC, ["/nologo", "/target:winexe", "/optimize+", "/platform:x64", "/out:" + setup, "/win32icon:" + icon,
    "/resource:" + payload + ",payload.zip", "/r:System.dll", "/r:System.Drawing.dll", "/r:System.Windows.Forms.dll",
    "/r:System.IO.Compression.dll", "/r:System.IO.Compression.FileSystem.dll",
    path.join(ROOT, "packaging", "windows", "KDTubeDesktopSetup.cs"), versionFile], { stdio: "inherit" });
  console.log("   " + path.relative(ROOT, setup) + "  " + Math.round(fs.statSync(setup).size / 1048576) + " MB");
}

// ---------- Linux ----------

const EXECUTABLE = /^(kdtube|chrome_crashpad_handler|chrome-sandbox)$|\.so(\.\d+)*$/;

function linuxControl(version, installedKb) {
  return [
    "Package: kdtube",
    "Version: " + version,
    "Section: video",
    "Priority: optional",
    "Architecture: amd64",
    "Installed-Size: " + installedKb,
    "Depends: libgtk-3-0 | libgtk-3-0t64, libnotify4, libnss3, libxss1, libxtst6, xdg-utils, libatspi2.0-0 | libatspi2.0-0t64, " +
      "libuuid1, libsecret-1-0, libgbm1, libasound2 | libasound2t64",
    "Recommends: va-driver-all | va-driver, libva2",
    "Maintainer: KDTube <noreply@kdtube.invalid>",
    "Homepage: https://github.com/dankrick50-bit/KDTube",
    "Description: YouTube for the big screen, and a player for your own files",
    " YouTube in the form it has on a TV, made for the keyboard and the sofa: no adverts,",
    " sponsored parts skipped, picture and sound options, video decoding by the graphics card.",
    " With a player for the video and music files of this computer.",
    "",
  ].join("\n");
}

const POSTINST = `#!/bin/sh
set -e
# The sandbox helper of the browser engine: it has to belong to root and may act as root.
chown root:root /${INSTALL_DIR}/chrome-sandbox 2>/dev/null || true
chmod 4755 /${INSTALL_DIR}/chrome-sandbox || true
# Ubuntu 24.04 and newer want a profile from a program that builds its own sandbox out of namespaces.
# An older AppArmor doesn't understand the profile: it isn't needed there.
if command -v apparmor_parser >/dev/null 2>&1 && [ -d /etc/apparmor.d ]; then
  if apparmor_parser --skip-kernel-load --debug /${INSTALL_DIR}/resources/apparmor-profile >/dev/null 2>&1; then
    cp -f /${INSTALL_DIR}/resources/apparmor-profile /etc/apparmor.d/kdtube
    apparmor_parser --replace --write-cache --skip-read-cache /etc/apparmor.d/kdtube >/dev/null 2>&1 || true
  fi
fi
if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database -q /usr/share/applications || true; fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true; fi
exit 0
`;

const POSTRM = `#!/bin/sh
set -e
if [ "$1" = "remove" ] || [ "$1" = "purge" ]; then
  if [ -f /etc/apparmor.d/kdtube ]; then
    if command -v apparmor_parser >/dev/null 2>&1; then apparmor_parser --remove /etc/apparmor.d/kdtube >/dev/null 2>&1 || true; fi
    rm -f /etc/apparmor.d/kdtube
  fi
fi
exit 0
`;

const APPARMOR = `abi <abi/4.0>,
include <tunables/global>

profile kdtube /${INSTALL_DIR}/kdtube flags=(unconfined) {
  userns,

  include if exists <local/kdtube>
}
`;

const DESKTOP_ENTRY = `[Desktop Entry]
Type=Application
Name=KDTube
GenericName=Video Player
Comment=YouTube for the big screen, and a player for your own files
Exec=/${INSTALL_DIR}/kdtube %U
Icon=kdtube
Terminal=false
Categories=AudioVideo;Video;Player;
StartupWMClass=KDTube
`;

const PORTABLE_README = `KDTube for Linux (x64), portable
================================

Run:   ./kdtube

If it stops right away with a message about the "SUID sandbox helper": the kernel of this system
doesn't let ordinary programs build a sandbox. Either install the .deb package instead (it sets the
helper up), or do it by hand once:

    sudo chown root:root chrome-sandbox && sudo chmod 4755 chrome-sandbox

Video decoding by the graphics card needs the VA-API drivers of the system (Ubuntu: va-driver-all).
The settings and the YouTube sign-in are kept in ~/.config/KDTube.
`;

async function buildLinux(args, version) {
  console.log("== Linux");
  const runtime = archive.readZip(await getElectron("linux-x64"));
  const files = []; // {name (inside of the folder of the app), data, mode}
  for (const entry of runtime) {
    if (entry.isDirectory || entry.name === "resources/default_app.asar") continue;
    if (entry.isSymlink) throw new Error("A link in the runtime, which this script doesn't expect: " + entry.name);
    const name = entry.name === "electron" ? "kdtube" : entry.name;
    const base = name.split("/").pop();
    let mode = EXECUTABLE.test(base) ? 0o755 : 0o644;
    if (base === "chrome-sandbox") mode = 0o4755;
    files.push({ name, data: entry.read(), mode });
  }
  for (const file of appFiles()) files.push({ name: "resources/app/" + file.name, data: file.data, mode: 0o644 });
  files.push({ name: "resources/apparmor-profile", data: archive.text(APPARMOR), mode: 0o644 });

  const mtime = Math.floor(Date.now() / 1000);
  const outDir = path.join(args.out, "Ubuntu");
  fs.mkdirSync(outDir, { recursive: true });

  // The .deb
  const data = files.map((file) => ({ name: "./" + INSTALL_DIR + "/" + file.name, data: file.data, mode: file.mode }));
  data.push({ name: "./usr/bin/kdtube", link: "/" + INSTALL_DIR + "/kdtube" });
  data.push({ name: "./usr/share/applications/kdtube.desktop", data: archive.text(DESKTOP_ENTRY), mode: 0o644 });
  for (const size of [48, 64, 128, 256, 512]) {
    const icon = path.join(__dirname, "icons", "kdtube-" + size + ".png");
    data.push({ name: "./usr/share/icons/hicolor/" + size + "x" + size + "/apps/kdtube.png", data: fs.readFileSync(icon), mode: 0o644 });
  }
  const installedKb = Math.ceil(data.reduce((sum, item) => sum + (item.data ? item.data.length : 0), 0) / 1024);
  const control = [
    { name: "./control", data: archive.text(linuxControl(version, installedKb)), mode: 0o644 },
    { name: "./postinst", data: archive.text(POSTINST), mode: 0o755 },
    { name: "./postrm", data: archive.text(POSTRM), mode: 0o755 },
  ];
  console.log("   packing the .deb");
  const deb = archive.ar([
    { name: "debian-binary", data: Buffer.from("2.0\n", "ascii") },
    { name: "control.tar.gz", data: zlib.gzipSync(archive.tar(control, mtime)) },
    { name: "data.tar.gz", data: zlib.gzipSync(archive.tar(data, mtime), { level: 6 }) },
  ], mtime);
  const debFile = path.join(outDir, "KDTube-ubuntu-amd64-v" + args.display + "-" + args.stamp + ".deb");
  fs.writeFileSync(debFile, deb);
  console.log("   " + path.relative(ROOT, debFile) + "  " + Math.round(deb.length / 1048576) + " MB");

  // The same files as a plain archive, for the other distributions
  console.log("   packing the portable archive");
  const portable = files.map((file) => ({ name: "./KDTube/" + file.name, data: file.data, mode: file.mode === 0o4755 ? 0o755 : file.mode }));
  portable.push({ name: "./KDTube/README.txt", data: archive.text(PORTABLE_README), mode: 0o644 });
  portable.push({ name: "./KDTube/kdtube.png", data: fs.readFileSync(path.join(__dirname, "icons", "kdtube-256.png")), mode: 0o644 });
  const tarFile = path.join(outDir, "KDTube-linux-x64-v" + args.display + "-" + args.stamp + ".tar.gz");
  const packed = zlib.gzipSync(archive.tar(portable, mtime), { level: 6 });
  fs.writeFileSync(tarFile, packed);
  console.log("   " + path.relative(ROOT, tarFile) + "  " + Math.round(packed.length / 1048576) + " MB");
}

async function main() {
  const args = parseArgs();
  const version = JSON.parse(fs.readFileSync(path.join(APP_DIR, "package.json"), "utf8")).version; // 32.64.0
  // The version as it's shown and written in the names of the files: the one of the Android app
  args.display = version.replace(/\.0$/, "");
  console.log("KDTube for the PC " + version + " (Electron " + ELECTRON_VERSION + ")");
  if (args.only !== "linux") await buildWindows(args, version);
  if (args.only !== "windows") await buildLinux(args, version);
}

main().catch((error) => {
  console.error("ERROR: " + (error && error.message ? error.message : error));
  process.exit(1);
});
