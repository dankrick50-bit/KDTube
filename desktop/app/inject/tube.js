// Runs inside of the YouTube TV page, before its own scripts.
//   - takes the adverts out of the answers of the server (nothing is requested, nothing is shown)
//   - skips the sponsored parts of a video (SponsorBlock)
//   - asks the player for the best quality the screen can show
//   - applies "Picture & sound" (see enhance.js) to the video
// The settings come from the app through window.kdtube (see tube-preload.js).
(function () {
  "use strict";
  if (window.__kdtubeStarted) return;
  window.__kdtubeStarted = true;

  var bridge = window.kdtube;
  if (!bridge) return;
  var settings = bridge.settings() || {};

  // What the player asked and what went wrong, for the day a video doesn't play: kdtube-player.log
  var logged = {};
  function diag(line, once) {
    try {
      if (once) {
        if (logged[line] || Object.keys(logged).length > 400) return;
        logged[line] = true;
      }
      bridge.log(line);
    } catch (e) {
      // the log is a nicety
    }
  }

  // ---------- what this "TV" can show ----------

  // The page asks the device in detail what it can play: "VP9 at 3840 x 2160, 60 frames, this bitrate?".
  // A browser only looks at the codec and says yes to everything, also to the nonsense values the page
  // mixes in as a check. A device that fails the check is given 720p / 1080p to be safe.
  // So the questions are answered properly: the codec is left to the engine, the numbers are checked here.
  var MAX_WIDTH = 7680;
  var MAX_HEIGHT = 4320;
  var MAX_FRAME_RATE = 120;
  var MAX_BITRATE = 150000000;
  var MAX_CHANNELS = 8;

  function isHdrDisplay() {
    try {
      return window.matchMedia("(dynamic-range: high)").matches;
    } catch (e) {
      return false;
    }
  }

  /**
   * settings.blockCodecs: the kinds of video the device has no hardware decoder for ("av01", e.g.).
   * The page then takes a kind that the chip decodes: smooth playback and a cool device instead of a
   * processor at its limit. Only set where it's known (Android).
   */
  function isBlockedCodec(codecs) {
    var blocked = settings.blockCodecs;
    if (!codecs || !blocked || !blocked.length) return false;
    for (var i = 0; i < blocked.length; i++) {
      if (codecs.indexOf(blocked[i]) === 0) return true;
    }
    return false;
  }

  function inRange(value, max) {
    if (value === undefined) return true;
    var number = Number(value);
    return number > 0 && number <= max;
  }

  if (window.MediaSource && typeof MediaSource.isTypeSupported === "function") {
    var isTypeSupported = MediaSource.isTypeSupported.bind(MediaSource);
    MediaSource.isTypeSupported = function (type) {
      try {
        var parts = String(type).split(";");
        var kept = [parts[0]];
        var params = {};
        for (var i = 1; i < parts.length; i++) {
          var index = parts[i].indexOf("=");
          if (index < 0) continue;
          var name = parts[i].slice(0, index).trim().toLowerCase();
          var value = parts[i].slice(index + 1).trim().replace(/^"|"$/g, "");
          if (name === "codecs") {
            kept.push(parts[i]);
            params.codecs = value;
          } else {
            params[name] = value;
          }
        }
        var answer;
        if (isBlockedCodec(params.codecs)) {
          // The device would have to decode it on the processor: see blockCodecs
          answer = false;
        } else if (!inRange(params.width, MAX_WIDTH) || !inRange(params.height, MAX_HEIGHT) ||
            !inRange(params.framerate, MAX_FRAME_RATE) || !inRange(params.bitrate, MAX_BITRATE) ||
            !inRange(params.channels, MAX_CHANNELS)) {
          answer = false;
        } else if (params.eotf !== undefined && params.eotf !== "bt709" &&
            !((params.eotf === "smpte2084" || params.eotf === "arib-std-b67") && isHdrDisplay())) {
          answer = false;
        } else if (params.cryptoblockformat !== undefined && (!settings.protection || params.cryptoblockformat !== "subsample")) {
          // Protected video. It needs a module of Google (Widevine). Android devices have it built in
          // (settings.protection). On the PC YouTube gives the keys only to programs that went through
          // a verification this app doesn't have (tried: the module loads, the key is refused).
          // Saying "no" there lets YouTube pick the unprotected copy of a video.
          answer = false;
        } else if (params["decode-to-texture"] !== undefined && params["decode-to-texture"] !== "false") {
          // Pictures as textures (360 degrees): not here
          answer = false;
        } else if (params.experimental !== undefined && params.experimental !== "allowed") {
          answer = false;
        } else {
          answer = isTypeSupported(kept.join(";"));
        }
        diag("can play? " + type + " -> " + answer, true);
        return answer;
      } catch (e) {
        return isTypeSupported(type);
      }
    };

    var addSourceBuffer = MediaSource.prototype.addSourceBuffer;
    MediaSource.prototype.addSourceBuffer = function (type) {
      try {
        var buffer = addSourceBuffer.apply(this, arguments);
        diag("buffer for " + type);
        return buffer;
      } catch (e) {
        diag("buffer for " + type + " FAILED: " + e.message);
        throw e;
      }
    };
  }

  // Protected video (see above): where it can't be played the answer is "no" whichever way the page asks,
  // also on a system that has such a module. Half a "yes" is the worst answer: the page then starts
  // something it can't finish.
  if (navigator.requestMediaKeySystemAccess && !settings.protection) {
    navigator.requestMediaKeySystemAccess = function (keySystem) {
      diag("protection asked for (" + keySystem + "): not available here", true);
      var error;
      try {
        error = new DOMException("Unsupported keySystem or supportedConfigurations.", "NotSupportedError");
      } catch (e) {
        error = new Error("Unsupported keySystem or supportedConfigurations.");
      }
      return Promise.reject(error);
    };
  }

  // The size of the screen in its real pixels (the page is drawn one to one, see applyPixelScale of the app)
  var displayScale = Number(settings.displayScale) || 1;
  if (displayScale !== 1) {
    ["width", "height", "availWidth", "availHeight"].forEach(function (name) {
      try {
        var pixels = Math.round(window.screen[name] * displayScale);
        Object.defineProperty(window.screen, name, { get: function () { return pixels; } });
      } catch (e) {
        // the page sees the smaller number then
      }
    });
  }

  // Android: a web page is laid out in the units of the system there (1280 wide on a tablet with 1920
  // pixels), and nothing but the page itself can change that. So the page is told to be as wide as the
  // screen has pixels; the engine shrinks it back to fit. Same effect as applyPixelScale of the PC edition.
  var viewportWidth = Number(settings.viewportWidth) || 0;

  function applyViewport() {
    if (!viewportWidth || !document.head) return;
    var meta = document.querySelector("meta[name=viewport]");
    if (!meta) {
      meta = document.createElement("meta");
      meta.setAttribute("name", "viewport");
      document.head.appendChild(meta);
    }
    var content = "width=" + viewportWidth + ", user-scalable=no";
    if (meta.getAttribute("content") !== content) meta.setAttribute("content", content);
  }

  if (viewportWidth) {
    applyViewport();
    document.addEventListener("DOMContentLoaded", applyViewport);
  }

  // ---------- adverts ----------

  function withoutAds(items) {
    return items.filter(function (item) {
      return !(item && (item.adSlotRenderer || item.tvMastheadRenderer));
    });
  }

  function stripAds(data) {
    // The answer of the player: the plan of the advert breaks of a video
    if (data.adPlacements) data.adPlacements = [];
    if (data.playerAds) data.playerAds = [];
    if (data.adSlots) data.adSlots = [];
    if (data.adBreakHeartbeatParams) delete data.adBreakHeartbeatParams;

    // The home screen: the banner on top and the "sponsored" cards in the rows
    var browse = data.contents && data.contents.tvBrowseRenderer && data.contents.tvBrowseRenderer.content;
    var surface = browse && browse.tvSurfaceContentRenderer && browse.tvSurfaceContentRenderer.content;
    var list = surface && surface.sectionListRenderer;
    if (list && Array.isArray(list.contents)) {
      list.contents = withoutAds(list.contents);
      list.contents.forEach(stripShelf);
    }
    // More rows loaded while scrolling
    var continuation = data.continuationContents && data.continuationContents.sectionListContinuation;
    if (continuation && Array.isArray(continuation.contents)) {
      continuation.contents = withoutAds(continuation.contents);
      continuation.contents.forEach(stripShelf);
    }
  }

  function stripShelf(shelf) {
    var row = shelf && shelf.shelfRenderer && shelf.shelfRenderer.content && shelf.shelfRenderer.content.horizontalListRenderer;
    if (row && Array.isArray(row.items)) row.items = withoutAds(row.items);
  }

  /** What YouTube offers for a video: how many forms, and how many of them only in protected form */
  var protectedOnly = {};

  function isProtected(format) {
    return !!(format.drmFamilies || format.drmTrackType);
  }

  function notePlayerAnswer(answer) {
    var data = answer.streamingData;
    var formats = (data.adaptiveFormats || []).concat(data.formats || []);
    var locked = formats.filter(isProtected).length;
    var id = answer.videoDetails && answer.videoDetails.videoId;
    diag("offer for " + id + ": " + formats.length + " forms, " + locked + " protected" +
      (data.licenseInfos ? ", needs a licence" : "") + (data.serverAbrStreamingUrl ? ", streamed by the server" : "") +
      ", status " + (answer.playabilityStatus && answer.playabilityStatus.status));

    if (settings.protection) {
      return;
    }

    if (locked > 0 && locked < formats.length) {
      // Both kinds are on offer: leave only what can be played here, so the page can't pick the other kind
      ["adaptiveFormats", "formats"].forEach(function (list) {
        if (Array.isArray(data[list])) {
          data[list] = data[list].filter(function (format) {
            return !isProtected(format);
          });
        }
      });
      diag("offer for " + id + ": the protected forms were taken out");
    } else if (id && locked > 0) {
      protectedOnly[id] = true;
    }
  }

  var parse = JSON.parse;
  JSON.parse = function () {
    var result = parse.apply(this, arguments);
    if (result && typeof result === "object" && result.streamingData) {
      try {
        notePlayerAnswer(result);
      } catch (e) {
        // only for the log
      }
    }
    if (settings.adblock && result && typeof result === "object") {
      try {
        stripAds(result);
      } catch (e) {
        // an answer of another shape: untouched
      }
    }
    return result;
  };

  // ---------- the video ----------

  var video = null;
  var videoId = null;
  var segments = [];
  var toast = null;
  var toastTimer = 0;

  function currentVideoId() {
    var match = /[?&]v=([\w-]{6,})/.exec(location.hash || "") || /[?&]v=([\w-]{6,})/.exec(location.search || "");
    return match ? match[1] : null;
  }

  function showToast(text, milliseconds) {
    if (!document.body) return;
    if (!toast) {
      toast = document.createElement("div");
      toast.style.cssText = "position:fixed;left:50%;bottom:9%;transform:translateX(-50%);z-index:2147483647;" +
        "padding:.55em 1.2em;border-radius:1.4em;max-width:80%;text-align:center;background:rgba(15,20,25,.88);color:#F2F5F9;" +
        "font:500 1.5rem 'Segoe UI',Roboto,sans-serif;pointer-events:none;transition:opacity .25s;opacity:0";
      document.body.appendChild(toast);
    }
    toast.textContent = text;
    toast.style.opacity = "1";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      toast.style.opacity = "0";
    }, milliseconds || 2200);
  }

  function loadSegments() {
    var id = currentVideoId();
    if (id === videoId) return;
    videoId = id;
    segments = [];
    if (!id || !settings.sponsorblock) return;
    bridge.segments(id).then(function (list) {
      if (id === videoId && Array.isArray(list)) segments = list;
    }).catch(function () {
      // no list: nothing is skipped
    });
  }

  function onTimeUpdate() {
    if (!settings.sponsorblock || !segments.length || !video || video.seeking) return;
    var time = video.currentTime;
    for (var i = 0; i < segments.length; i++) {
      var segment = segments[i];
      // NOTE: not when the end is right ahead: skipping would only stutter
      if (time >= segment.start && time < segment.end - 0.5) {
        video.currentTime = Math.min(segment.end, (video.duration || segment.end) - 0.2);
        showToast("Skipped: " + segment.label);
        return;
      }
    }
  }

  var LEVEL_HEIGHTS = { highres: 4320, hd2880: 2880, hd2160: 2160, hd1440: 1440, hd1080: 1080, hd720: 720, large: 480, medium: 360, small: 240, tiny: 144 };

  /**
   * The best picture the window can show: the lowest resolution that fills its pixels, or the highest
   * the video has. Not more: decoding 4K into a small window costs memory and frames and shows nothing.
   * Left alone, the player starts low and climbs with the speed of the connection.
   */
  function bestQuality() {
    if (!settings.bestQuality) return;
    try {
      var player = document.querySelector(".html5-video-player");
      if (!player || typeof player.getAvailableQualityLevels !== "function" || typeof player.setPlaybackQualityRange !== "function") return;
      // The best one is the first; "auto" is always in the list
      var levels = (player.getAvailableQualityLevels() || []).filter(function (level) {
        return LEVEL_HEIGHTS[level];
      });
      if (!levels.length) return;
      // NOTE: the page is drawn one to one, its size is in the pixels of the display.
      // A wide picture on a taller screen (a tablet) is as high as the width allows, not as the screen.
      var needed = Math.min(window.innerHeight, window.innerWidth * 9 / 16) * 0.92;
      var choice = levels[0];
      for (var i = 0; i < levels.length; i++) {
        if (LEVEL_HEIGHTS[levels[i]] >= needed) choice = levels[i];
      }
      player.setPlaybackQualityRange(choice, choice);
    } catch (e) {
      // the player decides by itself then
    }
  }

  var resizeTimer = 0;
  window.addEventListener("resize", function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(bestQuality, 800);
  });

  function applyEnhance() {
    if (video && window.KDEnhance) window.KDEnhance.apply(video, settings);
  }

  var reportedFailure = null;

  /** The page says a video can't be played: note what was going on */
  function watchForFailure() {
    var id = currentVideoId();
    if (!id || reportedFailure === id || !document.body) return;
    var text = document.body.innerText || "";
    var at = text.indexOf("Something went wrong");
    if (at < 0) return;
    reportedFailure = id;
    // The headline and the sentence below it, which says what kind of problem it is
    var message = text.slice(at, at + 160).replace(/\s+/g, " ");
    diag("PAGE ERROR for video " + id + ": " + message + " | hdr display: " + isHdrDisplay() + " | window: " +
      window.innerWidth + "x" + window.innerHeight);
    // The message of the page ("format is not supported") doesn't say what the matter is
    if (protectedOnly[id]) {
      showToast("YouTube offers this video only in protected form (films, shows, some music). KDTube can't play those here.", 9000);
    }
  }

  function attach(element) {
    if (video === element) return;
    video = element;
    video.addEventListener("error", function () {
      var error = video.error || {};
      diag("VIDEO ERROR " + error.code + ": " + error.message);
    });
    video.addEventListener("loadeddata", function () {
      diag("playing " + currentVideoId() + " at " + video.videoWidth + "x" + video.videoHeight);
    });
    video.addEventListener("encrypted", function () {
      diag("video " + currentVideoId() + " is protected", true);
    });
    video.addEventListener("waitingforkey", function () {
      diag("video " + currentVideoId() + " waits for its key", true);
    });
    video.addEventListener("timeupdate", onTimeUpdate);
    video.addEventListener("loadeddata", function () {
      loadSegments();
      bestQuality();
      applyEnhance();
      // The list of the resolutions is complete a moment after the first picture
      [2000, 6000].forEach(function (delay) {
        setTimeout(bestQuality, delay);
      });
    });
    ["play", "playing", "pause", "ended", "emptied"].forEach(function (name) {
      video.addEventListener(name, function () {
        bridge.playing(!video.paused && !video.ended && video.readyState > 2);
      });
    });
    applyEnhance();
  }

  function findVideo() {
    var element = document.querySelector("video");
    if (element) attach(element);
  }

  // The page makes its video element late and keeps it; looking for it now and then is cheap
  var ticks = 0;

  /** Now and then while a video plays: how the device keeps up (for the log of the player) */
  function noteFrames() {
    if (!video || video.paused || !video.getVideoPlaybackQuality) return;
    var quality = video.getVideoPlaybackQuality();
    diag("frames of " + currentVideoId() + " at " + Math.round(video.currentTime) + " s: " + quality.totalVideoFrames + " shown, " +
      quality.droppedVideoFrames + " dropped, " + video.videoWidth + "x" + video.videoHeight + " in a page of " +
      window.innerWidth + "x" + window.innerHeight);
  }

  setInterval(function () {
    findVideo();
    loadSegments();
    watchForFailure();
    applyViewport();
    if (++ticks % 20 === 0) noteFrames();
  }, 1500);
  window.addEventListener("hashchange", loadSegments);

  bridge.onSettings(function (next) {
    var sponsorChanged = JSON.stringify(next.sponsorCategories) !== JSON.stringify(settings.sponsorCategories) ||
      next.sponsorblock !== settings.sponsorblock;
    settings = next || {};
    if (sponsorChanged) videoId = null; // ask for the list again
    loadSegments();
    bestQuality();
    applyEnhance();
  });
})();
