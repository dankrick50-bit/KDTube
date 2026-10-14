"use strict";
// Casting: sends the video that plays here to a TV, a stick or a console that has the YouTube app.
// The same ways as in the Android app (common/.../misc/cast):
//   - devices of the home network are found by themselves:
//       smart TVs, Fire TV sticks, consoles: DIAL (the way the YouTube app of a phone finds a TV)
//       Chromecast, Google TV, Android TV: they announce themselves (mDNS) and are asked through
//       their cast channel (see castv2.js)
//   - any screen anywhere can be linked with the code it shows: YouTube > Settings > Link with TV code
// The screen plays the video with its own YouTube app: nothing is streamed through this computer.
//
// The other direction needs no code here: this app shows YouTube's TV interface, which is a cast
// target itself. Settings (the gear in YouTube) > Link with TV code.
const dgram = require("dgram");
const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const os = require("os");
const path = require("path");
const { net } = require("electron");
const castv2 = require("./castv2");

const SSDP_ADDRESS = "239.255.255.250";
const SSDP_PORT = 1900;
const SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1";
const MDNS_ADDRESS = "224.0.0.251";
const MDNS_PORT = 5353;
const CAST_SERVICE = "_googlecast._tcp.local";
// Where a Chromecast-like device describes itself
const CAST_DESCRIPTION = ":8008/ssdp/device-desc.xml";
// Fire TV answers the question about its YouTube app only to YouTube (403 otherwise)
const DIAL_ORIGIN = "https://www.youtube.com";
const LOCAL_ADDRESS = /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)\d{1,3}(\.\d{1,3}){1,2}$/;
const LOUNGE = "https://www.youtube.com/api/lounge";
const BIND = LOUNGE + "/bc/bind?VER=8&CVER=1&auth_failure_option=send_error";

let storeFile = null;
let remembered = []; // the screens that were linked with a code: {name, screenId}

function init(app) {
  storeFile = path.join(app.getPath("userData"), "kdtube-cast-devices.json");
  try {
    const stored = JSON.parse(fs.readFileSync(storeFile, "utf8"));
    if (Array.isArray(stored)) remembered = stored.filter((item) => item && item.screenId);
  } catch (e) {
    // nothing remembered yet
  }
}

function remember(device) {
  remembered = remembered.filter((item) => item.screenId !== device.screenId);
  remembered.unshift({ name: device.name, screenId: device.screenId });
  remembered = remembered.slice(0, 8);
  try {
    fs.writeFileSync(storeFile, JSON.stringify(remembered, null, 2));
  } catch (e) {
    // only until the app closes then
  }
}

function forget(screenId) {
  remembered = remembered.filter((item) => item.screenId !== screenId);
  try {
    fs.writeFileSync(storeFile, JSON.stringify(remembered, null, 2));
  } catch (e) {
    // see above
  }
}

function form(values) {
  return Object.keys(values).map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(values[key])).join("&");
}

async function request(url, options) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await net.fetch(url, Object.assign({ signal: controller.signal }, options));
    return { status: response.status, headers: response.headers, body: await response.text() };
  } finally {
    clearTimeout(timer);
  }
}

function post(url, body, headers) {
  return request(url, { method: "POST", body, headers: Object.assign({ "Content-Type": "application/x-www-form-urlencoded" }, headers) });
}

// ---------- the home network ----------

/** @returns the host of an http address of the local network, null for anything else */
function localHost(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "http:" && LOCAL_ADDRESS.test(parsed.hostname) ? parsed.hostname : null;
  } catch (e) {
    return null;
  }
}

/** A request to a device of the home network (never leaves it: see localHost) */
function local(url, method, body) {
  return new Promise((resolve, reject) => {
    if (!localHost(url)) return reject(new Error("Not a device of this network"));
    const headers = { Origin: DIAL_ORIGIN };
    if (body) {
      headers["Content-Type"] = "text/plain; charset=utf-8";
      headers["Content-Length"] = Buffer.byteLength(body);
    }
    const outgoing = http.request(url, { method: method || "GET", headers, timeout: 5000 }, (response) => {
      const parts = [];
      let size = 0;
      response.on("data", (part) => {
        size += part.length;
        if (size <= 256 * 1024) parts.push(part);
      });
      response.on("end", () => resolve({ status: response.statusCode, headers: response.headers, body: Buffer.concat(parts).toString("utf8") }));
      response.on("error", reject);
    });
    outgoing.on("timeout", () => outgoing.destroy(new Error("The device didn't answer in time")));
    outgoing.on("error", reject);
    outgoing.end(body || undefined);
  });
}

