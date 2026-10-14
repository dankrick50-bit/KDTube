#!/usr/bin/env node
"use strict";
// Sends the update of the Android / Fire OS app to where the installed apps look for it:
// the release with the tag "latest" of the repository (see installers/Updates/README.txt).
//
//   packaging/make-installers.sh         (first: it writes installers/Updates)
//   node packaging/publish-update.js
//
// The files: kdtube_<abi>.apk, then kdtube.json as the last one: an app that sees the new version
// finds the files of it already there.
//
// Access to GitHub: the variable GITHUB_TOKEN, or what git itself uses for this repository
// (its credential helper: the sign-in of "git push").
//   --dry-run   says what would be done
const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const UPDATES = path.join(ROOT, "installers", "Updates");
const TAG = "latest";
const DRY_RUN = process.argv.includes("--dry-run");
const CONTENT_TYPES = {
  ".json": "application/json",
  ".apk": "application/vnd.android.package-archive",
  ".exe": "application/vnd.microsoft.portable-executable",
  ".deb": "application/vnd.debian.binary-package",
};

function git(args, input) {
  return execFileSync("git", args, { cwd: ROOT, input, encoding: "utf8", stdio: ["pipe", "pipe", "ignore"] }).trim();
}

function repository() {
  const match = /github\.com[:/]([^/]+)\/([^/]+?)(\.git)?$/.exec(git(["remote", "get-url", "origin"]));
  if (!match) throw new Error("The remote \"origin\" isn't a GitHub repository");
  return match[1] + "/" + match[2];
}

function token() {
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN;
  const answer = execFileSync("git", ["credential", "fill"], {
    cwd: ROOT, input: "protocol=https\nhost=github.com\n\n", encoding: "utf8", stdio: ["pipe", "pipe", "ignore"],
    env: Object.assign({}, process.env, { GIT_TERMINAL_PROMPT: "0", GCM_INTERACTIVE: "never" }),
  });
  const match = /^password=(.+)$/m.exec(answer);
  if (!match) throw new Error("No access to GitHub: sign in with git (git push) or set GITHUB_TOKEN");
  return match[1].trim();
}

async function main() {
  const repo = repository();
  const info = JSON.parse(fs.readFileSync(path.join(UPDATES, "kdtube.json"), "utf8"));
  const version = Object.keys(info).find((key) => key !== "package");
  const changelog = (info[version].changelog || []).join("\n");
  // The apk files first, the list of them last. The same for the PC edition when it was built
  // (desktop/app/updater.js reads kdtube-pc.json).
  const present = fs.readdirSync(UPDATES);
  const pcFiles = present.filter((name) => /^kdtube-pc-.+\.(exe|deb)$/.test(name)).sort();
  const files = present.filter((name) => /^kdtube_.+\.apk$/.test(name)).sort().concat(["kdtube.json"])
    .concat(pcFiles.length && present.includes("kdtube-pc.json") ? pcFiles.concat(["kdtube-pc.json"]) : []);
  const commit = git(["rev-parse", "HEAD"]);
  console.log("KDTube " + version + " (version code " + info[version].versionCode + ") to " + repo + ", release \"" + TAG + "\", commit " + commit.slice(0, 7));
  if (DRY_RUN) {
    for (const name of files) console.log("   would send " + name + "  " + (fs.statSync(path.join(UPDATES, name)).size / 1048576).toFixed(1) + " MB");
    return;
  }

  const headers = { Authorization: "Bearer " + token(), Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "kdtube-publish" };
  const api = async (method, url, body, extraHeaders) => {
    const response = await fetch(url.startsWith("https://") ? url : "https://api.github.com/repos/" + repo + url, {
      method, headers: Object.assign({}, headers, extraHeaders), body: body && !Buffer.isBuffer(body) ? JSON.stringify(body) : body,
    });
    const text = await response.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch (e) {
      // not JSON: an error page
    }
    return { status: response.status, json, text };
  };
  const must = (answer, what) => {
    if (answer.status >= 300) throw new Error(what + " failed (" + answer.status + "): " + ((answer.json && answer.json.message) || answer.text.slice(0, 200)));
    return answer.json;
  };

  const text = { name: "KDTube " + version, body: changelog + "\n\nThe update channel of the app: installed apps take the new version from here." };
  let release;
  const existing = await api("GET", "/releases/tags/" + TAG);
  if (existing.status === 404) {
    release = must(await api("POST", "/releases", Object.assign({ tag_name: TAG, target_commitish: commit }, text)), "Creating the release");
    console.log("   release created");
  } else {
    release = must(existing, "Reading the release");
    // The tag follows the code the files were built from
    const moved = await api("PATCH", "/git/refs/tags/" + TAG, { sha: commit, force: true });
    if (moved.status >= 300) console.log("   NOTE: the tag stays where it is (" + moved.status + ")");
    release = must(await api("PATCH", "/releases/" + release.id, text), "Updating the release");
  }

  for (const name of files) {
    const data = fs.readFileSync(path.join(UPDATES, name));
    const old = (release.assets || []).find((asset) => asset.name === name);
    if (old) must(await api("DELETE", "/releases/assets/" + old.id), "Removing the old " + name);
    must(await api("POST", "https://uploads.github.com/repos/" + repo + "/releases/" + release.id + "/assets?name=" + encodeURIComponent(name), data,
      { "Content-Type": CONTENT_TYPES[path.extname(name)] || "application/octet-stream" }), "Sending " + name);
    console.log("   sent " + name + "  " + (data.length / 1048576).toFixed(1) + " MB");
  }

  // What an app sees now. A file that was just sent needs some seconds until it can be downloaded
  let seen = null;
  let status = 0;
  for (let i = 0; i < 15 && !(seen && seen[version]); i++) {
    if (i) await new Promise((resolve) => setTimeout(resolve, 4000));
    const check = await fetch("https://github.com/" + repo + "/releases/download/" + TAG + "/kdtube.json", { headers: { "Cache-Control": "no-cache" } });
    status = check.status;
    try {
      seen = check.ok ? JSON.parse(await check.text()) : null;
    } catch (e) {
      seen = null;
    }
  }
  if (!seen || !seen[version]) throw new Error("The files were sent, but the update file can't be read back (" + status + ")");
  console.log("== done: installed apps below " + version + " are offered the update at their next check");
}

main().catch((e) => {
  console.error("FAILED: " + e.message);
  process.exit(1);
});
