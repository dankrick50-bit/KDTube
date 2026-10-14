"use strict";
// KDTube for the PC (Windows, Linux).
//
// The window has two layers. Below: the interface of the app itself (ui/shell.html) with the bar on
// top, the settings panel and "Device media". Above it: YouTube in the form it has on a TV, which is
// made for a remote control and a big screen, so it works with the arrow keys from the sofa.
// The page of YouTube runs in its own sandbox and gets four functions of the app (tube-preload.js).
const { app, BaseWindow, WebContentsView, ipcMain, shell, dialog, powerSaveBlocker, net, session, screen } = require("electron");
const fs = require("fs");
const path = require("path");

const settings = require("./settings");
const memory = require("./memory");
const library = require("./library");
const cast = require("./cast");
const updater = require("./updater");

const TV_URL = "https://www.youtube.com/tv";
// What YouTube gives its TV interface to. The codecs and the resolutions are found out by the page itself.
const USER_AGENT = "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible; KDTube/" + app.getVersion();
const BAR_HEIGHT = 44;
const PANEL_WIDTH = 400;
const ARROW_KEYS = ["Up", "Down", "Left", "Right"];
const SPONSOR_API = "https://sponsor.ajay.app/api/skipSegments";
const SPONSOR_LABELS = {
  sponsor: "sponsor",
  selfpromo: "self promotion",
  interaction: "like / subscribe reminder",
  intro: "intro",
  outro: "end credits",
  preview: "preview",
  music_offtopic: "non-music part",
  filler: "filler",
};

// Options for testing: --kd-shot=<file> saves pictures of both layers after --kd-shot-delay=<ms> and exits;
// --kd-show=panel|library opens that part first; --kd-url=<address> instead of the home page.
function option(name) {
  const prefix = "--kd-" + name + "=";
  const found = process.argv.find((arg) => arg.startsWith(prefix));
  return found ? found.slice(prefix.length) : null;
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
  return;
}

app.setName("KDTube");
const values = settings.load(app);
memory.init(app, values);
cast.init(app);
app.userAgentFallback = option("ua") || USER_AGENT;
// A video starts when it's chosen, with or without a click before it
app.commandLine.appendSwitch("autoplay-policy", "no-user-gesture-required");
if (process.platform === "linux") {
  // Wayland when the desktop runs on it
  app.commandLine.appendSwitch("ozone-platform-hint", "auto");
}

let win = null;
let shellView = null;
let tubeView = null;
let mode = "tube"; // tube | library
let panelOpen = false;
let sleepBlocker = null;
let tubeFailed = false;

function injectCode() {
  try {
    return ["enhance.js", "tube.js"].map((name) => fs.readFileSync(path.join(__dirname, "inject", name), "utf8")).join("\n;\n");
  } catch (e) {
    return "";
  }
}

function layout() {
  if (!win || win.isDestroyed()) return;
  const [width, height] = win.getContentSize();
  const top = win.isFullScreen() ? 0 : BAR_HEIGHT;
  const panel = panelOpen ? Math.min(PANEL_WIDTH, Math.floor(width / 2)) : 0;
  shellView.setBounds({ x: 0, y: 0, width, height });
  const showTube = mode === "tube" && !tubeFailed;
  tubeView.setVisible(showTube);
  if (showTube) {
    tubeView.setBounds({ x: 0, y: top, width: Math.max(0, width - panel), height: Math.max(0, height - top) });
  }
}

function sendState() {
  if (!shellView || shellView.webContents.isDestroyed()) return;
  shellView.webContents.send("kd:state", {
    mode,
    panelOpen,
    fullscreen: win.isFullScreen(),
    tubeFailed,
    barHeight: BAR_HEIGHT,
    panelWidth: PANEL_WIDTH,
    version: app.getVersion(),
  });
}

function update() {
  layout();
  sendState();
}

