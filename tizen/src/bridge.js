// KDTube for Samsung TVs (Tizen). A TizenBrew module: TizenBrew opens YouTube's TV page and runs this file
// in it before the page's own scripts. This part gives the page scripts of KDTube (desktop/app/inject/tube.js,
// the same file as in the PC edition and the Android app) the "window.kdtube" they talk to.
//
// The settings live in the storage of the page. The red button of the remote opens them.
// NOTE: ES5 only: the web engine of a 2018 TV (Tizen 4) is an old one.
(function () {
  "use strict";
  if (window.kdtube) return;

  var STORAGE_KEY = "kdtube-settings";
  var SPONSOR_API = "https://sponsor.ajay.app/api/skipSegments";
  var LABELS = { sponsor: "sponsor", selfpromo: "self promotion", interaction: "like / subscribe reminder", intro: "intro",
    outro: "end credits", preview: "preview", music_offtopic: "non-music part", filler: "filler" };
  var KEY_RED = 403;
  var KEY_BACK = 10009;
  var DEFAULTS = {
    adblock: true,
    sponsorblock: true,
    sponsorCategories: ["sponsor", "selfpromo", "interaction"],
    bestQuality: true,
    // auto | 1080 | 2160 | 4320: the panel, for the resolutions the page is offered
    screen: "auto",
  };

  function load() {
    var stored = {};
    try {
      stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) || "{}") || {};
    } catch (e) {
      // no storage, or a broken entry: the defaults
    }
    var result = {};
    for (var key in DEFAULTS) result[key] = stored[key] !== undefined ? stored[key] : DEFAULTS[key];
    return result;
  }

  function save(values) {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(values));
    } catch (e) {
      // the choice holds until the TV is switched off
    }
  }

  /** The height of the panel in pixels: what the TV says, or the setting */
  function panelHeight(values) {
    if (values.screen !== "auto") return Number(values.screen) || 1080;
    try {
      var info = window.webapis && window.webapis.productinfo;
      if (info) {
        if (typeof info.is8KPanelSupported === "function" && info.is8KPanelSupported()) return 4320;
        if (typeof info.isUdPanelSupported === "function" && info.isUdPanelSupported()) return 2160;
        return 1080;
      }
    } catch (e) {
      // not allowed to ask here
    }
    // The page may be told the real size already
    return Math.max(window.screen.height || 0, 1080);
  }

  /** What the page scripts are given */
  function pageSettings(values) {
    // The page is laid out 1920 x 1080 also on a 4K or 8K panel: the scripts make it see the panel
    var scale = panelHeight(values) / Math.max(window.screen.height || 1080, 1);
    return {
      adblock: !!values.adblock,
      sponsorblock: !!values.sponsorblock,
      sponsorCategories: values.sponsorCategories,
      bestQuality: !!values.bestQuality,
      displayScale: scale,
      videoScale: scale,
      // The TV has the modules of protected video (Samsung licenses them): YouTube may send those
      protection: true,
      // No sound or picture processing here: the TV does that in its own chip
      sharpen: "off",
      levelling: "off",
      dialogue: false,
      wide: false,
      touchScroll: false,
    };
  }

  var values = load();
  var settings = pageSettings(values);
  var listeners = [];

  function changed() {
    save(values);
    settings = pageSettings(values);
    for (var i = 0; i < listeners.length; i++) {
      try {
        listeners[i](settings);
      } catch (e) {
        // a listener of the page: its problem
      }
    }
  }

  function segments(videoId) {
    return new Promise(function (resolve) {
      var request = new XMLHttpRequest();
      request.open("GET", SPONSOR_API + "?videoID=" + encodeURIComponent(videoId) + "&categories=" +
        encodeURIComponent(JSON.stringify(values.sponsorCategories)));
      request.timeout = 8000;
      request.onload = function () {
        // 404: nothing is known about the video
        if (request.status !== 200) return resolve([]);
        try {
          var list = JSON.parse(request.responseText);
          resolve(list.filter(function (item) {
            return item.segment && item.segment.length === 2 && item.actionType !== "mute" && item.actionType !== "full";
          }).map(function (item) {
            return { start: item.segment[0], end: item.segment[1], label: LABELS[item.category] || item.category };
          }));
        } catch (e) {
          resolve([]);
        }
      };
      request.onerror = request.ontimeout = function () {
        resolve([]);
      };
      request.send();
    });
  }

  window.kdtube = {
    settings: function () {
      return settings;
    },
    onSettings: function (listener) {
      if (typeof listener === "function") listeners.push(listener);
    },
    segments: segments,
    playing: function () {
      // The TV keeps the screen on while a video plays by itself
    },
    back: function () {
      // not used on the TV: Back is the key of the remote
    },
    log: function (line) {
      if (window.console) window.console.log("[KDTube] " + line);
    },
  };

  // ---------- the settings: the red button ----------

  var OPTIONS = [
    { key: "adblock", label: "No adverts", note: "From the next video on" },
    { key: "sponsorblock", label: "Skip sponsored parts", note: "Marked by viewers (SponsorBlock)" },
    { key: "bestQuality", label: "Best quality", note: "The highest resolution the screen shows" },
    { key: "screen", label: "Screen", choices: ["auto", "1080", "2160", "4320"], names: { auto: "Auto", "1080": "1080p", "2160": "4K", "4320": "8K" },
      note: "Auto asks the TV. Change it if 4K isn't offered" },
  ];
  var panel = null;
  var selected = 0;

  function valueText(option) {
    var value = values[option.key];
    if (option.choices) return option.names[value] || value;
    return value ? "On" : "Off";
  }

  function element(style, text, children) {
    var node = document.createElement("div");
    node.style.cssText = style;
    if (text) node.textContent = text;
    for (var i = 0; children && i < children.length; i++) node.appendChild(children[i]);
    return node;
  }

  // NOTE: built from elements and text: the page forbids innerHTML (Trusted Types)
  function draw() {
    if (!panel) return;
    while (panel.firstChild) panel.removeChild(panel.firstChild);
    panel.appendChild(element("font-size:34px;font-weight:600;margin-bottom:18px", "KDTube"));
    for (var i = 0; i < OPTIONS.length; i++) {
      var option = OPTIONS[i];
      var on = i === selected;
      panel.appendChild(element("display:flex;justify-content:space-between;align-items:center;padding:14px 20px;margin:6px 0;border-radius:12px;" +
        (on ? "background:#F2F5F9;color:#0F1419" : "background:rgba(255,255,255,.06)"), null, [
        element("", null, [element("font-size:26px", option.label), element("font-size:18px;opacity:.7", option.note)]),
        element("font-size:26px;font-weight:600;margin-left:20px", valueText(option)),
      ]));
    }
    panel.appendChild(element("font-size:18px;opacity:.6;margin-top:16px", "OK or left / right: change  ·  Back or red: close"));
  }

  function open() {
    if (panel || !document.body) return;
    panel = document.createElement("div");
    panel.style.cssText = "position:fixed;top:8%;right:4%;width:640px;z-index:2147483647;padding:28px;border-radius:20px;" +
      "background:rgba(15,20,25,.96);color:#F2F5F9;font-family:'Samsung One','Segoe UI',Roboto,sans-serif;box-shadow:0 10px 40px rgba(0,0,0,.6)";
    document.body.appendChild(panel);
    draw();
  }

  function close() {
    if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
    panel = null;
  }

  function change(step) {
    var option = OPTIONS[selected];
    if (option.choices) {
      var index = option.choices.indexOf(values[option.key]);
      values[option.key] = option.choices[(index + step + option.choices.length) % option.choices.length];
    } else {
      values[option.key] = !values[option.key];
    }
    changed();
    draw();
  }

  // Before the page sees the keys: while the panel is open they are its own
  window.addEventListener("keydown", function (event) {
    var code = event.keyCode;
    if (!panel && code !== KEY_RED) return;
    // First: whatever happens below, the page doesn't get the key
    event.preventDefault();
    event.stopImmediatePropagation();
    if (!panel) {
      open();
      return;
    }
    if (code === KEY_RED || code === KEY_BACK || code === 27) close();
    else if (code === 38) selected = (selected + OPTIONS.length - 1) % OPTIONS.length;
    else if (code === 40) selected = (selected + 1) % OPTIONS.length;
    else if (code === 37) change(-1);
    else if (code === 39 || code === 13) change(1);
    draw();
  }, true);

  // Once per start: where the settings are
  function hint() {
    if (!document.body) return setTimeout(hint, 1000);
    var toast = document.createElement("div");
    toast.textContent = "KDTube: red button for its settings";
    toast.style.cssText = "position:fixed;left:50%;bottom:8%;transform:translateX(-50%);z-index:2147483647;padding:12px 28px;" +
      "border-radius:28px;background:rgba(15,20,25,.9);color:#F2F5F9;font:500 24px sans-serif;transition:opacity .4s";
    document.body.appendChild(toast);
    setTimeout(function () {
      toast.style.opacity = "0";
      setTimeout(function () {
        if (toast.parentNode) toast.parentNode.removeChild(toast);
      }, 600);
    }, 6000);
  }
  setTimeout(hint, 4000);
})();
