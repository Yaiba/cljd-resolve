#!/usr/bin/env node
'use strict';
// A resolve daemon that needs no Dart: it answers one canned hit, for any
// cursor. Stands in for `bin/cljd-resolve` in the provider tests, so those
// exercise the extension's VSCode-facing layer and nothing else.

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
    const result = req.method === 'resolve' ? HIT : { ok: true };
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: req.id, result }) + '\n');
    if (req.method === 'shutdown') process.exit(0);
  }
});

module.exports = { HIT };
