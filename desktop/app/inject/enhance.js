// Picture & sound for a <video>: sharpening, volume levelling, dialogue boost, wide stereo, bass boost.
// The same code runs inside of the YouTube page and in the player of "Device media".
// The numbers are the ones of the Android app (SoundEnhanceProcessor, SharpenSurfaceWrapper).
//
//   KDEnhance.apply(video, { sharpen: "off|light|strong", levelling: "off|on|night", dialogue: bool, wide: bool,
//                            bass: "off|light|strong" })
(function (root) {
  "use strict";

  var SVG_NS = "http://www.w3.org/2000/svg";
  var FILTER_HOST_ID = "kd-sharpen-filters";
  // How much of each neighbouring pixel is taken away; the centre is raised to keep the brightness
  var STRENGTH = { light: 0.22, strong: 0.5 };
  var LEVELLING = {
    on: { threshold: -24, knee: 20, ratio: 4, attack: 0.01, release: 0.25 },
    night: { threshold: -42, knee: 12, ratio: 12, attack: 0.003, release: 0.3 }
  };
  var SPEECH_HZ = 2600;
  var SPEECH_LIFT_DB = 6;
  var RUMBLE_HZ = 160;
  var RUMBLE_CUT_DB = -3;
  var STEREO_WIDTH = 1.7;
  // Bass boost: a shelf under the kick drum and the bass line, nothing below what speakers play, a third of the lift
  // taken back from the whole and a limiter for the loudest moments (SoundEnhanceProcessor of the Android app)
  var BASS_HZ = 90;
  var BASS_DB = { light: 4, strong: 7 };
  var SUBSONIC_HZ = 25;

  function ensureFilters() {
    if (document.getElementById(FILTER_HOST_ID) || !document.body) return;
    // NOTE: built node by node: the YouTube page doesn't allow markup from strings
    var svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("id", FILTER_HOST_ID);
    svg.setAttribute("width", "0");
    svg.setAttribute("height", "0");
    svg.setAttribute("aria-hidden", "true");
    svg.style.cssText = "position:absolute;width:0;height:0;overflow:hidden;pointer-events:none";
    var defs = document.createElementNS(SVG_NS, "defs");
    Object.keys(STRENGTH).forEach(function (level) {
      var s = STRENGTH[level];
      var filter = document.createElementNS(SVG_NS, "filter");
      filter.setAttribute("id", "kd-sharpen-" + level);
      filter.setAttribute("color-interpolation-filters", "sRGB");
      var matrix = document.createElementNS(SVG_NS, "feConvolveMatrix");
      matrix.setAttribute("order", "3");
      matrix.setAttribute("preserveAlpha", "true");
      // The entries sum to 1
      matrix.setAttribute("kernelMatrix", [0, -s, 0, -s, 1 + 4 * s, -s, 0, -s, 0].join(" "));
      filter.appendChild(matrix);
      defs.appendChild(filter);
    });
    svg.appendChild(defs);
    document.body.appendChild(svg);
  }

  function applySharpen(video, level) {
    if (!STRENGTH[level]) {
      // Only what this code has set
      if (video.dataset.kdSharpen) {
        video.style.filter = "";
        delete video.dataset.kdSharpen;
      }
      return;
    }
    ensureFilters();
    video.style.filter = "url(#kd-sharpen-" + level + ")";
    video.dataset.kdSharpen = level;
  }

  var chains = new WeakMap();

  function createChain(video) {
    var Ctx = root.AudioContext || root.webkitAudioContext;
    var ctx = new Ctx({ latencyHint: "playback" });
    var chain = { ctx: ctx, source: ctx.createMediaElementSource(video) };
    chain.compressor = ctx.createDynamicsCompressor();
    chain.speech = ctx.createBiquadFilter();
    chain.speech.type = "peaking";
    chain.speech.frequency.value = SPEECH_HZ;
    chain.speech.Q.value = 0.8;
    chain.rumble = ctx.createBiquadFilter();
    chain.rumble.type = "lowshelf";
    chain.rumble.frequency.value = RUMBLE_HZ;
    // Wide stereo: more of the difference between the left and the right
    chain.splitter = ctx.createChannelSplitter(2);
    chain.merger = ctx.createChannelMerger(2);
    var same = (1 + STEREO_WIDTH) / 2;
    var other = (1 - STEREO_WIDTH) / 2;
    chain.wideGains = [same, other, other, same].map(function (value) {
      var gain = ctx.createGain();
      gain.gain.value = value;
      return gain;
    });
    chain.bass = ctx.createBiquadFilter();
    chain.bass.type = "lowshelf";
    chain.bass.frequency.value = BASS_HZ;
    chain.subsonic = ctx.createBiquadFilter();
    chain.subsonic.type = "highpass";
    chain.subsonic.frequency.value = SUBSONIC_HZ;
    chain.subsonic.Q.value = 0.7071;
    chain.limiter = ctx.createDynamicsCompressor();
    chain.limiter.threshold.value = -1.5;
    chain.limiter.knee.value = 3;
    chain.limiter.ratio.value = 20;
    chain.limiter.attack.value = 0.001;
    chain.limiter.release.value = 0.12;
    chain.trim = ctx.createGain();
    // Surround sound goes out with all its channels when nothing has to mix it down
    try {
      ctx.destination.channelCount = Math.min(ctx.destination.maxChannelCount || 2, 8);
    } catch (e) {
      // stereo then
    }
    // The page is allowed to start sound, but a context that was made before the first key press waits
    video.addEventListener("play", function () {
      if (ctx.state === "suspended") ctx.resume();
    });
    return chain;
  }

  function disconnect(node) {
    try {
      node.disconnect();
    } catch (e) {
      // wasn't connected
    }
  }

  function applySound(video, settings) {
    var compress = LEVELLING[settings.levelling];
    var bassDb = BASS_DB[settings.bass] || 0;
    var active = !!compress || !!settings.dialogue || !!settings.wide || bassDb > 0;
    var chain = chains.get(video);
    if (!chain) {
      // NOTE: once made, the sound of the element goes through the chain for good. So not before it's needed.
      if (!active) return;
      try {
        chain = createChain(video);
      } catch (e) {
        return;
      }
      chains.set(video, chain);
    }

    [chain.source, chain.compressor, chain.speech, chain.rumble, chain.splitter, chain.merger, chain.trim,
      chain.bass, chain.subsonic, chain.limiter]
      .concat(chain.wideGains).forEach(disconnect);

    var ctx = chain.ctx;
    if (!active) {
      chain.source.connect(ctx.destination);
      return;
    }

    var tail = chain.source;
    if (compress) {
      chain.compressor.threshold.value = compress.threshold;
      chain.compressor.knee.value = compress.knee;
      chain.compressor.ratio.value = compress.ratio;
      chain.compressor.attack.value = compress.attack;
      chain.compressor.release.value = compress.release;
      tail.connect(chain.compressor);
      tail = chain.compressor;
    }
    if (settings.dialogue) {
      chain.speech.gain.value = SPEECH_LIFT_DB;
      chain.rumble.gain.value = RUMBLE_CUT_DB;
      tail.connect(chain.speech);
      chain.speech.connect(chain.rumble);
      tail = chain.rumble;
    }
    if (settings.wide) {
      tail.connect(chain.splitter);
      chain.splitter.connect(chain.wideGains[0], 0);
      chain.splitter.connect(chain.wideGains[1], 1);
      chain.splitter.connect(chain.wideGains[2], 0);
      chain.splitter.connect(chain.wideGains[3], 1);
      chain.wideGains[0].connect(chain.merger, 0, 0);
      chain.wideGains[1].connect(chain.merger, 0, 0);
      chain.wideGains[2].connect(chain.merger, 0, 1);
      chain.wideGains[3].connect(chain.merger, 0, 1);
      tail = chain.merger;
    }
    if (bassDb > 0) {
      chain.bass.gain.value = bassDb;
      tail.connect(chain.bass);
      chain.bass.connect(chain.subsonic);
      tail = chain.subsonic;
    }
    // The compressor tames the peaks it meets. Without it, leave room for what was lifted.
    chain.trim.gain.value = (settings.dialogue && !compress ? 0.8 : 1) * (settings.wide && !compress ? 0.85 : 1) *
      (bassDb > 0 ? Math.pow(10, -bassDb / 60) : 1);
    tail.connect(chain.trim);
    if (bassDb > 0 && !compress) {
      // The bass boost alone: a limiter keeps the loudest moments clean
      chain.trim.connect(chain.limiter);
      chain.limiter.connect(ctx.destination);
    } else {
      chain.trim.connect(ctx.destination);
    }
    if (ctx.state === "suspended" && !video.paused) ctx.resume();
  }

  root.KDEnhance = {
    apply: function (video, settings) {
      if (!video || !settings) return;
      try {
        applySharpen(video, settings.sharpen);
      } catch (e) {
        // the picture stays as it is
      }
      try {
        applySound(video, settings);
      } catch (e) {
        // the sound stays as it is
      }
    }
  };
})(typeof window !== "undefined" ? window : this);
