#!/usr/bin/env node
"use strict";
// Builds KDTube for Samsung TVs: a TizenBrew module in tizen/ (see tizen/README.md).
//
//   node packaging/tizen/build-tizen.js            writes tizen/package.json and tizen/dist/userScript.js
//   node packaging/tizen/build-tizen.js --purge    after "git push": TVs get the new files at their next start
//
// TizenBrew reads the module from the repository through jsDelivr: gh/<owner>/<repo>@<branch>/tizen.
// jsDelivr keeps the files of a branch up to 12 hours; --purge asks it to take the new ones now.
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..", "..");
const TIZEN = path.join(ROOT, "tizen");
const INJECT = path.join(ROOT, "desktop", "app", "inject");
const MAIN = "dist/userScript.js";

function git(args) {
  return execFileSync("git", args, { cwd: ROOT, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
}

/** gh/<owner>/<repo>@<branch>/tizen: what is typed into TizenBrew (without "gh/") */
function moduleName() {
  const match = /github\.com[:/]([^/]+)\/([^/]+?)(\.git)?$/.exec(git(["remote", "get-url", "origin"]));
  if (!match) throw new Error("The remote \"origin\" isn't a GitHub repository");
  return match[1] + "/" + match[2] + "@" + git(["branch", "--show-current"]) + "/tizen";
}

function build() {
  const version = JSON.parse(fs.readFileSync(path.join(ROOT, "desktop", "app", "package.json"), "utf8")).version;
  const parts = [
    "// KDTube " + version + " for Samsung TVs (TizenBrew module). Built by packaging/tizen/build-tizen.js: don't edit, edit the sources.",
    fs.readFileSync(path.join(TIZEN, "src", "bridge.js"), "utf8"),
    // The same page scripts as the PC edition and the Android app. enhance.js (picture and sound) isn't
    // taken: the TV processes those in its own chip, and the sound of a TV must not go through the web engine.
    fs.readFileSync(path.join(INJECT, "tube.js"), "utf8"),
  ];
  fs.mkdirSync(path.join(TIZEN, "dist"), { recursive: true });
  fs.writeFileSync(path.join(TIZEN, MAIN), parts.join("\n;\n"));

  const manifest = {
    name: "kdtube-tizen",
    appName: "KDTube",
    version,
    description: "YouTube's TV interface without adverts, sponsored parts skipped, the best resolution of the screen. Red button: settings.",
    packageType: "mods",
    websiteURL: "https://www.youtube.com/tv",
    main: MAIN,
    keys: ["ColorF0Red", "MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind",
      "MediaTrackNext", "MediaTrackPrevious"],
    license: "MIT",
  };
  fs.writeFileSync(path.join(TIZEN, "package.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log("KDTube " + version + " for Samsung TVs: tizen/package.json, tizen/" + MAIN +
    " (" + Math.round(fs.statSync(path.join(TIZEN, MAIN)).size / 1024) + " KB)");
  console.log("In TizenBrew: Module Manager > Add a GitHub module > " + moduleName());
}

async function purge() {
  const name = moduleName();
  for (const file of ["package.json", MAIN]) {
    const url = "https://purge.jsdelivr.net/gh/" + name + "/" + file;
    const response = await fetch(url);
    console.log((response.ok ? "   renewed " : "   NOT renewed (" + response.status + ") ") + url);
  }
}

if (process.argv.includes("--purge")) {
  purge().catch((e) => {
    console.error("FAILED: " + e.message);
    process.exit(1);
  });
} else {
  build();
}
