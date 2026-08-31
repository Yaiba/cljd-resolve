'use strict';
// The VSCode extension (design.md §4 step 3).
//
// Registers a HoverProvider and a DefinitionProvider for `.cljd` files and
// answers both off the step-2 daemon -- sharing one `resolve` call when they
// fire on the same cursor, which they always do. Nothing here knows about
// Dart, rewrite-clj or the analyzer -- the daemon owns all of it.
//
// No conflict with Calva: VSCode merges hovers from every provider that
// answers, and offers multiple definitions as a peek list, so Calva keeps
// answering for everything Clojure-shaped and we answer for the Dart edge.

const fs = require('fs');
const os = require('os');
const path = require('path');
const vscode = require('vscode');

const { Daemon, protocolComplaint } = require('./client');
const { hoverMarkdown, summary } = require('./hover');
const { fallbackPaths, launcherName, needsShell } = require('./platform');

const SELECTOR = { language: 'clojure', scheme: 'file', pattern: '**/*.cljd' };

let daemon = null;
let out = null;
let status = null;
let warmed = new Set();

// ------------------------------------------------------------------- config

function cfg() {
  return vscode.workspace.getConfiguration('cljd-resolve');
}

function expandHome(p) {
  return p.startsWith('~') ? path.join(os.homedir(), p.slice(1)) : p;
}

// The platform launcher: the setting, then the repo this extension lives in,
// then whatever is on PATH.
//
// There is deliberately no `<extension>/bin/<launcher>` candidate. Nothing
// ships the daemon inside the extension and nothing can: it is babashka
// reading `bb.edn` and `src/` from the repo root, driving a Dart helper that
// only works after a `dart pub get` in `helper/` on the machine running it.
// So the extension is installed *from* the repo -- README, *Install*.
function daemonPath(context, platform = process.platform) {
  const configured = String(cfg().get('daemonPath') || '').trim();
  if (configured) return expandHome(configured);

  // realpath first: the supported install is a symlink from
  // ~/.vscode/extensions into this repo, and `..` has to mean the repo, not
  // the extensions dir.
  let root = context.extensionPath;
  try { root = fs.realpathSync(root); } catch (e) { /* keep the raw path */ }

  const launcher = launcherName(platform);
  const inRepo = path.join(root, '..', 'bin', launcher);
  if (fs.existsSync(inRepo)) return inRepo;
  return launcher;
}

// GUI launches can inherit a barer PATH than a terminal. User `extraPath`
// wins; common Babashka install locations are a last resort.
function daemonEnv() {
  const extra = (cfg().get('extraPath') || []).map(expandHome);
  const fallback = fallbackPaths();
  const current = (process.env.PATH || '').split(path.delimiter).filter(Boolean);
  const seen = new Set(current);
  const head = extra.filter((d) => d && !seen.has(d));
  head.forEach((d) => seen.add(d));
  const tail = fallback.filter((d) => !seen.has(d) && fs.existsSync(d));
  return Object.assign({}, process.env, {
    PATH: [...head, ...current, ...tail].join(path.delimiter),
  });
}

// ------------------------------------------------------------------ plumbing

function log(msg) {
  if (out) out.appendLine(`[${new Date().toISOString().slice(11, 19)}] ${msg}`);
}

const STATUS = {
  starting: { text: '$(sync~spin) cljd', tip: 'Starting the ClojureDart resolve daemon…' },
  ready: { text: '$(symbol-namespace) cljd', tip: 'ClojureDart resolve daemon is running' },
  stopped: { text: '$(circle-slash) cljd', tip: 'ClojureDart resolve daemon is not running' },
};

// Set while the daemon we are talking to speaks a different wire protocol
// than this extension does; see checkProtocol.
let mismatch = null;
let lastState = 'stopped';

function setState(state) {
  lastState = state;
  if (!status) return;
  // The complaint outranks whatever the daemon is doing: a mismatch is not
  // fixed by the next reply arriving, so it stays up until a matching daemon
  // replaces this one.
  const s = mismatch
    ? { text: '$(warning) cljd', tip: mismatch }
    : (STATUS[state] || STATUS.stopped);
  status.text = s.text;
  status.tooltip = `${s.tip}\nClick to show the log.`;
}

// Every daemon this extension starts is asked, once, whether it speaks the
// protocol this extension was written against -- but only once something
// actually needs it. Activation fires on any Clojure file, and pinging from
// there would spawn a `bb` process for every project that has no `.cljd` in
// it at all.
//
// Skew is unlikely by construction: there is no `.vsix`, so the extension runs
// from the checkout that holds the daemon it spawns (README, *Install*)
// and the two move together. What is left is a `cljd-resolve.daemonPath`
// aimed at a second, older clone, or a symlinked install whose repo has not
// been pulled -- neither of which announces itself.
//
// So: loud, and not fatal. Refusing to serve would turn skew that is usually
// harmless -- the daemon merely newer, its extra keys ignored -- into no
// hovers at all, and would do it for a mismatch this extension cannot fix. A
// modal popup is no better: it is dismissed once and the skew stays. Instead
// the log says exactly what happened and what to do, the status bar carries a
// warning for as long as it is true, and resolving carries on.
async function checkProtocol(d) {
  let pong;
  try {
    pong = await d.request('ping', {});
  } catch (e) {
    // A daemon that will not start is already reported by the client, and it
    // has no protocol to disagree about.
    return;
  }
  if (d !== daemon) return;               // superseded by a rebuild mid-ping
  mismatch = protocolComplaint(pong);
  if (mismatch) log(`PROTOCOL MISMATCH: ${mismatch}`);
  setState(lastState);
}

