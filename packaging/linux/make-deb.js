// Builds the .deb without any Debian tools (works on Windows too): a .deb is an "ar" archive
// of two gzipped tar files, both formats are simple enough to write by hand.
//
//   node make-deb.js --version 32.64 --out KDTube.deb --apk-dir <folder with KDTube-*-<abi>.apk>
//                    --script install-kdtube.sh --icon app_icon.png
//
// The package is "kdtube-installer": KDTube is an Android app, so what gets installed on Ubuntu
// is the app for every processor type plus the tool that puts it on a TV / stick / phone (adb)
// or into Waydroid.

"use strict";

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");

function parseArgs() {
  const args = {};
  for (let i = 2; i < process.argv.length; i += 2) {
    args[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  }
  for (const name of ["version", "out", "apk-dir", "script", "icon"]) {
    if (!args[name]) throw new Error("Missing --" + name);
  }
  return args;
}

// region: tar

function tarHeader(name, size, mode, type, mtime) {
  const header = Buffer.alloc(512);
  if (Buffer.byteLength(name) > 100) throw new Error("Path is too long for this writer: " + name);
  header.write(name, 0, "utf8");
  header.write(mode.toString(8).padStart(7, "0") + "\0", 100, "ascii");
  header.write("0000000\0", 108, "ascii"); // uid: root
  header.write("0000000\0", 116, "ascii"); // gid: root
  header.write(size.toString(8).padStart(11, "0") + "\0", 124, "ascii");
  header.write(mtime.toString(8).padStart(11, "0") + "\0", 136, "ascii");
  header.write("        ", 148, "ascii"); // the checksum is calculated with spaces in its place
  header.write(type, 156, "ascii");
  header.write("ustar\0" + "00", 257, "ascii");
  header.write("root", 265, "ascii");
  header.write("root", 297, "ascii");

  let sum = 0;
  for (const byte of header) sum += byte;
  header.write(sum.toString(8).padStart(6, "0") + "\0 ", 148, "ascii");
  return header;
}

/** entries: [{name: "./usr/bin/x", data: Buffer, mode: 0o755}] ; the folders are added by themselves */
function tar(entries, mtime) {
  const chunks = [];
  const dirs = new Set();

  const addDir = (dir) => {
    if (dirs.has(dir)) return;
    const parent = dir.replace(/[^/]+\/$/, "");
    if (parent && parent !== dir) addDir(parent);
    dirs.add(dir);
    chunks.push(tarHeader(dir, 0, 0o755, "5", mtime));
  };

  addDir("./");

  for (const entry of entries) {
    addDir(entry.name.replace(/[^/]+$/, ""));
    chunks.push(tarHeader(entry.name, entry.data.length, entry.mode, "0", mtime));
    chunks.push(entry.data);
    const padding = (512 - (entry.data.length % 512)) % 512;
    if (padding) chunks.push(Buffer.alloc(padding));
  }

  chunks.push(Buffer.alloc(1024)); // the end of the archive
  return Buffer.concat(chunks);
}

// endregion: tar

function ar(members, mtime) {
  const chunks = [Buffer.from("!<arch>\n", "ascii")];

  for (const member of members) {
    const header =
      member.name.padEnd(16, " ") +
      String(mtime).padEnd(12, " ") +
      "0".padEnd(6, " ") +
      "0".padEnd(6, " ") +
      "100644".padEnd(8, " ") +
      String(member.data.length).padEnd(10, " ") +
      "`\n";
    chunks.push(Buffer.from(header, "ascii"), member.data);
    if (member.data.length % 2) chunks.push(Buffer.from("\n", "ascii"));
  }

  return Buffer.concat(chunks);
}

function text(value) {
  return Buffer.from(value.replace(/\r\n/g, "\n"), "utf8");
}

function main() {
  const args = parseArgs();
  const version = args.version;
  const mtime = Math.floor(Date.now() / 1000);

  const apks = fs
    .readdirSync(args["apk-dir"])
    .filter((name) => /^KDTube-.*-(arm64-v8a|armeabi-v7a|x86)\.apk$/.test(name))
    .sort();
  if (apks.length === 0) throw new Error("No apk files in " + args["apk-dir"]);

  const data = [];

  for (const name of apks) {
    data.push({ name: "./opt/kdtube/apk/" + name, data: fs.readFileSync(path.join(args["apk-dir"], name)), mode: 0o644 });
  }

  data.push({ name: "./opt/kdtube/install-kdtube.sh", data: text(fs.readFileSync(args.script, "utf8")), mode: 0o755 });
  data.push({ name: "./usr/bin/kdtube-install", data: text('#!/bin/sh\nexec /opt/kdtube/install-kdtube.sh "$@"\n'), mode: 0o755 });
  data.push({ name: "./usr/share/pixmaps/kdtube.png", data: fs.readFileSync(args.icon), mode: 0o644 });

  data.push({
    name: "./usr/share/applications/kdtube-install.desktop",
    mode: 0o644,
    data: text(
      [
        "[Desktop Entry]",
        "Type=Application",
        "Name=KDTube Installer",
        "Comment=Put KDTube on an Android TV, a Fire TV or a phone",
        "Exec=sh -c 'kdtube-install; echo; printf \"Press Enter to close \"; read x'",
        "Terminal=true",
        "Icon=kdtube",
        "Categories=AudioVideo;Utility;",
        "",
      ].join("\n")
    ),
  });

  data.push({
    name: "./usr/share/applications/kdtube-waydroid.desktop",
    mode: 0o644,
    data: text(
      [
        "[Desktop Entry]",
        "Type=Application",
        "Name=KDTube (Waydroid)",
        "Comment=Install KDTube into Waydroid on this computer and start it",
        "Exec=sh -c 'kdtube-install --waydroid; echo; printf \"Press Enter to close \"; read x'",
        "Terminal=true",
        "Icon=kdtube",
        "Categories=AudioVideo;",
        "",
      ].join("\n")
    ),
  });

  data.push({
    name: "./usr/share/doc/kdtube-installer/README",
    mode: 0o644,
    data: text(
      [
        "KDTube " + version,
        "",
        "KDTube is an Android app (Android TV, Google TV, Fire TV, phones, tablets).",
        "There is no Linux version of it. This package contains the app for every",
        "processor type and the command that installs it on a device:",
        "",
        "  kdtube-install 192.168.1.50     a TV or stick in your network",
        "  kdtube-install                  a phone connected with a USB cable",
        "  kdtube-install --waydroid       into Waydroid on this computer",
        "",
        "On the device 'USB debugging' / 'ADB debugging' has to be switched on in the",
        "developer options. The app files are in /opt/kdtube/apk.",
        "",
      ].join("\n")
    ),
  });

  data.push({
    name: "./usr/share/doc/kdtube-installer/copyright",
    mode: 0o644,
    data: text("KDTube is a fork of SmartTube (https://github.com/yuliskov/SmartTube).\nSee the LICENSE file of the source repository.\n"),
  });

  const installedKb = Math.ceil(data.reduce((sum, entry) => sum + entry.data.length, 0) / 1024);

  const control = [
    "Package: kdtube-installer",
    "Version: " + version,
    "Section: video",
    "Priority: optional",
    "Architecture: all",
    "Depends: adb | android-tools-adb | android-sdk-platform-tools",
    "Suggests: waydroid",
    "Installed-Size: " + installedKb,
    "Maintainer: KDTube <kdtube@localhost>",
    "Description: KDTube (YouTube client for Android TV) and the installer for your devices",
    " KDTube is an Android app, there is no Linux version of it. This package carries",
    " the app for every processor type and 'kdtube-install', which puts it on an",
    " Android TV, a Fire TV, a phone or a tablet over the network or a USB cable,",
    " or into Waydroid on this computer.",
    "",
  ].join("\n");

  const md5sums =
    data.map((entry) => crypto.createHash("md5").update(entry.data).digest("hex") + "  " + entry.name.slice(2)).join("\n") + "\n";

  const controlTar = tar(
    [
      { name: "./control", data: text(control), mode: 0o644 },
      { name: "./md5sums", data: text(md5sums), mode: 0o644 },
    ],
    mtime
  );

  const deb = ar(
    [
      { name: "debian-binary", data: text("2.0\n") },
      { name: "control.tar.gz", data: zlib.gzipSync(controlTar, { level: 9 }) },
      // NOTE: the apk files are compressed already
      { name: "data.tar.gz", data: zlib.gzipSync(tar(data, mtime), { level: 1 }) },
    ],
    mtime
  );

  fs.writeFileSync(args.out, deb);
  console.log("deb: " + args.out + " (" + Math.round(deb.length / 1024 / 1024) + " MB, " + data.length + " files)");
}

main();