/**
 * The TV interface offers the resolutions that fit the size of its page, and it measures the page in
 * the units of the system, not in the pixels of the display. On a 4K display with the usual 150%
 * scaling it would see 2560 x 1440 at best and never offer 4K. So the page is drawn one to one: a unit
 * is a pixel. Its layout follows the size of the page, so it looks the same, only sharper.
 */
function displayScale() {
  try {
    return screen.getDisplayMatching(win.getBounds()).scaleFactor || 1;
  } catch (e) {
    // the display is gone: the next move of the window asks again
    return 1;
  }
}

function applyPixelScale() {
  if (!win || win.isDestroyed() || !tubeView || tubeView.webContents.isDestroyed()) return;
  tubeView.webContents.setZoomFactor(1 / displayScale());
}

/** What the page of YouTube is told */
function pageSettings() {
  return Object.assign({ displayScale: win && !win.isDestroyed() ? displayScale() : 1 }, settings.forPage());
}

function focusContent() {
  if (mode === "tube" && !panelOpen && !tubeFailed) tubeView.webContents.focus();
  else shellView.webContents.focus();
}

function pauseTube() {
  tubeView.webContents.executeJavaScript("(function(){var v=document.querySelector('video');if(v&&!v.paused)v.pause();})()").catch(() => {});
}

function setMode(next) {
  if (next !== "tube" && next !== "library") return;
  if (next !== mode && next === "library") pauseTube();
  mode = next;
  update();
  focusContent();
}

function setPanel(open) {
  panelOpen = !!open;
  update();
  focusContent();
}

function toggleFullscreen() {
  win.setFullScreen(!win.isFullScreen());
}

function pressKey(contents, keyCode) {
  contents.sendInputEvent({ type: "keyDown", keyCode });
  contents.sendInputEvent({ type: "keyUp", keyCode });
}

/**
 * A test aid (--kd-keys): a finger on the page of YouTube, sent the way a touch screen would.
 * "swipe:x1/y1/x2/y2" drags from one point to the other in 12 moves, "tap:x/y" touches one point.
 */
async function touchStep(step) {
  const contents = tubeView.webContents;
  if (!contents.debugger.isAttached()) contents.debugger.attach("1.3");
  const send = (type, touchPoints) => contents.debugger.sendCommand("Input.dispatchTouchEvent", { type, touchPoints });
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const [kind, numbers] = step.split(":");
  const [x1, y1, x2, y2] = numbers.split("/").map(Number);
  await send("touchStart", [{ x: x1, y: y1, id: 1 }]);
  if (kind === "swipe") {
    for (let i = 1; i <= 12; i++) {
      await wait(16);
      await send("touchMove", [{ x: x1 + ((x2 - x1) * i) / 12, y: y1 + ((y2 - y1) * i) / 12, id: 1 }]);
    }
  } else {
    await wait(50);
  }
  await send("touchEnd", []);
}

function goBack() {
  if (panelOpen) {
    setPanel(false);
  } else if (mode === "library") {
    shellView.webContents.send("kd:back");
  } else {
    // The TV interface has its own "back": the button of the remote
    pressKey(tubeView.webContents, "Escape");
  }
}

function goHome() {
  tubeFailed = false;
  setMode("tube");
  tubeView.webContents.loadURL(TV_URL).catch(() => {});
}

function broadcastSettings() {
  if (tubeView && !tubeView.webContents.isDestroyed()) tubeView.webContents.send("kd:settings", pageSettings());
  if (shellView && !shellView.webContents.isDestroyed()) shellView.webContents.send("kd:settings", settings.get());
}

/** The keys that belong to the app, whichever layer has the focus. */
function handleKey(event, input) {
  if (input.type !== "keyDown") return;
  const key = input.key;
  if (key === "F11" || (key === "Enter" && input.alt)) {
    toggleFullscreen();
  } else if (key === "F2" || (key === "," && input.control)) {
    setPanel(!panelOpen);
  } else if (key === "F5" || (key.toLowerCase() === "r" && input.control)) {
    if (mode === "tube") tubeView.webContents.reload();
  } else if (key === "ArrowLeft" && input.alt) {
    goBack();
  } else if (key === "F12" && option("dev") !== null) {
    tubeView.webContents.openDevTools({ mode: "detach" });
  } else {
    return;
  }
  event.preventDefault();
}