let checked = false;

// The check, at most once per daemon. Awaited by warm-up, which is slow
// already and where one more round trip buys ordered logging; fired and
// forgotten by a resolve, which must not wait on it.
function ensureChecked() {
  if (checked || !daemon) return Promise.resolve();
  checked = true;
  return checkProtocol(daemon);
}

function showStatus(editor) {
  if (!status) return;
  const isCljd = editor && editor.document.uri.fsPath.endsWith('.cljd');
  if (isCljd) status.show(); else status.hide();
}

function makeDaemon(context) {
  const command = daemonPath(context);
  return new Daemon({
    command,
    // Windows batch files are scripts, not native executables. Node needs the
    // command shell to run one; executable paths on every platform stay direct.
    shell: needsShell(process.platform, command),
    cwd: (vscode.workspace.workspaceFolders || [])[0]?.uri.fsPath,
    env: daemonEnv(),
    timeout: Number(cfg().get('requestTimeout')) || 20000,
    log,
    onState: setState,
  });
}

// The hover and the definition provider fire on the same cursor, so the same
// {file, text, line, character} would otherwise cost two full round trips --
// two copies of the whole buffer up the pipe -- for one answer. In-flight
// resolves are shared on that key instead.
const inFlight = new Map();

// One `resolve` round trip, shared by everyone asking for the same position.
// Failures are logged, never thrown at the editor -- a broken daemon should
// mean no hover, not a popup on every mouse move.
//
// The shared request deliberately carries NO cancellation token and is never
// cancelled. Attaching the first caller's token would mean a cancelled hover
// resolving the shared promise to null under a definition still waiting on
// it, and both would lose the answer. Cancellation was only ever a
// client-side discard -- the daemon does the work regardless -- so each
// consumer races this promise against its own token instead.
function resolveOnce(key, params) {
  const shared = inFlight.get(key);
  if (shared) return shared;
  ensureChecked();
  const trace = Boolean(cfg().get('trace'));
  const p = daemon.request('resolve', params, { trace })
    .then((hit) => {
      if (trace) log(`<-- ${summary(hit)}`);
      return hit;
    })
    .catch((e) => {
      log(`resolve failed: ${e.message}`);
      return null;
    })
    .finally(() => {
      if (inFlight.get(key) === p) inFlight.delete(key);
    });
  inFlight.set(key, p);
  return p;
}

// `promise`, unless `token` cancels first -- in which case null, for this
// caller only. The listener is disposed either way, so a resolved request
// leaves nothing hanging off the editor's token.
function raceCancellation(promise, token) {
  return new Promise((resolve) => {
    let sub = null;
    const done = (v) => {
      if (sub && sub.dispose) sub.dispose();
      resolve(v);
    };
    sub = token.onCancellationRequested(() => done(null));
    promise.then(done, () => done(null));
  });
}

function resolveAt(document, position, token) {
  if (!daemon) return Promise.resolve(null);
  const params = {
    file: document.uri.fsPath,
    text: document.getText(), // the unsaved buffer, which is the whole point
    line: position.line,
    character: position.character,
  };
  const key = JSON.stringify([params.file, params.line, params.character, params.text]);
  const shared = resolveOnce(key, params);
  if (!token) return shared;
  if (token.isCancellationRequested) return Promise.resolve(null);
  return raceCancellation(shared, token);
}

function toRange(r) {
  if (!r) return null;
  return new vscode.Range(
    new vscode.Position(r.start.line, r.start.character),
    new vscode.Position(r.end.line, r.end.character));
}

// -------------------------------------------------------------- the providers

const hoverProvider = {
  async provideHover(document, position, token) {
    const hit = await resolveAt(document, position, token);
    if (!hit || token.isCancellationRequested) return null;
    const body = hoverMarkdown(hit);
    if (!body) return null;
    const md = new vscode.MarkdownString(body);
    md.supportThemeIcons = true;
    return new vscode.Hover(md, toRange(hit.originRange) || undefined);
  },
};

const definitionProvider = {
  async provideDefinition(document, position, token) {
    const hit = await resolveAt(document, position, token);
    if (!hit || !hit.defUri || !hit.defRange || token.isCancellationRequested) return null;
    const target = toRange(hit.defRange);
    // A LocationLink rather than a Location, so the peek highlights the exact
    // symbol in the `.cljd` buffer it came from.
    return [{
      originSelectionRange: toRange(hit.originRange) || undefined,
      targetUri: vscode.Uri.parse(hit.defUri),
      targetRange: target,
      targetSelectionRange: target,
    }];
  },
};

