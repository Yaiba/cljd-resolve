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

// Two lines stand in for the answers a client has to handle without offering
// anything: line 40 is a cursor nowhere a Dart name can go, line 41 is a shape
// with nothing to suggest for it.
function completion(params) {
  if (params.line === 40) return null;
  if (params.line === 41) return Object.assign({}, COMPLETION, { items: [] });
  return COMPLETION;
}

function answer(req) {
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
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, result: answer(req) }) + '\n');
    if (req.method === 'shutdown') process.exit(0);
  }
});

module.exports = { HIT, LIBS, PROTO, COMPLETION };