/** The question "who is a Chromecast?" (a DNS question for the pointers of the service, answers wanted directly) */
function castQuestion() {
  const parts = [Buffer.from([0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0])];
  for (const label of CAST_SERVICE.split(".")) {
    parts.push(Buffer.from([label.length]), Buffer.from(label, "ascii"));
  }
  parts.push(Buffer.from([0, 0, 12, 0x80, 1]));
  return Buffer.concat(parts);
}

/**
 * Asks the network who speaks DIAL (smart TVs, Fire TV...) and who is a Chromecast (Google TV, Android TV...)
 * @returns the addresses of the descriptions of the devices
 */
function search(timeoutMs) {
  return new Promise((resolve) => {
    const locations = new Set();
    const sockets = [];
    const questions = [
      {
        message: Buffer.from("M-SEARCH * HTTP/1.1\r\nHOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " +
          SEARCH_TARGET + "\r\n\r\n"),
        address: SSDP_ADDRESS,
        port: SSDP_PORT,
        answer: (data) => {
          const match = /^location:\s*(\S+)\s*$/im.exec(data.toString("utf8"));
          if (match) locations.add(match[1]);
        },
      },
      {
        message: castQuestion(),
        address: MDNS_ADDRESS,
        port: MDNS_PORT,
        // Who answers is one: its name and its apps are asked for later
        answer: (data, from) => locations.add("http://" + from.address + CAST_DESCRIPTION),
      },
    ];
    // One socket per network card: the question has to leave through the one the TV is behind
    const addresses = [];
    for (const list of Object.values(os.networkInterfaces())) {
      for (const item of list || []) {
        if (item.family === "IPv4" && !item.internal) addresses.push(item.address);
      }
    }
    for (const address of addresses) {
      for (const question of questions) {
        try {
          const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
          sockets.push(socket);
          socket.on("message", question.answer);
          socket.on("error", () => {});
          socket.bind(0, address, () => {
            // UDP may lose a packet: say it twice
            socket.send(question.message, question.port, question.address, () => {});
            setTimeout(() => {
              try {
                socket.send(question.message, question.port, question.address, () => {});
              } catch (e) {
                // closed in the meantime
              }
            }, 400);
          });
        } catch (e) {
          // a network card that doesn't want to: the others may
        }
      }
    }
    setTimeout(() => {
      for (const socket of sockets) {
        try {
          socket.close();
        } catch (e) {
          // was never open
        }
      }
      resolve(Array.from(locations));
    }, timeoutMs);
  });
}

async function describe(location) {
  // NOTE: only devices of the local network answer the search; still, nothing but http to an address of it
  const host = localHost(location);
  if (!host) return null;
  const description = await local(location);
  const found = /<friendlyName>([^<]*)<\/friendlyName>/.exec(description.body);
  const name = found ? unescapeXml(found[1].trim()) || "TV" : "TV";
  let appUrl = description.headers["application-url"];
  if (appUrl && localHost(appUrl)) {
    if (!appUrl.endsWith("/")) appUrl += "/";
    const app = await local(appUrl + "YouTube");
    if (app.status === 200) {
      const screenId = /<screenId>([^<]+)<\/screenId>/.exec(app.body);
      return { name, screenId: screenId ? screenId[1] : null, appUrl };
    }
  }
  // No YouTube app the DIAL way. A Chromecast-like device has it behind its cast channel
  if (location.endsWith(CAST_DESCRIPTION) && description.status === 200) {
    // No answer: busy or just waking up. The device is there, the answer comes when it's used
    const cast = await castv2.probe(host).catch(() => ({ hasYouTube: true }));
    if (cast.hasYouTube) return { name, screenId: null, castHost: host };
  }
  return null;
}

/** 75&quot; QLED -> 75" QLED */
function unescapeXml(text) {
  return text.replace(/&(quot|apos|amp|lt|gt|#(\d+)|#x([0-9a-fA-F]+));/g, (match, name, decimal, hex) => {
    if (decimal) return String.fromCodePoint(parseInt(decimal, 10));
    if (hex) return String.fromCodePoint(parseInt(hex, 16));
    return { quot: "\"", apos: "'", amp: "&", lt: "<", gt: ">" }[name];
  });
}

/** @returns {Promise<object[]>} the devices of the network with a YouTube app, then the linked ones */
async function discover() {
  // All at once: a device that is slow to answer doesn't hold up the others
  const described = await Promise.all((await search(3000)).map((location) => describe(location).catch((e) => {
    if (process.env.KD_CAST_DEBUG) console.log("cast: " + location + ": " + e.message);
    return null;
  })));
  const result = [];
  for (const device of described) {
    // a device that answered the search and not the question: skip it
    if (!device) continue;
    const key = device.appUrl || device.castHost;
    if (result.some((item) => (item.appUrl || item.castHost) === key)) continue;
    result.push({ name: device.name, screenId: device.screenId, appUrl: device.appUrl, castHost: device.castHost, kind: "network" });
  }
  result.sort((left, right) => left.name.localeCompare(right.name));
  for (const device of remembered) {
    if (!result.some((item) => item.screenId === device.screenId)) result.push({ name: device.name, screenId: device.screenId, kind: "linked" });
  }
  return result;
}

