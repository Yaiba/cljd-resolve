'use strict';
// Just enough of the `vscode` module to load extension.js outside VSCode, so
// the provider wiring is tested rather than assumed. `install()` puts it on
// the module path under the name `vscode`; everything it records is on the
// object it returns.

const Module = require('module');

function makeApi() {
  const rec = {
    output: [],
    commands: new Map(),
    providers: { hover: [], definition: [] },
    listeners: { open: [], activeEditor: [], config: [] },
    messages: [],
    status: null,
    config: {},
    activeTextEditor: undefined,
    workspaceFolders: undefined,
  };

  const disposable = () => ({ dispose() {} });
  const event = (bucket) => (fn) => { bucket.push(fn); return disposable(); };

  class Position {
    constructor(line, character) { this.line = line; this.character = character; }
  }
  class Range {
    constructor(start, end) { this.start = start; this.end = end; }
  }
  class MarkdownString {
    constructor(value) { this.value = value; }
  }
  class Hover {
    constructor(contents, range) { this.contents = contents; this.range = range; }
  }

  const api = {
    Position,
    Range,
    MarkdownString,
    Hover,
    StatusBarAlignment: { Left: 1, Right: 2 },
    Uri: { parse: (s) => ({ toString: () => s, fsPath: s.replace(/^file:\/\//, '') }) },

    window: {
      get activeTextEditor() { return rec.activeTextEditor; },
      createOutputChannel: () => ({
        appendLine: (l) => rec.output.push(l),
        show() {},
        dispose() {},
      }),
      createStatusBarItem: () => (rec.status = {
        text: '', tooltip: '', command: '', shown: false,
        show() { this.shown = true; }, hide() { this.shown = false; }, dispose() {},
      }),
      showInformationMessage: (m) => rec.messages.push(['info', m]),
      showErrorMessage: (m) => rec.messages.push(['error', m]),
      onDidChangeActiveTextEditor: event(rec.listeners.activeEditor),
    },

    workspace: {
      get workspaceFolders() { return rec.workspaceFolders; },
      getWorkspaceFolder: () => undefined,
      getConfiguration: () => ({ get: (k) => rec.config[k] }),
      onDidOpenTextDocument: event(rec.listeners.open),
      onDidChangeConfiguration: event(rec.listeners.config),
    },

    languages: {
      registerHoverProvider: (selector, provider) => {
        rec.providers.hover.push({ selector, provider });
        return disposable();
      },
      registerDefinitionProvider: (selector, provider) => {
        rec.providers.definition.push({ selector, provider });
        return disposable();
      },
    },

    commands: {
      registerCommand: (id, fn) => { rec.commands.set(id, fn); return disposable(); },
    },
  };

  return { api, rec };
}

// A document and a cancellation token shaped the way the providers use them.
function document(fsPath, text) {
  return { uri: { fsPath, toString: () => 'file://' + fsPath }, getText: () => text };
}

function token() {
  const fns = [];
  return {
    isCancellationRequested: false,
    onCancellationRequested: (fn) => { fns.push(fn); return { dispose() {} }; },
    cancel() { this.isCancellationRequested = true; fns.forEach((fn) => fn()); },
  };
}

let installed = null;

function install() {
  const { api, rec } = makeApi();
  if (!installed) {
    const load = Module._load;
    Module._load = function (request, parent, isMain) {
      if (request === 'vscode') return installed.api;
      return load.apply(this, arguments);
    };
  }
  installed = { api, rec };
  return installed;
}

module.exports = { install, document, token };
