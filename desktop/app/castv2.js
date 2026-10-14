"use strict";
// The Chromecast way of talking to a device (Chromecast, Google TV, Android TV, Nest Hub).
// These devices don't offer their YouTube app the DIAL way (see cast.js): the app has to be asked for
// through the cast channel of the device. Only what's needed for that is here:
//   1) is there a YouTube app?   2) start it (if it isn't running)   3) ask it for its screen id
// The video itself is started the same way as on every other screen (the "lounge" service, see cast.js).
//
// The channel: TLS to port 8009, messages are a length (4 bytes) and a small protobuf (CastMessage).
const tls = require("tls");

const PORT = 8009;
const YOUTUBE_APP_ID = "233637DE";
const SENDER = "sender-kdtube";
const RECEIVER = "receiver-0";
const NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
const NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
const NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
const NS_YOUTUBE = "urn:x-cast:com.google.youtube.mdx";
const MAX_MESSAGE_BYTES = 256 * 1024;

function varint(value) {
  const bytes = [];
  let rest = value;
  while (rest > 127) {
    bytes.push((rest & 127) | 128);
    rest = Math.floor(rest / 128);
  }
  bytes.push(rest);
  return Buffer.from(bytes);
}

function text(field, value) {
  const data = Buffer.from(value, "utf8");
  return Buffer.concat([varint((field << 3) | 2), varint(data.length), data]);
}

/** CastMessage: 1 protocol version, 2 source, 3 destination, 4 namespace, 5 payload type (0: text), 6 the text */
function encode(destination, namespace, payload) {
  const message = Buffer.concat([
    varint(1 << 3), varint(0), text(2, SENDER), text(3, destination), text(4, namespace), varint(5 << 3), varint(0), text(6, JSON.stringify(payload)),
  ]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(message.length, 0);
  return Buffer.concat([length, message]);
}

function decode(data) {
  const fields = {};
  let position = 0;
  const readVarint = () => {
    let value = 0;
    let factor = 1;
    while (position < data.length) {
      const byte = data[position++];
      value += (byte & 127) * factor;
      if (byte < 128) break;
      factor *= 128;
    }
    return value;
  };
  while (position < data.length) {
    const tag = readVarint();
    const field = tag >>> 3;
    const type = tag & 7;
    if (type === 0) {
      fields[field] = readVarint();
    } else if (type === 2) {
      const length = readVarint();
      fields[field] = data.subarray(position, position + length);
      position += length;
    } else if (type === 5) {
      position += 4;
    } else if (type === 1) {
      position += 8;
    } else {
      break; // not a message of this protocol
    }
  }
  const string = (field) => (Buffer.isBuffer(fields[field]) ? fields[field].toString("utf8") : "");
  let payload = null;
  try {
    payload = JSON.parse(string(6));
  } catch (e) {
    // a binary message (the device proving who it is): not needed here
  }
  return { source: string(2), namespace: string(4), payload };
}

/**
 * One short conversation with the device.
 * @param talk async (channel) => result. channel.send(destination, namespace, payload),
 *   channel.wait(test, what) resolves with the first coming message the test accepts.
 */
function converse(host, timeoutMs, talk) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    let waiting = [];
    let done = false;
    // NOTE: the devices identify themselves with a certificate of their own making, like every Chromecast does.
    // Nothing secret is sent: the question for the YouTube app and its public screen id.
    const socket = tls.connect({ host, port: PORT, rejectUnauthorized: false });
    const finish = (error, result) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      socket.destroy();
      for (const item of waiting) item.reject(error || new Error("closed"));
      waiting = [];
      if (error) reject(error);
      else resolve(result);
    };
    const timer = setTimeout(() => finish(new Error("The device didn't answer in time")), timeoutMs);
    const channel = {
      send: (destination, namespace, payload) => socket.write(encode(destination, namespace, payload)),
      wait: (test, what) => new Promise((accept, refuse) => waiting.push({ test, resolve: accept, reject: refuse, what })),
    };
    socket.on("error", (e) => finish(new Error("Can't reach the device (" + e.message + ")")));
    socket.on("close", () => finish(new Error("The device closed the connection")));
    socket.on("data", (data) => {
      buffer = Buffer.concat([buffer, data]);
      while (buffer.length >= 4) {
        const length = buffer.readUInt32BE(0);
        if (length > MAX_MESSAGE_BYTES) return finish(new Error("Unexpected answer of the device"));
        if (buffer.length < 4 + length) break;
        const message = decode(buffer.subarray(4, 4 + length));
        buffer = buffer.subarray(4 + length);
        if (!message.payload) continue;
        if (message.namespace === NS_HEARTBEAT && message.payload.type === "PING") {
          channel.send(RECEIVER, NS_HEARTBEAT, { type: "PONG" });
          continue;
        }
        for (const item of waiting.slice()) {
          let accepted = false;
          try {
            accepted = item.test(message);
          } catch (e) {
            accepted = false;
          }
          if (accepted) {
            waiting = waiting.filter((other) => other !== item);
            item.resolve(message);
          }
        }
      }
    });
    socket.on("secureConnect", () => {
      channel.send(RECEIVER, NS_CONNECTION, { type: "CONNECT" });
      Promise.resolve().then(() => talk(channel)).then((result) => finish(null, result), (e) => finish(e));
    });
  });
}

