"use strict";
// The link between the app and the YouTube page. The page gets four functions and nothing else.
const { contextBridge, ipcRenderer, webFrame } = require("electron");

const isYouTube = /(^|\.)youtube\.com$/.test(location.hostname);

if (isYouTube && window.top === window) {
  let settings = ipcRenderer.sendSync("kd:settings-sync");
  const listeners = [];

  ipcRenderer.on("kd:settings", (_event, next) => {
    settings = next;
    for (const listener of listeners) {
      try {
        listener(next);
      } catch (e) {
        // a listener of the page: its problem
      }
    }
  });

  contextBridge.exposeInMainWorld("kdtube", {
    settings: () => settings,
    onSettings: (listener) => {
      if (typeof listener === "function") listeners.push(listener);
    },
    /** The parts of a video to skip: [{start, end, label}] */
    segments: (videoId) => ipcRenderer.invoke("kd:segments", String(videoId)),
    playing: (isPlaying) => ipcRenderer.send("kd:playing", !!isPlaying),
    /** The "back" button of the remote */
    back: () => ipcRenderer.send("kd:page-back"),
    /** A line for the log of the player (kdtube-player.log in the data folder of the app) */
    log: (line) => ipcRenderer.send("kd:log", String(line).slice(0, 500)),
  });

  // The scripts that run inside of the page. Before its own ones: they have to see every answer of the server.
  const code = ipcRenderer.sendSync("kd:inject-code");
  if (code) webFrame.executeJavaScript(code);
}
