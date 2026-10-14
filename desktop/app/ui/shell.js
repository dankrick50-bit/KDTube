"use strict";
// The interface of the app: the bar, the settings panel, "Device media" and its player.
(function () {
  const kd = window.kd;
  const $ = (id) => document.getElementById(id);
  const body = document.body;
  let settings = kd.settings();
  let state = { mode: "tube", panelOpen: false, fullscreen: false };

  // ---------- the bar ----------

  $("tab-tube").addEventListener("click", () => (state.mode === "tube" ? kd.command("home") : kd.command("mode", "tube")));
  $("tab-library").addEventListener("click", () => kd.command("mode", "library"));
  $("btn-back").addEventListener("click", () => kd.command("back"));
  $("btn-reload").addEventListener("click", () => (state.mode === "library" ? loadLibrary() : kd.command("reload")));
  $("btn-panel").addEventListener("click", () => kd.command("panel"));
  $("btn-fullscreen").addEventListener("click", () => kd.command("fullscreen"));
  $("panel-close").addEventListener("click", () => kd.command("panel", false));
  $("offline-retry").addEventListener("click", () => kd.command("reload"));
  $("offline-library").addEventListener("click", () => kd.command("mode", "library"));

  kd.onState((next) => {
    const wasLibrary = state.mode === "library";
    state = next;
    body.classList.toggle("mode-tube", next.mode === "tube");
    body.classList.toggle("mode-library", next.mode === "library");
    body.classList.toggle("panel-open", next.panelOpen);
    body.classList.toggle("fullscreen", next.fullscreen);
    body.classList.toggle("tube-failed", !!next.tubeFailed);
    document.documentElement.style.setProperty("--panel", next.panelWidth + "px");
    $("about").textContent = "KDTube " + next.version + " for the PC. F2 settings, F11 full screen, Esc back.";
    if (next.mode === "library" && !wasLibrary && !libraryLoaded) loadLibrary();
    if (next.mode !== "library") closePlayer();
    if (next.panelOpen) refreshMemory();
  });

  // ---------- the settings ----------

  function renderSettings() {
    document.querySelectorAll("input[type=checkbox][data-key]").forEach((input) => {
      input.checked = !!settings[input.dataset.key];
    });
    document.querySelectorAll(".segmented[data-key]").forEach((group) => {
      group.querySelectorAll("button").forEach((button) => {
        button.classList.toggle("on", button.dataset.value === settings[group.dataset.key]);
      });
    });
    const categories = settings.sponsorCategories || [];
    $("sponsor-categories").classList.toggle("disabled", !settings.sponsorblock);
    $("sponsor-categories").querySelectorAll("button").forEach((button) => {
      button.classList.toggle("on", categories.includes(button.dataset.category));
    });
    applyToPlayer();
  }

  function change(key, value) {
    settings[key] = value;
    renderSettings();
    kd.set(key, value);
    if (key === "allDrives") loadLibrary();
  }

  document.querySelectorAll("input[type=checkbox][data-key]").forEach((input) => {
    input.addEventListener("change", () => change(input.dataset.key, input.checked));
  });
  document.querySelectorAll(".segmented[data-key]").forEach((group) => {
    group.addEventListener("click", (event) => {
      const button = event.target.closest("button");
      if (button) change(group.dataset.key, button.dataset.value);
    });
  });
  $("sponsor-categories").addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button) return;
    const categories = (settings.sponsorCategories || []).slice();
    const index = categories.indexOf(button.dataset.category);
    if (index >= 0) categories.splice(index, 1);
    else categories.push(button.dataset.category);
    change("sponsorCategories", categories);
  });
  $("sign-out").addEventListener("click", () => kd.signOut());

  kd.onSettings((next) => {
    settings = next;
    renderSettings();
  });

  // ---------- memory ----------

  const megabytes = (bytes) => Math.round(bytes / 1048576).toLocaleString() + " MB";
  let memoryTimer = 0;

  async function refreshMemory() {
    clearTimeout(memoryTimer);
    if (!state.panelOpen) return;
    const info = await kd.memory();
    if (info) {
      const used = info.totalBytes - info.freeBytes;
      $("memory-bar").style.width = Math.round((used / info.totalBytes) * 100) + "%";
      $("memory-app").textContent = "KDTube holds " + megabytes(info.appBytes) + " of " + megabytes(info.totalBytes) +
        " (" + megabytes(info.freeBytes) + " free on this computer).";
      $("memory-scratch").textContent = "On the disk instead of in memory: " + megabytes(info.scratchBytes) +
        ". This area is emptied when the app closes.";
      if (info.limits) {
        $("memory-limits").textContent = "Limits for this computer: " + info.limits.videoMb + " MB of video and " +
          info.limits.audioMb + " MB of sound ahead in memory, up to " + info.limits.cacheMb + " MB on the disk.";
      }
    }
    memoryTimer = setTimeout(refreshMemory, 3000);
  }

  // ---------- device media ----------

  let files = [];
  let libraryLoaded = false;
  let libraryBusy = false;
  let kind = "all";
  let query = "";

  const ICON_VIDEO = "M10 16.5l6-4.5-6-4.5v9zM12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z";
  const ICON_MUSIC = "M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z";

  function fileUrl(file) {
    // Each part of the path on its own: '#' and '?' are ordinary characters in a file name
    const parts = file.replace(/\\/g, "/").split("/").map((part, index) => (index === 0 && /^[A-Za-z]:$/.test(part) ? part : encodeURIComponent(part)));
    return "file://" + (parts[0] === "" ? "" : "/") + parts.join("/");
  }

  function sizeText(bytes) {
    return bytes >= 1073741824 ? (bytes / 1073741824).toFixed(1) + " GB" : Math.max(1, Math.round(bytes / 1048576)) + " MB";
  }

  function icon(pathData) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    const shape = document.createElementNS("http://www.w3.org/2000/svg", "path");
    shape.setAttribute("d", pathData);
    svg.appendChild(shape);
    return svg;
  }

  // Thumbnails: one hidden video takes a picture of each file that scrolls into view, one at a time
  const thumbCache = new Map();
  const thumbQueue = [];
  let thumbBusy = false;
  const thumbVideo = document.createElement("video");
  thumbVideo.muted = true;
  thumbVideo.preload = "metadata";

  function takeThumb(file) {
    return new Promise((resolve) => {
      let done = false;
      const finish = (result) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        thumbVideo.onloadeddata = thumbVideo.onseeked = thumbVideo.onerror = null;
        thumbVideo.removeAttribute("src");
        thumbVideo.load();
        resolve(result);
      };
      const timer = setTimeout(() => finish(null), 6000);
      thumbVideo.onerror = () => finish(null);
      thumbVideo.onloadeddata = () => {
        // Not the first frame: it's black more often than not
        thumbVideo.currentTime = Math.min(20, (thumbVideo.duration || 10) * 0.1);
      };
      thumbVideo.onseeked = () => {
        try {
          const canvas = document.createElement("canvas");
          canvas.width = 384;
          canvas.height = 216;
          const context = canvas.getContext("2d");
          const scale = Math.max(canvas.width / thumbVideo.videoWidth, canvas.height / thumbVideo.videoHeight);
          const width = thumbVideo.videoWidth * scale;
          const height = thumbVideo.videoHeight * scale;
          context.drawImage(thumbVideo, (canvas.width - width) / 2, (canvas.height - height) / 2, width, height);
          finish(canvas.toDataURL("image/jpeg", 0.72));
        } catch (e) {
          finish(null);
        }
      };
      thumbVideo.src = fileUrl(file);
    });
  }

  async function runThumbs() {
    if (thumbBusy) return;
    thumbBusy = true;
    while (thumbQueue.length) {
      const job = thumbQueue.shift();
      if (!job.element.isConnected) continue;
      let data = thumbCache.get(job.file);
      if (data === undefined) {
        data = await takeThumb(job.file);
        if (thumbCache.size > 400) thumbCache.delete(thumbCache.keys().next().value);
        thumbCache.set(job.file, data);
      }
      if (data && job.element.isConnected) {
        const image = document.createElement("img");
        image.src = data;
        job.element.appendChild(image);
      }
    }
    thumbBusy = false;
  }

  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      observer.unobserve(entry.target);
      thumbQueue.push({ element: entry.target, file: entry.target.dataset.file });
    }
    runThumbs();
  }, { root: $("library-grid"), rootMargin: "200px" });

  function renderLibrary() {
    const grid = $("library-grid");
    observer.disconnect();
    thumbQueue.length = 0;
    grid.textContent = "";
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    const shown = files.filter((file) => {
      if (kind === "video" && file.audio) return false;
      if (kind === "audio" && !file.audio) return false;
      const name = file.name.toLowerCase();
      return words.every((word) => name.includes(word));
    });
    // A long list is drawn in pieces: the window stays responsive
    const fragment = document.createDocumentFragment();
    for (const file of shown.slice(0, 1500)) {
      const card = document.createElement("button");
      card.className = "card";
      card.title = file.path;
      const thumb = document.createElement("div");
      thumb.className = "thumb";
      thumb.appendChild(icon(file.audio ? ICON_MUSIC : ICON_VIDEO));
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.textContent = file.ext;
      thumb.appendChild(badge);
      if (!file.audio) {
        thumb.dataset.file = file.path;
        observer.observe(thumb);
      }
      const text = document.createElement("div");
      text.className = "text";
      const name = document.createElement("div");
      name.className = "name";
      name.textContent = file.name;
      const info = document.createElement("div");
      info.className = "info";
      info.textContent = file.folder + " • " + sizeText(file.size);
      text.append(name, info);
      card.append(thumb, text);
      card.addEventListener("click", () => openPlayer(file));
      fragment.appendChild(card);
    }
    grid.appendChild(fragment);
    return shown.length;
  }

  function setStatus(shown) {
    if (libraryBusy) {
      $("library-status").textContent = "Looking for video and music files…";
    } else if (!files.length) {
      $("library-status").textContent = "No video or music files were found. Add a folder, or switch on \"all drives\" in the settings (F2).";
    } else {
      $("library-status").textContent = shown.toLocaleString() + (shown === files.length ? "" : " of " + files.length.toLocaleString()) +
        " files" + (libraryPartial ? " (the search was stopped at its limit: add the folder you miss)" : "");
    }
  }

  let libraryPartial = false;

  async function loadLibrary() {
    if (libraryBusy) return;
    libraryBusy = true;
    libraryLoaded = true;
    setStatus(0);
    try {
      const result = await kd.library();
      files = result ? result.files : [];
      libraryPartial = !!(result && result.partial);
    } catch (e) {
      files = [];
    }
    libraryBusy = false;
    setStatus(renderLibrary());
  }

  $("library-kind").addEventListener("click", (event) => {
    const button = event.target.closest("button");
    if (!button) return;
    kind = button.dataset.kind;
    $("library-kind").querySelectorAll("button").forEach((item) => item.classList.toggle("on", item === button));
    setStatus(renderLibrary());
  });
  $("library-search").addEventListener("input", (event) => {
    query = event.target.value;
    setStatus(renderLibrary());
  });
  $("library-rescan").addEventListener("click", loadLibrary);
  $("library-add").addEventListener("click", async () => {
    if (await kd.addFolder()) loadLibrary();
  });

  // ---------- the player of device media ----------

  const video = $("player-video");
  let playing = null;

  function applyToPlayer() {
    if (playing) window.KDEnhance.apply(video, settings);
  }

  function openPlayer(file) {
    playing = file;
    body.classList.add("playing");
    body.classList.remove("player-failed");
    $("player-title").textContent = file.name;
    video.src = fileUrl(file.path);
    video.play().catch(() => {});
    applyToPlayer();
    video.focus();
  }

  function closePlayer() {
    if (!playing) return;
    playing = null;
    body.classList.remove("playing", "player-failed");
    video.pause();
    video.removeAttribute("src");
    video.load();
  }

  video.addEventListener("error", () => {
    if (!playing) return;
    body.classList.add("player-failed");
    $("player-error-text").textContent = "The built-in player doesn't know the format of \"" + playing.name + "." + playing.ext +
      "\" (some MKV, AVI and WMV files, and Dolby / DTS sound). A player like VLC or mpv does.";
  });
  // The picture arrived but there is no sound: a sound format the engine has no decoder for (Dolby, DTS)
  video.addEventListener("loadeddata", () => {
    applyToPlayer();
  });
  video.addEventListener("dblclick", () => kd.command("fullscreen"));
  $("player-close").addEventListener("click", closePlayer);
  $("player-external").addEventListener("click", () => playing && kd.openExternal(playing.path));
  $("player-folder").addEventListener("click", () => playing && kd.showInFolder(playing.path));

  kd.onBack(() => {
    if (playing) closePlayer();
    else kd.command("mode", "tube");
  });
  window.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    if (playing) closePlayer();
    else if (state.panelOpen) kd.command("panel", false);
    else if (state.mode === "library") kd.command("mode", "tube");
  });

  // ---------- casting ----------

  let castBusy = false;

  function castStatus(text) {
    $("cast-status").textContent = text || "";
  }

  async function castTo(device) {
    if (castBusy) return;
    castBusy = true;
    castStatus("Sending…");
    const result = await kd.castSend(device);
    castBusy = false;
    castStatus(result && result.name ? "Playing on " + result.name : (result && result.error) || "It didn't work");
    if (result && result.name && device.code) {
      $("cast-code").value = "";
      findCastDevices();
    }
  }

  async function findCastDevices() {
    const list = $("cast-devices");
    castStatus("Looking for devices…");
    const devices = (await kd.castDevices()) || [];
    list.textContent = "";
    for (const device of devices) {
      const button = document.createElement("button");
      button.className = "cast-device";
      const name = document.createElement("span");
      name.textContent = device.name || "TV";
      const kind = document.createElement("small");
      kind.textContent = device.kind !== "network" ? "linked" : device.castHost ? "Google TV / Chromecast" : "in this network";
      button.append(name, kind);
      button.addEventListener("click", () => castTo(device));
      list.appendChild(button);
    }
    castStatus(devices.length ? "" : "Nothing was found in this network. A TV code works everywhere.");
  }

  $("btn-cast").addEventListener("click", () => {
    kd.command("panel", true);
    $("cast-section").scrollIntoView();
    findCastDevices();
  });
  $("cast-search").addEventListener("click", findCastDevices);
  $("cast-link").addEventListener("click", () => {
    const code = $("cast-code").value.trim();
    if (code) castTo({ code });
    else castStatus("Type the code the TV shows");
  });
  $("cast-code").addEventListener("keydown", (event) => {
    if (event.key === "Enter") $("cast-link").click();
    event.stopPropagation();
  });

  renderSettings();
  kd.command("state");
})();