function findYouTube(status) {
  const applications = (status && status.applications) || [];
  return applications.find((app) => app.appId === YOUTUBE_APP_ID) || null;
}

/** @returns {Promise<{name: string|null, hasYouTube: boolean, running: string|null}>} changes nothing on the device */
function probe(host, timeoutMs) {
  return converse(host, timeoutMs || 5000, async (channel) => {
    const statusAnswer = channel.wait((m) => m.namespace === NS_RECEIVER && m.payload.type === "RECEIVER_STATUS" && m.payload.requestId === 1);
    const availabilityAnswer = channel.wait((m) => m.namespace === NS_RECEIVER && m.payload.requestId === 2);
    channel.send(RECEIVER, NS_RECEIVER, { type: "GET_STATUS", requestId: 1 });
    channel.send(RECEIVER, NS_RECEIVER, { type: "GET_APP_AVAILABILITY", appId: [YOUTUBE_APP_ID], requestId: 2 });
    const status = (await statusAnswer).payload.status || {};
    const availability = (await availabilityAnswer).payload.availability || {};
    const app = (status.applications || [])[0];
    return {
      hasYouTube: availability[YOUTUBE_APP_ID] === "APP_AVAILABLE" || !!findYouTube(status),
      running: app ? app.displayName || app.appId : null,
    };
  });
}

/** Starts the YouTube app of the device (if it isn't running) and returns its screen id */
function screenId(host) {
  return converse(host, 40000, async (channel) => {
    const statusAnswer = channel.wait((m) => m.namespace === NS_RECEIVER && m.payload.type === "RECEIVER_STATUS" && m.payload.requestId === 1);
    channel.send(RECEIVER, NS_RECEIVER, { type: "GET_STATUS", requestId: 1 });
    let app = findYouTube((await statusAnswer).payload.status);
    if (!app) {
      // The answer to the request, or the news that the app is there now (some devices answer with the old state first)
      const launched = channel.wait((m) => m.namespace === NS_RECEIVER &&
        (m.payload.type === "LAUNCH_ERROR" || (m.payload.type === "RECEIVER_STATUS" && findYouTube(m.payload.status))));
      channel.send(RECEIVER, NS_RECEIVER, { type: "LAUNCH", appId: YOUTUBE_APP_ID, requestId: 2 });
      const answer = (await launched).payload;
      if (answer.type === "LAUNCH_ERROR") throw new Error("The device can't start YouTube (" + (answer.reason || "no reason given") + ")");
      app = findYouTube(answer.status);
    }
    channel.send(app.transportId, NS_CONNECTION, { type: "CONNECT" });
    // The app needs a moment after its start: ask until it knows its id
    for (let i = 0; i < 12; i++) {
      const answer = channel.wait((m) => m.namespace === NS_YOUTUBE && m.payload.type === "mdxSessionStatus");
      channel.send(app.transportId, NS_YOUTUBE, { type: "getMdxSessionStatus" });
      const result = await Promise.race([answer, new Promise((accept) => setTimeout(() => accept(null), 2500))]);
      const id = result && result.payload.data && result.payload.data.screenId;
      if (id) return id;
    }
    throw new Error("The YouTube app of the device didn't say how to reach it");
  });
}

module.exports = { probe, screenId, PORT };