async function loadSegments(videoId) {
  const current = settings.get();
  if (!current.sponsorblock || !/^[\w-]{6,20}$/.test(videoId) || !current.sponsorCategories.length) return [];
  const url = SPONSOR_API + "?videoID=" + encodeURIComponent(videoId) + "&categories=" +
    encodeURIComponent(JSON.stringify(current.sponsorCategories));
  try {
    const response = await net.fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok) return []; // 404: nothing is known about the video
    const list = await response.json();
    return list
      .filter((item) => Array.isArray(item.segment) && item.actionType !== "mute" && item.actionType !== "full")
      .map((item) => ({ start: item.segment[0], end: item.segment[1], label: SPONSOR_LABELS[item.category] || item.category }));
  } catch (e) {
    return [];
  }
}

function setupIpc() {
  ipcMain.on("kd:settings-sync", (event) => {
    event.returnValue = event.sender === tubeView.webContents ? pageSettings() : settings.get();
  });
  ipcMain.on("kd:inject-code", (event) => {
    // Only for the page of YouTube, in the layer that is made for it
    event.returnValue = event.sender === tubeView.webContents ? injectCode() : "";
  });
  ipcMain.handle("kd:segments", (event, videoId) => (event.sender === tubeView.webContents ? loadSegments(String(videoId)) : []));
  ipcMain.on("kd:page-back", (event) => {
    if (event.sender === tubeView.webContents) pressKey(tubeView.webContents, "Escape");
  });
  ipcMain.on("kd:page-key", (event, name) => {
    // Touch scrolling: the arrows only, and only for the page of YouTube
    if (event.sender === tubeView.webContents && ARROW_KEYS.includes(name)) pressKey(tubeView.webContents, name);
  });
  ipcMain.on("kd:log", (event, line) => {
    if (event.sender === tubeView.webContents) logPlayer(String(line).slice(0, 500));
  });
  ipcMain.on("kd:playing", (event, isPlaying) => {
    // The screen doesn't go dark in the middle of a film
    if (isPlaying && sleepBlocker === null) {
      sleepBlocker = powerSaveBlocker.start("prevent-display-sleep");
    } else if (!isPlaying && sleepBlocker !== null) {
      powerSaveBlocker.stop(sleepBlocker);
      sleepBlocker = null;
    }
  });

  // Everything below is for the interface of the app only
  const fromShell = (event) => shellView && event.sender === shellView.webContents;

  ipcMain.on("kd:command", (event, name, argument) => {
    if (!fromShell(event)) return;
    switch (name) {
      case "home": goHome(); break;
      case "back": goBack(); break;
      case "reload": tubeFailed = false; update(); tubeView.webContents.reload(); break;
      case "fullscreen": toggleFullscreen(); break;
      case "panel": setPanel(argument === undefined ? !panelOpen : argument); break;
      case "mode": setMode(argument); break;
      case "state": sendState(); break;
      case "quit": app.quit(); break;
      default: break;
    }
  });
  ipcMain.handle("kd:set", (event, key, value) => {
    if (!fromShell(event)) return false;
    const ok = settings.set(key, value);
    if (ok) broadcastSettings();
    return ok;
  });
  ipcMain.handle("kd:memory", (event) => (fromShell(event) ? memory.getInfo(app) : null));
  ipcMain.handle("kd:library", (event) => (fromShell(event) ? library.scan(app, settings.get()) : null));
  ipcMain.handle("kd:add-folder", async (event) => {
    if (!fromShell(event)) return null;
    const result = await dialog.showOpenDialog(win, { title: "Add a folder with videos or music", properties: ["openDirectory"] });
    if (result.canceled || !result.filePaths.length) return null;
    const folders = settings.get().folders.slice();
    if (!folders.includes(result.filePaths[0])) folders.push(result.filePaths[0]);
    settings.set("folders", folders);
    broadcastSettings();
    return result.filePaths[0];
  });
  ipcMain.handle("kd:cast-devices", (event) => (fromShell(event) ? cast.discover().catch(() => []) : []));
  ipcMain.handle("kd:cast-forget", (event, screenId) => {
    if (fromShell(event)) cast.forget(String(screenId));
  });
  ipcMain.handle("kd:cast-send", async (event, device) => {
    if (!fromShell(event) || !device) return { error: "Not allowed" };
    try {
      // What plays here, and where it is
      const playing = JSON.parse(await tubeView.webContents.executeJavaScript(
        "(function(){var m=/[?&]v=([A-Za-z0-9_-]{6,})/.exec(location.hash);var v=document.querySelector('video');" +
        "return JSON.stringify({id:m?m[1]:null,time:v?v.currentTime:0})})()"));
      if (!playing.id) return { error: "Open a video first: the one that plays here is sent to the TV" };
      const name = await cast.send(device, playing.id, playing.time);
      pauseTube();
      return { name };
    } catch (e) {
      return { error: e && e.message ? e.message : String(e) };
    }
  });
  ipcMain.handle("kd:open-external", (event, file) => (fromShell(event) ? shell.openPath(String(file)) : "denied"));
  ipcMain.handle("kd:show-in-folder", (event, file) => {
    if (fromShell(event)) shell.showItemInFolder(String(file));
  });
  ipcMain.handle("kd:sign-out", async (event) => {
    if (!fromShell(event)) return false;
    const answer = await dialog.showMessageBox(win, {
      type: "question",
      buttons: ["Sign out", "Cancel"],
      defaultId: 1,
      cancelId: 1,
      message: "Sign out and forget everything YouTube stored on this computer?",
      detail: "The settings of KDTube stay.",
    });
    if (answer.response !== 0) return false;
    await tubeView.webContents.session.clearStorageData();
    await tubeView.webContents.session.clearCache();
    goHome();
    return true;
  });
}

