'use strict';
// A client for the resolve daemon (design.md §4 step 2): spawn it, speak its
// newline-delimited JSON-RPC on stdio, and hand back promises.
//
// Deliberately free of any `vscode` import, so it can be driven from plain
// node -- which is what test/extension_test.js does.

const { spawn } = require('child_process');

// Three failed spawns in a row and we stop trying: a missing `bb` would
// otherwise mean a new process on every mouse move.
const RESTART_LIMIT = 3;

// The daemon wire protocol this client was written against. Kept in step with
// `protocol-version` in src/cljd_resolve/daemon.clj, which is where the rule
// for bumping it is written down.
const PROTOCOL = 1;

// What is wrong with the daemon `ping` answered with, or null when nothing is.
//
// A daemon answering no `protocol` at all is a daemon from before the field
// existed, which is the same skew by another name -- so it is reported rather
// than waved through.
function protocolComplaint(pong) {
  const theirs = pong && pong.protocol;
  if (theirs === PROTOCOL) return null;
  return (
    `the resolve daemon speaks protocol ${theirs === undefined ? 'nothing' : theirs}, ` +
    `this extension speaks ${PROTOCOL} -- they are from different checkouts, ` +
    'so hovers may be wrong or missing. Pull both to the same commit, or point ' +
    '`cljd-resolve.daemonPath` at this repository.');
}

class Daemon {
  constructor(opts = {}) {
    this.command = opts.command;
    this.args = opts.args || [];
    this.cwd = opts.cwd;
    this.env = opts.env || process.env;
    this.timeout = opts.timeout || 20000;
    this.log = opts.log || (() => {});
    this.onState = opts.onState || (() => {});

    this.proc = null;
    this.pending = new Map();
    this.nextId = 1;
    this.buf = '';
    this.failures = 0;
    this.disposed = false;
  }

  get running() {
    return Boolean(this.proc);
  }

  // ------------------------------------------------------------- the process

  start() {
    if (this.proc) return this.proc;
    if (this.disposed) throw new Error('the daemon client has been disposed');
    if (this.failures >= RESTART_LIMIT) {
      throw new Error(
        `the resolve daemon failed to start ${this.failures} times ` +
        `(${this.command}); fix it and run "ClojureDart: Restart Resolve Daemon"`);
    }

    this.log(`spawning ${this.command} ${this.args.join(' ')}`);
    const proc = spawn(this.command, this.args, {
      cwd: this.cwd,
      env: this.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.proc = proc;
    this.buf = '';

    proc.stdout.setEncoding('utf8');
    proc.stdout.on('data', (chunk) => this._onData(chunk));

    // The daemon inherits its own stderr to the analyzer subprocess, so this
    // carries both `dart run`'s compile chatter and any daemon stack trace.
    proc.stderr.setEncoding('utf8');
    proc.stderr.on('data', (chunk) => {
      const s = String(chunk).replace(/\s+$/, '');
      if (s) this.log(`[daemon] ${s}`);
    });

    proc.stdin.on('error', (err) => this.log(`stdin: ${err.message}`));

    proc.on('error', (err) => {
      this.failures += 1;
      this._down(`could not spawn ${this.command}: ${err.message}`);
    });

    proc.on('exit', (code, signal) => {
      // An orderly `shutdown` clears `pending` first, so anything still
      // waiting here died with the process.
      if (this.pending.size > 0) this.failures += 1;
      this._down(`daemon exited (code ${code}, signal ${signal})`);
    });

    this.onState('starting');
    return proc;
  }

  _down(message) {
    this.proc = null;
    this.log(message);
    for (const [id, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(new Error(message));
      this.pending.delete(id);
    }
    this.onState('stopped');
  }

  _onData(chunk) {
    this.buf += chunk;
    let nl;
    while ((nl = this.buf.indexOf('\n')) >= 0) {
      const line = this.buf.slice(0, nl);
      this.buf = this.buf.slice(nl + 1);
      if (line.trim()) this._onLine(line);
    }
  }

  _onLine(line) {
    let msg;
    try {
      msg = JSON.parse(line);
    } catch (e) {
      // Not ours: anything the daemon's own stdout picked up.
      this.log(`unparseable line from the daemon: ${line.slice(0, 200)}`);
      return;
    }
    const entry = this.pending.get(msg.id);
    if (!entry) return; // cancelled or timed out; the answer is stale
    this.pending.delete(msg.id);
    clearTimeout(entry.timer);
    this.failures = 0;
    this.onState('ready');
    if (msg.error) {
      const err = new Error(msg.error.message || 'daemon error');
      err.code = msg.error.code;
      entry.reject(err);
    } else {
      entry.resolve(msg.result === undefined ? null : msg.result);
    }
  }

  // ------------------------------------------------------------- the traffic

  request(method, params, opts = {}) {
    return new Promise((resolve, reject) => {
      let proc;
      try {
        proc = this.start();
      } catch (e) {
        reject(e);
        return;
      }

      const id = this.nextId++;
      const ms = opts.timeout || this.timeout;
      const entry = { resolve, reject };
      entry.timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${ms}ms`));
      }, ms);
      this.pending.set(id, entry);

      if (opts.token) {
        // A cancelled hover is not a failure: drop the entry and answer
        // nothing. The daemon keeps working and its reply is discarded.
        opts.token.onCancellationRequested(() => {
          if (!this.pending.has(id)) return;
          clearTimeout(entry.timer);
          this.pending.delete(id);
          resolve(null);
        });
      }

      const payload = JSON.stringify({ jsonrpc: '2.0', id, method, params: params || {} });
      if (opts.trace) this.log(`--> ${payload.slice(0, 400)}`);
      try {
        proc.stdin.write(payload + '\n');
      } catch (e) {
        this.pending.delete(id);
        clearTimeout(entry.timer);
        reject(e);
      }
    });
  }

  // Stops the daemon without counting it as a crash.
  async stop() {
    const proc = this.proc;
    if (!proc) return;
    this.proc = null;
    for (const [id, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.resolve(null);
      this.pending.delete(id);
    }
    try {
      proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 0, method: 'shutdown' }) + '\n');
      proc.stdin.end();
    } catch (e) {
      // already gone
    }
    await new Promise((done) => {
      const kill = setTimeout(() => {
        try { proc.kill('SIGKILL'); } catch (e) { /* already gone */ }
        done();
      }, 2000);
      proc.on('exit', () => { clearTimeout(kill); done(); });
    });
    this.onState('stopped');
  }

  dispose() {
    this.disposed = true;
    const stopping = this.stop();
    return stopping;
  }
}

module.exports = { Daemon, RESTART_LIMIT, PROTOCOL, protocolComplaint };
