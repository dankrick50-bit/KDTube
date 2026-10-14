"use strict";
// The archive formats the packaging needs, written by hand so nothing has to be installed and the
// same scripts run on Windows and Linux: zip (read and write), tar, ar (a .deb is an ar archive of
// two tar files).
const fs = require("fs");
const zlib = require("zlib");

// ---------- zip: read ----------

const UNIX_SYMLINK = 0o120000;
const UNIX_TYPE_MASK = 0o170000;

/**
 * @returns {{name: string, mode: number, isDirectory: boolean, isSymlink: boolean, read: () => Buffer}[]}
 *   mode: the permissions the file had on Linux / macOS, or 0 when the archive doesn't say
 */
function readZip(file) {
  const buffer = fs.readFileSync(file);
  let end = buffer.length - 22;
  while (end >= 0 && buffer.readUInt32LE(end) !== 0x06054b50) end--;
  if (end < 0) throw new Error("Not a zip file: " + file);
  const count = buffer.readUInt16LE(end + 10);
  let offset = buffer.readUInt32LE(end + 16);
  if (offset === 0xffffffff || count === 0xffff) throw new Error("zip64 isn't supported: " + file);

  const entries = [];
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error("Broken zip directory: " + file);
    const madeBy = buffer.readUInt16LE(offset + 4);
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const attributes = buffer.readUInt32LE(offset + 38);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.toString("utf8", offset + 46, offset + 46 + nameLength);
    const mode = madeBy >> 8 === 3 ? (attributes >>> 16) & 0xffff : 0;

    entries.push({
      name,
      mode: mode & 0o7777,
      isDirectory: name.endsWith("/"),
      isSymlink: (mode & UNIX_TYPE_MASK) === UNIX_SYMLINK,
      read() {
        const start = localOffset + 30 + buffer.readUInt16LE(localOffset + 26) + buffer.readUInt16LE(localOffset + 28);
        const data = buffer.subarray(start, start + compressedSize);
        if (method === 0) return Buffer.from(data);
        if (method === 8) return zlib.inflateRawSync(data);
        throw new Error("Unknown compression " + method + " of " + name);
      },
    });
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

// ---------- zip: write ----------

function dosTime(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

/**
 * Writes the entries one by one: only one file is in memory at a time.
 * @param {string} file
 * @param {Iterable<{name: string, data: Buffer}>} entries
 */
function writeZip(file, entries) {
  const out = fs.openSync(file, "w");
  const directory = [];
  const { time, day } = dosTime(new Date());
  let position = 0;
  const write = (buffer) => {
    fs.writeSync(out, buffer);
    position += buffer.length;
  };

  try {
    for (const entry of entries) {
      const name = Buffer.from(entry.name, "utf8");
      const deflated = zlib.deflateRawSync(entry.data, { level: 6 });
      const stored = deflated.length >= entry.data.length;
      const body = stored ? entry.data : deflated;
      const crc = zlib.crc32(entry.data);
      if (entry.data.length >= 0xffffffff || position >= 0xffffffff) throw new Error("Too big for this zip writer: " + entry.name);

      const header = Buffer.alloc(30);
      header.writeUInt32LE(0x04034b50, 0);
      header.writeUInt16LE(20, 4);
      header.writeUInt16LE(0x0800, 6); // the name is utf-8
      header.writeUInt16LE(stored ? 0 : 8, 8);
      header.writeUInt16LE(time, 10);
      header.writeUInt16LE(day, 12);
      header.writeUInt32LE(crc, 14);
      header.writeUInt32LE(body.length, 18);
      header.writeUInt32LE(entry.data.length, 22);
      header.writeUInt16LE(name.length, 26);
      directory.push({ name, header, offset: position });
      write(header);
      write(name);
      write(body);
    }

    const start = position;
    for (const item of directory) {
      const record = Buffer.alloc(46);
      record.writeUInt32LE(0x02014b50, 0);
      record.writeUInt16LE(20, 4);
      item.header.copy(record, 6, 4, 30); // the same fields as in the local header
      record.writeUInt32LE(item.offset, 42);
      write(record);
      write(item.name);
    }
    const tail = Buffer.alloc(22);
    tail.writeUInt32LE(0x06054b50, 0);
    tail.writeUInt16LE(directory.length, 8);
    tail.writeUInt16LE(directory.length, 10);
    tail.writeUInt32LE(position - start, 12);
    tail.writeUInt32LE(start, 16);
    write(tail);
  } finally {
    fs.closeSync(out);
  }
  return position;
}

// ---------- tar ----------

function tarHeader(name, size, mode, type, mtime, linkName) {
  const header = Buffer.alloc(512);
  if (Buffer.byteLength(name) > 100) throw new Error("Path is too long for this writer: " + name);
  header.write(name, 0, "utf8");
  header.write(mode.toString(8).padStart(7, "0") + "\0", 100, "ascii");
  header.write("0000000\0", 108, "ascii"); // owner: root
  header.write("0000000\0", 116, "ascii");
  header.write(size.toString(8).padStart(11, "0") + "\0", 124, "ascii");
  header.write(mtime.toString(8).padStart(11, "0") + "\0", 136, "ascii");
  header.write("        ", 148, "ascii"); // the checksum is calculated with spaces in its place
  header.write(type, 156, "ascii");
  if (linkName) header.write(linkName, 157, "utf8");
  header.write("ustar\0" + "00", 257, "ascii");
  header.write("root", 265, "ascii");
  header.write("root", 297, "ascii");

  let sum = 0;
  for (const byte of header) sum += byte;
  header.write(sum.toString(8).padStart(6, "0") + "\0 ", 148, "ascii");
  return header;
}

/**
 * @param {{name: string, data?: Buffer, mode?: number, link?: string}[]} entries  name: "./usr/bin/x";
 *   an entry with "link" is a symbolic link to it. The folders are added by themselves.
 */
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
    if (entry.link) {
      chunks.push(tarHeader(entry.name, 0, 0o777, "2", mtime, entry.link));
      continue;
    }
    chunks.push(tarHeader(entry.name, entry.data.length, entry.mode || 0o644, "0", mtime));
    chunks.push(entry.data);
    const padding = (512 - (entry.data.length % 512)) % 512;
    if (padding) chunks.push(Buffer.alloc(padding));
  }

  chunks.push(Buffer.alloc(1024)); // the end of the archive
  return Buffer.concat(chunks);
}

// ---------- ar ----------

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

/** Text files of a package: unix line ends, whatever the system the build runs on */
function text(value) {
  return Buffer.from(String(value).replace(/\r\n/g, "\n"), "utf8");
}

module.exports = { readZip, writeZip, tar, ar, text };
