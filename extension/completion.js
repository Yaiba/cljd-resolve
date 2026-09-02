'use strict';
// Turns one daemon `complete` result into what a completion list needs, minus
// VSCode itself -- extension.js wraps these in CompletionItems.
//
// Pure string and object work with no `vscode` import, the same way hover.js
// is, so it is testable from plain node.

const { hoverMarkdown } = require('./hover');

// The daemon's element kinds as VSCode CompletionItemKind names. Dart's
// vocabulary is not the editor's: a constructor's named parameter is a
// `Property`, because that is the icon an editor shows for a name you fill in
// rather than a value you call.
const KINDS = {
  parameter: 'Property',
  field: 'Field',
  method: 'Method',
  function: 'Function',
  constructor: 'Constructor',
  class: 'Class',
  // Top-level kinds, which only a whole-library list produces.
  enum: 'Enum',
  mixin: 'Class',
  typedef: 'Interface',
  extension: 'Class',
};

function itemKind(kind) {
  return KINDS[kind] || 'Value';
}

// What `describe` needs to find one candidate again: the file, the library and
// type the list came from, plus the label. Carried on the item so the editor
// can ask for a docstring when -- and only when -- the user highlights that
// row.
//
// `file` is passed in rather than read back later from the active editor: the
// row is resolved some time after the list was built, and by then focus may
// have moved. An address has to say for itself which document it came from.
//
// `lib` is read off the item first and the result second. Most targets share
// one library and send it once, but `refers` matches names across every
// library the `ns` form referred to, and each of those candidates carries its
// own -- there is no result-level library that would be true of all of them.
//
// `member` rides along when the key a candidate lives under is not the text
// the editor inserts: `m/Icons.add` is inserted whole and keyed `add`.
function address(result, item, file) {
  if (!result || !item) return null;
  const addr = {
    file,
    lib: item.lib || result.lib,
    type: result.type,
    target: result.target,
    label: item.label,
  };
  if (item.member) addr.member = item.member;
  // Which constructor the list was read off, so a parameter two constructors
  // share is described against the one that offered it.
  if (result.ctor) addr.ctor = result.ctor;
  return addr;
}

// The markdown a highlighted candidate shows. The same renderer the hover
// uses, so a symbol reads identically whether it is being chosen or inspected.
function documentation(hit) {
  return hoverMarkdown(hit);
}

module.exports = { KINDS, itemKind, address, documentation };
