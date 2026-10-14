// Differential test of the lazy AST shim (assets/nsigsolver/lazy.ast.js): the app's challenge
// solvers must produce the same answers with and without it.
//
//   node solver-diff.js compare <yt|tcl> <player.js> <spans.txt>     same answers? (exit code 1 if not)
//   node solver-diff.js run     <yt|tcl> <player.js> [spans.txt]     one preprocessing run (for memory/time measurements,
//                                                                    e.g. with node --max-old-space-size=64)
//
// spans.txt comes from:  cargo run --release --example jsspans -- player.js > spans.txt
// The solvers run in a bare context (no node globals), like inside the app's V8.

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ASSETS = path.join(__dirname, '../../../MediaServiceCore/youtubeapi/src/main/assets/nsigsolver');

function createSolver(kind, lazy) {
  const context = vm.createContext({});
  const scripts = ['polyfill.js', 'meriyah-6.1.4.min.js', 'astring-1.9.0.min.js'];
  if (lazy) {
    scripts.push('lazy.ast.js');
  }
  scripts.push(kind === 'tcl' ? 'tcl.solver.js' : 'yt.solver.core.js');
  for (const name of scripts) {
    vm.runInContext(fs.readFileSync(path.join(ASSETS, name), 'utf8'), context, { filename: name });
  }
  return context;
}

// The same call the app makes (see JsRuntimeChalBaseJCP / TclChallengeProvider)
function solve(context, kind, type, code, spans, challenges) {
  context.__player = code;
  if (spans) {
    context.__playerSpans = spans;
  } else {
    delete context.__playerSpans;
  }
  const key = type === 'preprocessed' ? 'preprocessed_player' : 'player';
  const request = kind === 'tcl'
    ? { type, challenges: challenges.n.map((n) => 'https://www.youtube.com/videoplayback?n=' + n), output_preprocessed: type === 'player' }
    : { type, requests: [{ type: 'n', challenges: challenges.n }, { type: 'sig', challenges: challenges.sig }], output_preprocessed: type === 'player' };
  const fn = kind === 'tcl' ? 'tclSolver' : 'jsc';
  const out = JSON.parse(vm.runInContext(`JSON.stringify(${fn}(Object.assign(${JSON.stringify(request)}, {${key}: __player})));`, context));
  const preprocessed = out.preprocessed_player;
  delete out.preprocessed_player;
  return { answers: out, preprocessed, lazy: context.__lazyAst === true };
}

function randomChallenges(count, seed) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  let state = seed;
  const next = () => (state = (state * 1103515245 + 12345) & 0x7fffffff);
  const str = (len) => Array.from({ length: len }, () => alphabet[next() % alphabet.length]).join('');
  return {
    n: Array.from({ length: count }, () => str(10 + (next() % 14))),
    sig: Array.from({ length: count }, () => str(96 + (next() % 16))),
  };
}

function hasError(answers) {
  return answers.type === 'error' || (answers.responses || []).some((r) => r.type === 'error');
}

const [mode, kind, playerFile, spansFile] = process.argv.slice(2);
const player = fs.readFileSync(playerFile, 'utf8');
const spans = spansFile ? fs.readFileSync(spansFile, 'utf8').trim() : null;
const name = path.basename(playerFile);

if (mode === 'run') {
  const start = Date.now();
  const result = solve(createSolver(kind, !!spans), kind, 'player', player, spans, randomChallenges(1, 1));
  console.log(`RESULT ${name} solver=${kind} lazy=${result.lazy} ms=${Date.now() - start} error=${hasError(result.answers)} ` +
    `preprocessed_kb=${Math.round((result.preprocessed || '').length / 1024)} heap_used_mb=${Math.round(process.memoryUsage().heapUsed / 1048576)}`);
  process.exit(hasError(result.answers) ? 1 : 0);
}

// ---- compare
let failed = false;
const fail = (what) => {
  failed = true;
  console.log(`FAIL ${name} solver=${kind}: ${what}`);
};

const first = randomChallenges(20, 7);
const standard = solve(createSolver(kind, false), kind, 'player', player, null, first);
const lazy = solve(createSolver(kind, true), kind, 'player', player, spans, first);

if (!lazy.lazy) {
  fail('the lazy parser was not used (spans rejected?)');
}
if (hasError(standard.answers)) {
  console.log(`NOTE ${name} solver=${kind}: the regular solver itself fails on this player: ${JSON.stringify(standard.answers).slice(0, 200)}`);
}
if (JSON.stringify(standard.answers) !== JSON.stringify(lazy.answers)) {
  fail(`first run answers differ\n  regular: ${JSON.stringify(standard.answers).slice(0, 300)}\n  lazy:    ${JSON.stringify(lazy.answers).slice(0, 300)}`);
}

// What the app caches and runs on every later playback
let compared = 0;
if (standard.preprocessed && lazy.preprocessed) {
  const more = randomChallenges(300, 99);
  const a = solve(createSolver(kind, false), kind, 'preprocessed', standard.preprocessed, null, more);
  const b = solve(createSolver(kind, false), kind, 'preprocessed', lazy.preprocessed, null, more);
  compared = more.n.length + (kind === 'tcl' ? 0 : more.sig.length);
  if (hasError(a.answers) !== hasError(b.answers) || JSON.stringify(a.answers) !== JSON.stringify(b.answers)) {
    fail(`answers of the cached players differ\n  regular: ${JSON.stringify(a.answers).slice(0, 300)}\n  lazy:    ${JSON.stringify(b.answers).slice(0, 300)}`);
  }
} else if (!hasError(standard.answers)) {
  fail('no preprocessed player');
}

console.log(`${failed ? 'FAIL' : 'PASS'} ${name} solver=${kind}: ${compared} challenges compared on the cached players, ` +
  `cached size regular=${Math.round((standard.preprocessed || '').length / 1024)} KB lazy=${Math.round((lazy.preprocessed || '').length / 1024)} KB`);
process.exit(failed ? 1 : 0);