/** YouTube isn't open on the device: asks the device to start it and waits for its screen id */
async function startApp(device) {
  const url = device.appUrl + "YouTube";
  const read = async () => {
    const match = /<screenId>([^<]+)<\/screenId>/.exec((await local(url)).body);
    return match ? match[1] : null;
  };
  let screenId = await read();
  if (screenId) return screenId;
  const started = await local(url, "POST", "pairingCode=" + crypto.randomUUID());
  if (started.status >= 400) throw new Error("The device refused to start YouTube (" + started.status + ")");
  for (let i = 0; i < 16; i++) {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    screenId = await read();
    if (screenId) return screenId;
  }
  throw new Error("YouTube didn't start on the device");
}

// ---------- the remote control service of YouTube ("lounge") ----------

/** @returns the screen that shows this code, or null (wrong or expired code) */
async function findScreen(code) {
  const digits = String(code || "").replace(/[^0-9a-zA-Z]/g, "");
  if (!digits) return null;
  const response = await post(LOUNGE + "/pairing/get_screen", form({ pairing_code: digits }));
  if (response.status >= 400) return null;
  try {
    const screen = JSON.parse(response.body).screen;
    return { name: screen.name || "TV", screenId: screen.screenId, kind: "linked" };
  } catch (e) {
    throw new Error("Unexpected answer of the pairing service (" + response.status + ")");
  }
}

async function play(screenId, videoId, positionSeconds) {
  const tokenResponse = await post(LOUNGE + "/pairing/get_lounge_token_batch", form({ screen_ids: screenId }));
  let token = null;
  try {
    token = JSON.parse(tokenResponse.body).screens[0].loungeToken;
  } catch (e) {
    throw new Error("The screen isn't available anymore: link it again");
  }
  const headers = { "X-YouTube-LoungeId-Token": token, Origin: "https://www.youtube.com" };

  // 1. a session with the screen
  const session = await post(BIND + "&RID=1", form({
    app: "web", "mdx-version": "3", name: "KDTube (" + os.hostname() + ")", id: crypto.randomUUID(), device: "REMOTE_CONTROL",
    capabilities: "que,dsdtr,atp,mus", magnaKey: "cloudPairedDevice", ui: "false", theme: "cl",
  }), headers);
  const sid = /\["c","([^"]+)"/.exec(session.body);
  const gsid = /\["S","([^"]+)"/.exec(session.body);
  if (session.status === 401 || session.status === 403) throw new Error("The screen has to be linked again (the access has expired)");
  if (session.status !== 200 || !sid || !gsid) throw new Error("Can't connect to the screen (" + session.status + ")");

  // 2. the command: play this, from here
  const command = await post(BIND + "&RID=2&SID=" + encodeURIComponent(sid[1]) + "&gsessionid=" + encodeURIComponent(gsid[1]), form({
    count: "1", ofs: "0", req0__sc: "setPlaylist", req0_videoId: videoId, req0_videoIds: videoId, req0_currentIndex: "0",
    req0_currentTime: String(Math.max(0, Math.floor(positionSeconds || 0))), req0_audioOnly: "false", req0_listId: "",
  }), headers);
  if (command.status !== 200) throw new Error("The screen didn't accept the video (" + command.status + ")");
}

/**
 * @param device one of discover(), or {code: "..."} for a screen that shows a TV code
 * @returns the name of the screen that plays the video now
 */
async function send(device, videoId, positionSeconds) {
  if (!/^[\w-]{6,20}$/.test(String(videoId))) throw new Error("Nothing is playing that could be sent");
  let target = device;
  if (device.code) {
    target = await findScreen(device.code);
    if (!target) throw new Error("No screen shows this code. On the TV: YouTube > Settings > Link with TV code");
    remember(target);
  }
  let screenId = target.screenId;
  // A device of this network: its app could be closed, or started again with another id
  if (target.appUrl) {
    screenId = await startApp(target);
  } else if (target.castHost) {
    if (!LOCAL_ADDRESS.test(String(target.castHost))) throw new Error("Not a device of this network");
    screenId = await castv2.screenId(target.castHost);
  }
  if (!screenId) throw new Error("The device doesn't say how to reach its YouTube app");
  await play(screenId, String(videoId), positionSeconds);
  return target.name || "the screen";
}

module.exports = { init, discover, send, forget };
