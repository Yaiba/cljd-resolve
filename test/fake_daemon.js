#!/usr/bin/env node
'use strict';
// A resolve daemon that needs no Dart: it answers one canned hit, for any
// cursor. Stands in for `bin/cljd-resolve` in the provider tests, so those
// exercise the extension's VSCode-facing layer and nothing else.

const { PROTOCOL } = require('../extension/client');

// The libraries `warmUp` hands back for the client to warm one at a time.
const LIBS = ['package:flutter/material.dart', 'dart:core'];

// The protocol this fake claims. `CLJD_FAKE_PROTOCOL` makes it a daemon from
// another checkout, which is what the extension's version check is for.
const PROTO = process.env.CLJD_FAKE_PROTOCOL
  ? Number(process.env.CLJD_FAKE_PROTOCOL)
  : PROTOCOL;

const HIT = {
  kind: 'parameter',
  name: 'style',
  container: 'Text(style:)',
  owner: 'm/Text',
  signature: 'TextStyle? style',
  doc: 'If non-null, the style to use for this text.\n\nSee [TextStyle].',
  lib: 'package:flutter/material.dart',
  defUri: 'file:///flutter/lib/src/widgets/text.dart',
  defRange: { start: { line: 509, character: 9 }, end: { line: 509, character: 14 } },
  originRange: { start: { line: 16, character: 7 }, end: { line: 16, character: 13 } },
};

// One canned completion list, shaped like a real `named-args` answer. Two
// candidates, from the two groups the daemon orders by `sort`.
const COMPLETION = {
  target: 'named-args',
  prefix: 'sty',
  lib: 'package:flutter/material.dart',
  type: 'Text',
  owner: 'm/Text',
  segment: { start: { line: 16, character: 8 }, end: { line: 16, character: 11 } },
  range: { start: { line: 16, character: 7 }, end: { line: 16, character: 11 } },
  items: [
    { label: 'style', kind: 'parameter', detail: 'TextStyle?',
      container: 'Text(style:)', sort: '0style' },
    { label: 'strutStyle', kind: 'field', detail: 'StrutStyle?',
      container: 'Text', sort: '1strutStyle' },
  ],
};

// A `refers` answer, which is the shape with no result-level library: each
// candidate was matched in a different one of the `ns` form's requires, so the
// library rides on the item.
const REFERS = {
  target: 'refers',
  prefix: 'p',
  segment: { start: { line: 42, character: 4 }, end: { line: 42, character: 5 } },
  range: { start: { line: 42, character: 4 }, end: { line: 42, character: 5 } },
  items: [
    { label: 'pi', kind: 'field', detail: 'double',
      container: 'dart:math', lib: 'dart:math', sort: '0pi' },
    { label: 'print', kind: 'function', detail: 'void print(Object?)',
      container: 'dart:core', lib: 'dart:core', sort: '0print' },
  ],
};

// Three lines stand in for the answers a client has to handle: line 40 is a
// cursor nowhere a Dart name can go, line 41 is a shape with nothing to
// suggest for it, line 42 is a `refers` list drawn from two libraries.
function completion(params) {
  if (params.line === 40) return null;
  if (params.line === 41) return Object.assign({}, COMPLETION, { items: [] });
  if (params.line === 42) return REFERS;
  return COMPLETION;
}

// A daemon from before completion existed. It still answers `ping` with the
// current protocol -- the number is bumped only for a change a peer would
// *misread*, and a method added on the end is not one -- so the only thing
// that gives it away is the method itself coming back unknown.
const NO_COMPLETE = Boolean(process.env.CLJD_FAKE_NO_COMPLETE);

class RpcError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

function answer(req) {
  if (NO_COMPLETE && (req.method === 'complete' || req.method === 'describe')) {
    throw new RpcError(-32601, `unknown method: ${req.method}`);
  }
  switch (req.method) {
    case 'resolve': return HIT;
    case 'complete': return completion(req.params || {});
    case 'describe': return HIT;
    case 'warmUp': return { ok: true, libs: LIBS };
    case 'ping': return { ok: true, protocol: PROTO };
    default: return { ok: true };
  }
}

let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buf += chunk;
  let nl;
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl);
    buf = buf.slice(nl + 1);
    if (!line.trim()) continue;
    const req = JSON.parse(line);
    let msg;
    try {
      msg = { jsonrpc: '2.0', id: req.id, result: answer(req) };
    } catch (e) {
      msg = { jsonrpc: '2.0', id: req.id, error: { code: e.code, message: e.message } };
    }
    process.stdout.write(JSON.stringify(msg) + '\n');
    if (req.method === 'shutdown') process.exit(0);
  }
});

module.exports = { HIT, LIBS, PROTO, COMPLETION, REFERS };
