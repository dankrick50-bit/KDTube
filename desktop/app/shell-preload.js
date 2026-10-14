"use strict";
// What the interface of the app (ui/shell.html) may ask the app for.
const { contextBridge, ipcRenderer } = require("electron");

function listen(channel) {
  return (listener) => ipcRenderer.on(channel, (_event, value) => listener(value));
}

contextBridge.exposeInMainWorld("kd", {
  platform: process.platform,
  settings: () => ipcRenderer.sendSync("kd:settings-sync"),
  set: (key, value) => ipcRenderer.invoke("kd:set", key, value),
  command: (name, argument) => ipcRenderer.send("kd:command", name, argument),
  memory: () => ipcRenderer.invoke("kd:memory"),
  library: () => ipcRenderer.invoke("kd:library"),
  addFolder: () => ipcRenderer.invoke("kd:add-folder"),
  openExternal: (file) => ipcRenderer.invoke("kd:open-external", file),
  showInFolder: (file) => ipcRenderer.invoke("kd:show-in-folder", file),
  signOut: () => ipcRenderer.invoke("kd:sign-out"),
  castDevices: () => ipcRenderer.invoke("kd:cast-devices"),
  castSend: (device) => ipcRenderer.invoke("kd:cast-send", device),
  castForget: (screenId) => ipcRenderer.invoke("kd:cast-forget", screenId),
  onState: listen("kd:state"),
  onSettings: listen("kd:settings"),
  onBack: listen("kd:back"),
});
