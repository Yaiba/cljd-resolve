'use strict';
// The VSCode extension (design.md §4 step 3).
//
// Registers a HoverProvider and a DefinitionProvider for `.cljd` files and
// answers both from one `resolve` call to the step-2 daemon. Nothing here
// knows about Dart, rewrite-clj or the analyzer -- the daemon owns all of it.
//
// No conflict with Calva: VSCode merges hovers from every provider that
// answers, and offers multiple definitions as a peek list, so Calva keeps
// answering for everything Clojure-shaped and we answer for the Dart edge.

const fs = require('fs');
const os = require('os');
const path = require('path');
const vscode = require('vscode');

const { Daemon } = require('./client');
const { hoverMarkdown, summary } = require('./hover');

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

// `bin/cljd-resolve`: the setting, then the two layouts the extension can be
// installed in, then whatever is on PATH.
function daemonPath(context) {
  const configured = String(cfg().get('daemonPath') || '').trim();
  if (configured) return expandHome(configured);

  // realpath first: a common install is a symlink from ~/.vscode/extensions
  // into this repo, and `..` has to mean the repo, not the extensions dir.
  let root = context.extensionPath;
  try { root = fs.realpathSync(root); } catch (e) { /* keep the raw path */ }

  const candidates = [
    path.join(root, 'bin', 'cljd-resolve'),        // packaged alongside
    path.join(root, '..', 'bin', 'cljd-resolve'),  // running from the repo
  ];
  for (const p of candidates) if (fs.existsSync(p)) return p;
  return 'cljd-resolve';
}

// VSCode launched from Finder inherits a bare PATH, which is how `bb` goes
// missing on macOS even though it works in every terminal. User `extraPath`
// wins; the usual install dirs are a last resort.
function daemonEnv() {
  const extra = (cfg().get('extraPath') || []).map(expandHome);
  const fallback = process.platform === 'win32' ? [] : [
    '/opt/homebrew/bin',
    '/usr/local/bin',
    path.join(os.homedir(), '.local', 'bin'),
    path.join(os.homedir(), 'bin'),
  ];
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

function setState(state) {
  if (!status) return;
  const s = STATUS[state] || STATUS.stopped;
  status.text = s.text;
  status.tooltip = `${s.tip}\nClick to show the log.`;
}

function showStatus(editor) {
  if (!status) return;
  const isCljd = editor && editor.document.uri.fsPath.endsWith('.cljd');
  if (isCljd) status.show(); else status.hide();
}

function makeDaemon(context) {
  return new Daemon({
    command: daemonPath(context),
    cwd: (vscode.workspace.workspaceFolders || [])[0]?.uri.fsPath,
    env: daemonEnv(),
    timeout: Number(cfg().get('requestTimeout')) || 20000,
    log,
    onState: setState,
  });
}

// One `resolve` round trip. Failures are logged, never thrown at the editor --
// a broken daemon should mean no hover, not a popup on every mouse move.
async function resolveAt(document, position, token) {
  if (!daemon) return null;
  const trace = Boolean(cfg().get('trace'));
  try {
    const hit = await daemon.request('resolve', {
      file: document.uri.fsPath,
      text: document.getText(), // the unsaved buffer, which is the whole point
      line: position.line,
      character: position.character,
    }, { token, trace });
    if (trace) log(`<-- ${summary(hit)}`);
    return hit;
  } catch (e) {
    log(`resolve failed: ${e.message}`);
    return null;
  }
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
async function warmUp(document) {
  if (!daemon || !cfg().get('warmUp')) return;
  if (!document || !document.uri.fsPath.endsWith('.cljd')) return;
  const folder = vscode.workspace.getWorkspaceFolder(document.uri);
  const key = folder ? folder.uri.fsPath : path.dirname(document.uri.fsPath);
  if (warmed.has(key)) return;
  warmed.add(key);
  try {
    const t = Date.now();
    const res = await daemon.request(
      'warmUp',
      { file: document.uri.fsPath, text: document.getText() },
      { timeout: 120000 });
    log(res && res.ok
      ? `warm-up ready in ${Date.now() - t}ms, ${res.libs} librar${res.libs === 1 ? 'y' : 'ies'} (${key})`
      : `warm-up found no Dart project for ${key}`);
    if (!res || !res.ok) warmed.delete(key);
  } catch (e) {
    warmed.delete(key);
    log(`warm-up failed: ${e.message}`);
  }
}

// Tears the daemon down and stands a fresh one up, picking up any changed
// setting on the way.
async function rebuild(context) {
  warmed = new Set();
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

module.exports = { activate, deactivate };