// ------------------------------------------------------------------ warm-up

// Two costs land once per project: the analyzer's first start is a `dart run`
// compile (~3s), and resolving the first library out of the Flutter SDK is
// another ~5.5s. Paying both when a `.cljd` file opens hides them behind the
// file opening rather than behind the user's first hover, which is then ~10ms.
//
// The whole batch is ~8s, though, and the daemon serves one request at a
// time -- so warming it in a single request would put every hover on file
// open behind all of it. Hence one request per chunk, each awaited here: the
// analyzer start, then one library at a time. The daemon reads stdin line by
// line, so a hover sent mid warm-up is simply the next line it reads, and it
// waits for one chunk (~3s, then ~5.5s, then ~10ms) instead of the batch.
const WARM_UP_TIMEOUT = 120000;

async function warmUp(document) {
  if (!daemon) return;
  if (!document || !document.uri.fsPath.endsWith('.cljd')) return;
  await ensureChecked();
  if (!cfg().get('warmUp')) return;
  const folder = vscode.workspace.getWorkspaceFolder(document.uri);
  const key = folder ? folder.uri.fsPath : path.dirname(document.uri.fsPath);
  if (warmed.has(key)) return;
  warmed.add(key);
  const file = document.uri.fsPath;
  const trace = Boolean(cfg().get('trace'));
  try {
    const t = Date.now();
    const res = await daemon.request(
      'warmUp',
      { file, text: document.getText() },
      { timeout: WARM_UP_TIMEOUT, trace });
    if (!res || !res.ok) {
      warmed.delete(key);
      log(`warm-up found no Dart project for ${key}`);
      return;
    }
    const libs = res.libs || [];
    for (const lib of libs) {
      await daemon.request('warmUpLib', { file, lib }, { timeout: WARM_UP_TIMEOUT, trace });
    }
    log(`warm-up ready in ${Date.now() - t}ms, ` +
        `${libs.length} librar${libs.length === 1 ? 'y' : 'ies'} (${key})`);
  } catch (e) {
    warmed.delete(key);
    log(`warm-up failed: ${e.message}`);
  }
}

// Tears the daemon down and stands a fresh one up, picking up any changed
// setting on the way.
async function rebuild(context) {
  warmed = new Set();
  mismatch = null;
  checked = false;
  const old = daemon;
  daemon = makeDaemon(context);
  if (old) await old.dispose();
  log(`daemon: ${daemon.command}`);
  await warmUp(vscode.window.activeTextEditor?.document);
}

// -------------------------------------------------------------- the lifecycle

function activate(context) {
  out = vscode.window.createOutputChannel('ClojureDart Resolve');
  status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  status.command = 'cljd-resolve.showLog';
  setState('stopped');

  daemon = makeDaemon(context);
  checked = false;
  log(`daemon: ${daemon.command}`);

  context.subscriptions.push(
    out,
    status,
    vscode.languages.registerHoverProvider(SELECTOR, hoverProvider),
    vscode.languages.registerDefinitionProvider(SELECTOR, definitionProvider),

    vscode.commands.registerCommand('cljd-resolve.showLog', () => out.show(true)),

    vscode.commands.registerCommand('cljd-resolve.restart', async () => {
      await rebuild(context);
      vscode.window.showInformationMessage('ClojureDart resolve daemon restarted.');
    }),

    vscode.commands.registerCommand('cljd-resolve.clearCache', async () => {
      try {
        await daemon.request('clearCache', {});
        // The libraries went with it, so warm them again rather than leaving
        // the next hover to pay for the whole SDK.
        warmed = new Set();
        vscode.window.showInformationMessage('ClojureDart analyzer cache cleared.');
        await warmUp(vscode.window.activeTextEditor?.document);
      } catch (e) {
        vscode.window.showErrorMessage(`Could not clear the cache: ${e.message}`);
      }
    }),

    vscode.workspace.onDidOpenTextDocument((doc) => warmUp(doc)),
    vscode.workspace.onDidChangeConfiguration((e) => {
      // A new daemonPath or PATH means a new process; the rest is read per call.
      if (e.affectsConfiguration('cljd-resolve.daemonPath') ||
          e.affectsConfiguration('cljd-resolve.extraPath') ||
          e.affectsConfiguration('cljd-resolve.requestTimeout')) {
        rebuild(context);
      }
    }),
    vscode.window.onDidChangeActiveTextEditor((editor) => {
      showStatus(editor);
      if (editor) warmUp(editor.document);
    }),
  );

  showStatus(vscode.window.activeTextEditor);
  warmUp(vscode.window.activeTextEditor?.document);
}

function deactivate() {
  return daemon ? daemon.dispose() : undefined;
}

module.exports = { activate, deactivate, daemonPath };
