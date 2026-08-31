#!/usr/bin/env node
'use strict';
// Tests for the VSCode extension (design.md §4 step 3), minus VSCode itself:
// the hover markdown, and the daemon client driving a real `bin/cljd-resolve`
// subprocess. No Dart and no analyzer -- `ping` needs neither -- so this runs
// in a blink.
//
//   node test/extension_test.js

const path = require('path');
const { Daemon } = require('../extension/client');
const { cleanDoc, footer, hoverMarkdown } = require('../extension/hover');
const stub = require('./vscode_stub');

let failures = 0;

function check(ok, label, extra) {
  if (ok) {
    console.log('  ok   ' + label);
  } else {
    failures += 1;
    const s = extra === undefined ? '' : ' -- ' + JSON.stringify(extra).slice(0, 220);
    console.log('  FAIL ' + label + s);
  }
}

function checkEq(expected, actual, label) {
  check(expected === actual, label, { expected, actual });
}

// ------------------------------------------------------------ doc rendering

console.log('\ndoc cleanup');
{
  checkEq('', cleanDoc(null), 'no doc');

  checkEq('The text to display.',
    cleanDoc('{@template flutter.widgets.text}The text to display.{@endtemplate}'),
    'template markers are dropped, prose kept');

  checkEq('See also.', cleanDoc('{@macro flutter.foo}\n\nSee also.'), 'a macro directive is dropped');

  checkEq('The style to use for `Text`.',
    cleanDoc('The style to use for [Text].'),
    'a dartdoc reference becomes code');

  checkEq('See `TextStyle.color` and `of()`.',
    cleanDoc('See [TextStyle.color] and [of()].'),
    'dotted and called references');

  checkEq('See the [docs](https://flutter.dev).',
    cleanDoc('See the [docs](https://flutter.dev).'),
    'a real markdown link is left alone');

  checkEq('A [ref][id] and\n\n[id]: https://x',
    cleanDoc('A [ref][id] and\n\n[id]: https://x'),
    'reference links and their definitions are left alone');

  checkEq('a\n\nb', cleanDoc('a\n\n\n\n\nb\n\n'), 'runs of blank lines collapse');

  const fenced = 'Use it:\n\n```dart\nfinal x = list[0];\nvar y = m[key];\n```\n\nDone.';
  checkEq(fenced, cleanDoc(fenced), 'brackets inside a code fence are untouched');

  checkEq('Before `A`.\n\n```dart\nx[0]\n```\n\nAfter `B`.',
    cleanDoc('Before [A].\n\n```dart\nx[0]\n```\n\nAfter [B].'),
    'references on both sides of a fence still convert');
}

console.log('\nfooter');
{
  checkEq('*parameter* in `Text(style:)` · package:flutter/material.dart',
    footer({ kind: 'parameter', container: 'Text(style:)', lib: 'package:flutter/material.dart' }),
    'a named parameter names its constructor');

  checkEq('*class* · package:flutter/material.dart',
    footer({ kind: 'class', container: 'package:flutter/material.dart', lib: 'package:flutter/material.dart' }),
    'a class does not repeat the library as its container');

  checkEq('', footer({}), 'nothing to say');
}

console.log('\nhover markdown');
{
  const hit = {
    kind: 'parameter',
    name: 'style',
    container: 'Text(style:)',
    signature: 'TextStyle? style',
    doc: 'If non-null, the style to use for this text.\n\nSee [TextStyle].',
    lib: 'package:flutter/material.dart',
  };
  const md = hoverMarkdown(hit);
  check(md.startsWith('```dart\nTextStyle? style\n```'), 'the signature leads, as Dart', md);
  check(md.includes('If non-null, the style to use for this text.'), 'the doc follows');
  check(md.includes('See `TextStyle`.'), 'the doc is cleaned on the way through');
  check(md.trimEnd().endsWith('*parameter* in `Text(style:)` · package:flutter/material.dart'),
    'the footer is last');

  checkEq('```dart\nclass Text extends StatelessWidget\n```',
    hoverMarkdown({ signature: 'class Text extends StatelessWidget' }),
    'a signature with no doc and no library is the whole hover');

  checkEq('', hoverMarkdown(null), 'no hit, no hover');
}

// --------------------------------------------------------------- the client

const repo = path.resolve(__dirname, '..');