/**
 * The TV interface gets its video from the servers of YouTube (googlevideo.com) the way a TV does:
 * without the permission headers a browser insists on for a page that reads from another address.
 * The headers are added here, for those servers only and for the page of YouTube only.
 */
let networkLog = null; // a test aid: see --kd-shot

// The log of the player: what the page asked for and what failed. A fresh one with every run, never big.
// It's the first thing to look at when a video doesn't play: kdtube-player.log in the data folder of the app.
let playerLog = null;
let playerLogLines = 0;

function logPlayer(line) {
  try {
    if (!playerLog) {
      playerLog = path.join(app.getPath("userData"), "kdtube-player.log");
      fs.writeFileSync(playerLog, "KDTube " + app.getVersion() + ", " + process.platform + " " + process.getSystemVersion() +
        ", engine " + process.versions.chrome + "\n");
    }
    if (playerLogLines++ < 4000) fs.appendFileSync(playerLog, new Date().toISOString().slice(11, 19) + " " + line + "\n");
  } catch (e) {
    // the log is a nicety
  }
  if (networkLog) networkLog("[log] " + line);
}

function allowVideoServers(ses) {
  const filter = { urls: ["https://*.googlevideo.com/*", "https://*.c.youtube.com/*", "https://*.gvt1.com/*"] };
  const pending = new Map();

  ses.webRequest.onBeforeSendHeaders(filter, (details, callback) => {
    const headers = details.requestHeaders || {};
    const find = (name) => {
      const key = Object.keys(headers).find((item) => item.toLowerCase() === name);
      return key ? headers[key] : null;
    };
    const origin = find("origin");
    const known = pending.get(details.id);
    if (origin && /^https:\/\/([\w-]+\.)?youtube\.com$/.test(origin)) {
      pending.set(details.id, { origin, requestHeaders: find("access-control-request-headers") });
      // A request that never gets an answer shouldn't stay here forever
      if (pending.size > 500) pending.delete(pending.keys().next().value);
    } else if (known && origin) {
      // The same request, sent on to another server (the servers of the video do that when one of
      // them is busy or far away). The browser hides the page behind the word "null" from then on,
      // and wants exactly that word back.
      known.origin = origin;
      known.requestHeaders = find("access-control-request-headers");
    }
    callback({ requestHeaders: headers });
  });

  ses.webRequest.onHeadersReceived(filter, (details, callback) => {
    const request = pending.get(details.id);
    if (networkLog) {
      const names = Object.keys(details.responseHeaders || {});
      const value = (name) => {
        const key = names.find((item) => item.toLowerCase() === name);
        return key ? String(details.responseHeaders[key]) : "-";
      };
      networkLog("headers " + details.statusCode + " " + details.method + " " + details.url.replace(/\?.*$/, "").slice(8, 70) +
        " type=" + details.resourceType + " origin=" + (request ? request.origin : "none") + " allow-origin=" + value("access-control-allow-origin") +
        " content-type=" + value("content-type") + " location=" + value("location").replace(/\?.*$/, "").slice(0, 60));
    }
    // NOTE: a redirect is answered under the same id: keep it until the request is over
    if (!request) {
      callback({});
      return;
    }
    const headers = details.responseHeaders || {};
    const has = (name) => Object.keys(headers).some((item) => item.toLowerCase() === name);
    if (!has("access-control-allow-origin")) {
      headers["Access-Control-Allow-Origin"] = [request.origin];
      headers["Access-Control-Allow-Credentials"] = ["true"];
    }
    if (details.method === "OPTIONS") {
      if (!has("access-control-allow-methods")) headers["Access-Control-Allow-Methods"] = ["GET, POST, OPTIONS"];
      if (!has("access-control-allow-headers") && request.requestHeaders) {
        headers["Access-Control-Allow-Headers"] = [request.requestHeaders];
      }
    }
    callback({ responseHeaders: headers });
  });

  ses.webRequest.onBeforeRedirect(filter, (details) => {
    logPlayer("video server sends on: " + details.statusCode + " " + details.url.replace(/\?.*$/, "").slice(0, 90) + " -> " +
      String(details.redirectURL).replace(/\?.*$/, "").slice(0, 90));
  });

  const forget = (details) => {
    pending.delete(details.id);
    const failed = details.error && details.error !== "net::OK" && details.error !== "net::ERR_ABORTED";
    const where = details.method + " " + details.url.replace(/\?.*$/, "?...").slice(0, 110);
    // What didn't work goes to the log of the player
    if (failed) logPlayer("video server request FAILED " + details.error + " " + where);
    else if (details.statusCode >= 400) logPlayer("video server answered " + details.statusCode + " " + where);
    else if (networkLog) networkLog(details.statusCode + " " + where + (details.fromCache ? " (cache)" : ""));
  };
  ses.webRequest.onCompleted(filter, forget);
  ses.webRequest.onErrorOccurred(filter, forget);
}

