'use strict';
// Turns one daemon `resolve` result into the markdown a hover shows.
//
// Pure string work, with no `vscode` import, so it is testable from plain node
// -- extension.js wraps the result in a MarkdownString.

// Dartdoc's own directives. They are machine markers, not prose: `{@template}`
// and `{@macro}` splice text at doc-generation time, `{@tool}` brackets a
// runnable sample, `{@animation}` and `{@youtube}` embed media a hover cannot
// show. Dropping the markers leaves the surrounding prose (and a tool block's
// sample code) intact.
const DIRECTIVE = /\{@[^{}]*\}/g;

// A dartdoc cross-reference: `[Text]`, `[TextStyle.color]`, `[of]`. Excluded
// are real markdown links `[text](url)`, reference definitions `[ref]: url`
// and both halves of a reference link `[text][ref]` -- the first ends in `[`,
// the second follows a `]`.
const REFERENCE = /(?<!\])\[([A-Za-z_$][\w$]*(?:\.[\w$]+)*(?:\(\))?)\](?![([:])/g;

function cleanDoc(doc) {
  if (!doc) return '';
  let s = String(doc).replace(DIRECTIVE, '');
  s = linkifyRefs(s);
  return s.replace(/\n{3,}/g, '\n\n').trim();
}

// `[Foo]` -> `` `Foo` ``, but never inside a fenced code block, where the
// brackets are Dart source and backticks would corrupt it.
function linkifyRefs(s) {
  return s
    .split(/(^```[\s\S]*?^```$)/m)
    .map((chunk, i) => (i % 2 === 1 ? chunk : chunk.replace(REFERENCE, '`$1`')))
    .join('');
}

// The one dim line under the doc: what kind of thing this is, what holds it,
// and which Dart library it came from.
function footer(hit) {
  const bits = [];
  if (hit.kind) bits.push(`*${hit.kind}*`);
  if (hit.container && hit.container !== hit.lib) bits.push(`in \`${hit.container}\``);
  const head = bits.join(' ');
  const parts = [];
  if (head) parts.push(head);
  if (hit.lib) parts.push(hit.lib);
  return parts.join(' · ');
}

// The whole hover body, as markdown.
function hoverMarkdown(hit) {
  if (!hit) return '';
  const parts = [];
  if (hit.signature) parts.push('```dart\n' + hit.signature + '\n```');
  const doc = cleanDoc(hit.doc);
  if (doc) parts.push(doc);
  const foot = footer(hit);
  if (foot) parts.push('---\n' + foot);
  return parts.join('\n\n');
}

// One line for the output channel / status bar.
function summary(hit) {
  if (!hit) return 'no match';
  return `${hit.kind || '?'} ${hit.name}${hit.container ? ` in ${hit.container}` : ''}`;
}

module.exports = { cleanDoc, linkifyRefs, footer, hoverMarkdown, summary };