function daemon(opts) {
  return new Daemon(Object.assign({
    command: path.join(repo, 'bin', 'cljd-resolve'),
    cwd: repo,
    timeout: 30000,
    log: () => {},
  }, opts));
}

async function main() {
  console.log('\nthe daemon client');
  {
    const d = daemon();
    const states = [];
    d.onState = (s) => states.push(s);

    const pong = await d.request('ping', {});
    check(pong && pong.ok === true, 'ping answers over the wire', pong);
    check(states[0] === 'starting' && states.includes('ready'), 'it reports starting then ready', states);

    // Two in flight at once: the daemon answers in order, and ids keep the
    // answers apart.
    const both = await Promise.all([d.request('ping', {}), d.request('ping', {})]);
    check(both.every((r) => r && r.ok), 'concurrent requests both answer', both);

    let err = null;
    try { await d.request('nonsense', {}); } catch (e) { err = e; }
    check(err !== null, 'an unknown method rejects');
    check(err && err.code === -32601, 'with the JSON-RPC code the daemon sent', err && err.code);
    check((await d.request('ping', {})).ok, 'and the daemon is still usable after it');

    // A resolve on a file in no Dart project is a null result, not an error.
    const miss = await d.request('resolve', {
      file: path.join(repo, 'test', 'nothing.cljd'),
      text: '(ns x)\n',
      line: 0,
      character: 2,
    });
    checkEq(null, miss, 'an unresolvable cursor is a null result');

    // A cancelled request answers null without disturbing the daemon.
    const listeners = [];
    const token = {
      isCancellationRequested: false,
      onCancellationRequested: (fn) => listeners.push(fn),
    };
    const cancelled = d.request('ping', {}, { token });
    listeners.forEach((fn) => fn());
    checkEq(null, await cancelled, 'a cancelled request resolves to null');
    check((await d.request('ping', {})).ok, 'and the daemon is still usable after that');

    await d.dispose();
    check(!d.running, 'dispose stops the process');

    let disposedErr = null;
    try { await d.request('ping', {}); } catch (e) { disposedErr = e; }
    check(disposedErr !== null, 'a disposed client refuses new requests');
  }

  console.log('\nspawn failures');
  {
    const d = daemon({ command: path.join(repo, 'bin', 'no-such-daemon') });
    let last = null;
    for (let i = 0; i < 5; i += 1) {
      try { await d.request('ping', {}); } catch (e) { last = e; }
    }
    check(last !== null, 'a missing daemon rejects rather than hangs', last && last.message);
    check(/failed to start/.test(last.message),
      'and after three tries it stops respawning', last.message);
    await d.dispose();
  }

  console.log('\ntimeouts');
  {
    // Something that reads our line and never answers.
    const d = daemon({ command: '/bin/sleep', args: ['5'], timeout: 300 });
    let err = null;
    try { await d.request('ping', {}); } catch (e) { err = e; }
    check(err !== null && /timed out/.test(err.message), 'a silent daemon times out', err && err.message);
    await d.dispose();
  }

  // ------------------------------------------------------------ the providers

  console.log('\nthe providers');
  {
    // extension.js is loaded against a stubbed `vscode`, talking to a daemon
    // that answers one canned hit -- so what is under test is the wiring
    // between the two, and nothing else.
    const { rec } = stub.install();
    rec.config = {
      daemonPath: path.join(repo, 'test', 'fake_daemon.js'),
      extraPath: [],
      requestTimeout: 10000,
      warmUp: true,
      trace: false,
    };
    const doc = stub.document('/p/src/acme/main.cljd', '(ns acme.main)\n');
    rec.activeTextEditor = { document: doc };

    const extension = require('../extension/extension');
    const subscriptions = [];
    extension.activate({ extensionPath: path.join(repo, 'extension'), subscriptions });

    check(rec.providers.hover.length === 1, 'a hover provider is registered');
    check(rec.providers.definition.length === 1, 'a definition provider is registered');
    const selector = rec.providers.hover[0].selector;
    check(selector.language === 'clojure' && selector.pattern === '**/*.cljd',
      'for .cljd files claimed as clojure -- alongside Calva, not instead of it', selector);
    for (const id of ['cljd-resolve.restart', 'cljd-resolve.clearCache', 'cljd-resolve.showLog']) {
      check(rec.commands.has(id), `the ${id} command exists`);
    }

    const tok = stub.token();

    const hover = await rec.providers.hover[0].provider.provideHover(doc, { line: 16, character: 8 }, tok);
    check(hover && hover.contents && /TextStyle\? style/.test(hover.contents.value),
      'a hover carries the signature as markdown');
    check(hover && /See `TextStyle`/.test(hover.contents.value), 'and the cleaned doc');
    check(hover && hover.range && hover.range.start.line === 16 && hover.range.start.character === 7,
      'and underlines the symbol, not the whole line', hover && hover.range);

    const defs = await rec.providers.definition[0].provider.provideDefinition(doc, { line: 16, character: 8 }, tok);
    check(Array.isArray(defs) && defs.length === 1, 'a definition comes back as one link');
    const link = defs[0];
    checkEq('file:///flutter/lib/src/widgets/text.dart', link.targetUri.toString(), 'pointing into the Dart source');
    check(link.targetRange.start.line === 509 && link.targetRange.start.character === 9,
      'at the element name', link.targetRange);
    check(link.originSelectionRange.start.character === 7, 'linked from the .cljd symbol');

    const cancelled = stub.token();
    const pending = rec.providers.hover[0].provider.provideHover(doc, { line: 16, character: 8 }, cancelled);
    cancelled.cancel();
    checkEq(null, await pending, 'a cancelled hover answers nothing');

    // What the daemon was actually asked for, straight off the trace log.
    rec.config.trace = true;
    const sent = (method) => rec.output.filter((l) => l.includes(`"method":"${method}"`)).length;

    // Both providers fire on the same cursor, so that is one round trip.
    {
      const before = sent('resolve');
      const [h, d] = await Promise.all([
        rec.providers.hover[0].provider.provideHover(doc, { line: 20, character: 4 }, stub.token()),
        rec.providers.definition[0].provider.provideDefinition(doc, { line: 20, character: 4 }, stub.token()),
      ]);
      check(h && Array.isArray(d) && d.length === 1,
        'a hover and a definition on one cursor both answer');
      checkEq(1, sent('resolve') - before, 'from a single shared resolve');
    }

    // The shared request carries nobody's token, so one consumer cancelling
    // must not take the answer away from the other.
    {
      const a = stub.token();
      const b = stub.token();
      const hoverP = rec.providers.hover[0].provider.provideHover(doc, { line: 21, character: 4 }, a);
      const defP = rec.providers.definition[0].provider.provideDefinition(doc, { line: 21, character: 4 }, b);
      a.cancel();
      checkEq(null, await hoverP, 'the consumer that cancels answers nothing');
      const links = await defP;
      check(Array.isArray(links) && links.length === 1,
        'and the one still waiting gets the shared value anyway', links);
    }

    // ... and the entry goes away when it settles, so the same cursor asked
    // again later is a fresh request rather than a stale answer.
    {
      const before = sent('resolve');
      await rec.providers.hover[0].provider.provideHover(doc, { line: 21, character: 4 }, stub.token());
      checkEq(1, sent('resolve') - before, 'a settled share is dropped, not reused');
    }

    check(rec.output.some((l) => /warm-up ready/.test(l)), 'the daemon is warmed on activation', rec.output);
    check(rec.status && rec.status.shown === true, 'the status bar shows for a .cljd editor');
    rec.listeners.activeEditor.forEach((fn) => fn({ document: stub.document('/p/other.clj', '') }));
    check(rec.status.shown === false, 'and hides for anything else');

    const beforeWarm = rec.output.length;
    await rec.commands.get('cljd-resolve.clearCache')();
    check(rec.messages.some(([kind, m]) => kind === 'info' && /cache cleared/i.test(m)),
      'the clear-cache command reaches the daemon', rec.messages);

    // Warming is chunked so a hover queues behind one chunk, not the batch:
    // one request to start the analyzer, then one per library it named.
    const rewarm = rec.output.slice(beforeWarm);
    const asked = (method) => rewarm.filter((l) => l.includes(`"method":"${method}"`)).length;
    checkEq(1, asked('warmUp'), 'warming starts the analyzer in a request of its own');
    checkEq(2, asked('warmUpLib'), 'and then asks for one library per request');
    check(rewarm.some((l) => /warm-up ready in \d+ms, 2 libraries/.test(l)),
      'and reports the whole warm-up once the last chunk lands', rewarm);

    await extension.deactivate();
  }

  console.log('');
  if (failures === 0) {
    console.log('all checks passed');
  } else {
    console.log(failures + ' check(s) FAILED');
  }
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