function createWindow() {
  const stored = settings.get().window || {};
  win = new BaseWindow({
    width: parseInt((option("size") || "").split("x")[0], 10) || stored.width || 1280,
    height: parseInt((option("size") || "").split("x")[1], 10) || stored.height || 720 + BAR_HEIGHT,
    x: stored.x,
    y: stored.y,
    minWidth: 800,
    minHeight: 480,
    title: "KDTube",
    backgroundColor: "#0F1419",
    icon: path.join(__dirname, "assets", process.platform === "win32" ? "icon.ico" : "icon.png"),
    // No frame of the system: the bar of the app, with the buttons of the window drawn into it
    titleBarStyle: "hidden",
    titleBarOverlay: { color: "#0F1419", symbolColor: "#C9D1DB", height: BAR_HEIGHT },
    show: false,
  });

  shellView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "shell-preload.js"),
      contextIsolation: true,
      sandbox: true,
      // Only the files of the app are shown in this layer. Without this rule of the web its player
      // gets silence from the files of the disk as soon as a sound option is on.
      webSecurity: false,
      // The pages of the app are never in the background from the user's point of view
      backgroundThrottling: false,
    },
  });
  tubeView = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, "tube-preload.js"),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      autoplayPolicy: "no-user-gesture-required",
    },
  });
  shellView.setBackgroundColor("#0F1419");
  tubeView.setBackgroundColor("#000000");
  win.contentView.addChildView(shellView);
  win.contentView.addChildView(tubeView);

  for (const contents of [shellView.webContents, tubeView.webContents]) {
    contents.on("before-input-event", handleKey);
    // Nothing opens windows of its own. Links to the outside go to the browser of the system.
    contents.setWindowOpenHandler(({ url }) => {
      if (/^https:\/\//.test(url)) shell.openExternal(url);
      return { action: "deny" };
    });
  }
  // The interface of the app stays on its own pages
  shellView.webContents.on("will-navigate", (event) => event.preventDefault());
  // And the other layer stays with YouTube (and Google, for signing in)
  tubeView.webContents.on("will-navigate", (event, url) => {
    let host = "";
    try {
      host = new URL(url).hostname;
    } catch (e) {
      // not an address
    }
    if (!/(^|\.)(youtube\.com|google\.com|youtube-nocookie\.com|youtu\.be)$/.test(host)) event.preventDefault();
  });
  tubeView.webContents.on("did-fail-load", (_event, code, _description, _url, isMainFrame) => {
    // -3: a load that was replaced by another one
    if (isMainFrame && code !== -3) {
      tubeFailed = true;
      update();
    }
  });
  tubeView.webContents.on("did-finish-load", () => {
    applyPixelScale();
    if (tubeFailed) {
      tubeFailed = false;
      update();
    }
  });
  win.on("moved", applyPixelScale);
  screen.on("display-metrics-changed", applyPixelScale);
  allowVideoServers(session.defaultSession);
  // The microphone and the camera stay off; nothing else is asked for by a video page that we'd want
  session.defaultSession.setPermissionRequestHandler((_contents, permission, callback) => {
    callback(permission === "fullscreen" || permission === "clipboard-sanitized-write");
  });

  win.on("resize", layout);
  win.on("enter-full-screen", update);
  win.on("leave-full-screen", update);
  win.on("close", () => {
    if (!win.isFullScreen() && !win.isMaximized() && !win.isMinimized()) {
      settings.set("window", win.getBounds());
    }
  });
  win.on("closed", () => {
    win = null;
    app.quit();
  });

  shellView.webContents.loadFile(path.join(__dirname, "ui", "shell.html"));
  tubeView.webContents.loadURL(option("url") || TV_URL).catch(() => {});

  const show = option("show");
  if (show === "library") mode = "library";
  if (show === "panel") panelOpen = true;

  shellView.webContents.once("did-finish-load", () => {
    update();
    if (settings.get().startFullscreen && option("shot") === null) win.setFullScreen(true);
    if (option("shot") !== null) win.showInactive();
    else win.show();
    focusContent();
    // Updates over the air (see updater.js). --kd-update-url=<address>: another update file, also from the sources
    const updateUrl = option("update-url");
    if (option("shot") === null || updateUrl) {
      updater.start({ win, url: updateUrl, force: updateUrl !== null, delayMs: updateUrl ? 3000 : undefined,
        isEnabled: () => settings.get().updateCheck });
    }
  });

  // --kd-keys=Enter,Down,wait3000,Enter: presses the keys in the page of YouTube, one per second, after --kd-keys-delay
  // (also "swipe:x1/y1/x2/y2" and "tap:x/y": a finger, see touchStep)
  // --kd-click=<css selector>: clicks an element of the interface of the app, 6 seconds after the start
  const click = option("click");
  if (click) {
    setTimeout(() => {
      shellView.webContents.executeJavaScript("(function(){var e=document.querySelector(" + JSON.stringify(click) + ");if(e)e.click();})()").catch(() => {});
    }, 6000);
  }

  const keys = option("keys");
  if (keys) {
    setTimeout(async () => {
      // More than keys: "text:some words" types, "shot:name" saves a picture, "status" notes what the player does
      const base = (option("shot") || "kd").replace(/\.png$/i, "");
      for (const key of keys.split(",")) {
        try {
          if (/^wait\d+$/.test(key)) {
            await new Promise((resolve) => setTimeout(resolve, parseInt(key.slice(4), 10)));
          } else if (key.startsWith("text:")) {
            for (const char of key.slice(5)) {
              tubeView.webContents.sendInputEvent({ type: "keyDown", keyCode: char });
              tubeView.webContents.sendInputEvent({ type: "char", keyCode: char });
              tubeView.webContents.sendInputEvent({ type: "keyUp", keyCode: char });
              await new Promise((resolve) => setTimeout(resolve, 120));
            }
          } else if (key === "guest") {
            // "Watch as guest", wherever the start screen of the day has put it
            const screenText = await tubeView.webContents.executeJavaScript("document.body.innerText");
            if (/Sign in with your phone/.test(screenText)) {
              for (const step of ["Down", "Down", "Down", "Enter"]) {
                pressKey(tubeView.webContents, step);
                await new Promise((resolve) => setTimeout(resolve, 700));
              }
            } else if (/Watch as guest/.test(screenText)) {
              pressKey(tubeView.webContents, "Down");
              await new Promise((resolve) => setTimeout(resolve, 700));
              pressKey(tubeView.webContents, "Enter");
            }
          } else if (key.startsWith("load:")) {
            // Asks the player of the page for another video, without leaving the page
            const result = await tubeView.webContents.executeJavaScript(
              "(function(){var p=document.querySelector('.html5-video-player');if(!p)return 'no player';" +
              "if(typeof p.loadVideoById!=='function')return 'no loadVideoById';p.loadVideoById(" + JSON.stringify(key.slice(5)) + ");return 'asked'})()");
            if (networkLog) networkLog("[status] load: " + result);
          } else if (/^(swipe|tap):/.test(key)) {
            await touchStep(key);
          } else if (key.startsWith("shot:")) {
            fs.writeFileSync(base + "-" + key.slice(5) + ".png", (await tubeView.webContents.capturePage()).toPNG());
          } else if (key === "status") {
            const status = await tubeView.webContents.executeJavaScript(
              "(function(){var v=document.querySelector('video')||{};var p=document.querySelector('.html5-video-player')||{};" +
              "var q='';try{q=p.getPlaybackQuality()}catch(e){}" +
              "return JSON.stringify({url:location.hash.slice(0,40),time:Math.round(v.currentTime||0),duration:Math.round(v.duration||0)," +
              "size:(v.videoWidth||0)+'x'+(v.videoHeight||0),paused:v.paused,ended:v.ended,quality:q," +
              "dropped:v.getVideoPlaybackQuality?v.getVideoPlaybackQuality().droppedVideoFrames:null," +
              "error:(document.body.innerText||'').indexOf('Something went wrong')>=0})})()");
            if (networkLog) networkLog("[status] " + status);
          } else {
            pressKey(tubeView.webContents, key);
          }
        } catch (e) {
          if (networkLog) networkLog("[status] step '" + key + "' failed: " + e.message);
        }
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    }, parseInt(option("keys-delay") || "9000", 10));
  }

  const shot = option("shot");
  if (shot) {
    const consoleLog = shot.replace(/\.png$/i, "") + "-console.log";
    fs.writeFileSync(consoleLog, "");
    networkLog = (line) => {
      try {
        fs.appendFileSync(consoleLog, "[net] " + line + "\n");
      } catch (e) {
        // only a test aid
      }
    };
    tubeView.webContents.on("console-message", (details) => {
      try {
        fs.appendFileSync(consoleLog, "[" + details.level + "] " + String(details.message).slice(0, 600) + "\n");
      } catch (e) {
        // only a test aid
      }
    });
    setTimeout(async () => {
      try {
        const base = shot.replace(/\.png$/i, "");
        fs.writeFileSync(base + "-shell.png", (await shellView.webContents.capturePage()).toPNG());
        if (mode === "tube") fs.writeFileSync(base + "-tube.png", (await tubeView.webContents.capturePage()).toPNG());
        const state = await tubeView.webContents.executeJavaScript(
          "(function(){var v=document.querySelector('video')||{};var p=document.querySelector('.html5-video-player')||{};" +
          "var q=function(n){try{return p[n]()}catch(e){return null}};" +
          "return JSON.stringify({title:document.title,url:location.href,injected:!!window.__kdtubeStarted," +
          "video:{time:v.currentTime,width:v.videoWidth,height:v.videoHeight,paused:v.paused,ready:v.readyState," +
          "filter:v.style?v.style.filter:null,dropped:v.getVideoPlaybackQuality?v.getVideoPlaybackQuality().droppedVideoFrames:null," +
          "frames:v.getVideoPlaybackQuality?v.getVideoPlaybackQuality().totalVideoFrames:null}," +
          "quality:q('getPlaybackQuality'),levels:q('getAvailableQualityLevels')," +
          "support:['video/mp4; codecs=\"avc1.640028\"','video/webm; codecs=\"vp9\"','video/webm; codecs=\"vp09.00.51.08\"'," +
          "'video/mp4; codecs=\"av01.0.12M.08\"','video/mp4; codecs=\"hev1.1.6.L120.90\"','audio/mp4; codecs=\"mp4a.40.2\"'," +
          "'audio/webm; codecs=\"opus\"','audio/mp4; codecs=\"ec-3\"','audio/mp4; codecs=\"ac-3\"'].map(function(t){return t+' = '+MediaSource.isTypeSupported(t)})," +
          "screen:[screen.width,screen.height,innerWidth,innerHeight,devicePixelRatio]," +
          "ua:navigator.userAgent})})()");
        const local = await shellView.webContents.executeJavaScript(
          "(function(){var v=document.getElementById('player-video');return JSON.stringify({classes:document.body.className," +
          "cards:document.querySelectorAll('.card').length,time:v.currentTime,width:v.videoWidth,height:v.videoHeight," +
          "paused:v.paused,error:v.error?v.error.message:null,filter:v.style.filter})})()");
        let found = option("cast-test") !== null ? await cast.discover().catch((e) => String(e)) : undefined;
        // --kd-cast-code=<TV code>: sends what plays here to the screen that shows the code
        if (option("cast-code")) {
          const playing = JSON.parse(state);
          const id = /[?&]v=([A-Za-z0-9_-]{6,})/.exec(playing.url || "");
          found = await cast.send({ code: option("cast-code") }, id ? id[1] : "", playing.video.time).then((name) => "sent to " + name, (e) => "FAILED: " + e.message);
        }
        fs.writeFileSync(base + "-info.json", JSON.stringify({ tube: JSON.parse(state), shell: JSON.parse(local), memory: memory.getInfo(app), cast: found }, null, 2));
      } catch (e) {
        fs.writeFileSync(shot + ".error.txt", String(e && e.stack ? e.stack : e));
      }
      app.quit();
    }, parseInt(option("shot-delay") || "12000", 10));
  }
}

app.on("second-instance", () => {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.focus();
});

app.whenReady().then(() => {
  setupIpc();
  createWindow();
});

app.on("window-all-closed", () => app.quit());

// The session cache is emptied on the way out
let cleaned = false;
app.on("before-quit", (event) => {
  if (cleaned) return;
  cleaned = true;
  event.preventDefault();
  const done = () => app.quit();
  // NOTE: never longer than a moment: closing the app has to feel instant
  Promise.race([memory.cleanup(session.defaultSession), new Promise((resolve) => setTimeout(resolve, 1500))]).then(done, done);
});
