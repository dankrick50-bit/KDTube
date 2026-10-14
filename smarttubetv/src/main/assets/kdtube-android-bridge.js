// Gives the page scripts of KDTube (tube.js, enhance.js: the same files as in the PC edition) the
// "window.kdtube" they talk to, on top of what the Android app offers (KDTubeAndroid, see TvInterfaceActivity).
(function () {
  "use strict";
  if (window.kdtube || !window.KDTubeAndroid) return;

  var host = window.KDTubeAndroid;
  var settings = {};
  try {
    settings = JSON.parse(host.settings());
  } catch (e) {
    // the defaults of the scripts
  }
  var listeners = [];
  var waiting = {};
  var nextToken = 1;

  // Called by the app
  window.__kdtubeSettings = function (next) {
    settings = next || {};
    listeners.forEach(function (listener) {
      try {
        listener(settings);
      } catch (e) {
        // a listener of the page: its problem
      }
    });
  };
  window.__kdtubeSegments = function (token, list) {
    var resolve = waiting[token];
    delete waiting[token];
    if (resolve) resolve(list || []);
  };

  window.kdtube = {
    settings: function () {
      return settings;
    },
    onSettings: function (listener) {
      if (typeof listener === "function") listeners.push(listener);
    },
    segments: function (videoId) {
      return new Promise(function (resolve) {
        var token = nextToken++;
        waiting[token] = resolve;
        host.segments(String(videoId), token);
      });
    },
    playing: function (isPlaying) {
      host.playing(!!isPlaying);
    },
    back: function () {
      host.back();
    },
    log: function (line) {
      host.log(String(line).slice(0, 500));
    }
  };
})();
