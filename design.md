# cljd-resolve — hover docs and jump-to-definition for ClojureDart in VSCode

**Goal.** Hovering a Flutter symbol in a `.cljd` file (`m/Text`, `m.Colors/red`, `.style`) shows
its Dart doc comment; `cmd+enter` jumps to its definition. Both already work in `.dart` files.

**Status.** Design only. Nothing built yet. This document is the handoff.

**Verified against:** ClojureDart `01c41592` · Calva `2.0.597` · Dart-Code `3.140.0` · macOS.

Companion page (same content, with diagrams):
<https://claude.ai/code/artifact/bd0d758a-6cc9-4f2e-ba76-8c8174f27c58>

---

## 1. Why it doesn't work today

Two toolchains run side by side in every ClojureDart project and neither can see the edge
between them.

**Calva** claims `.cljd` as languageId `clojure` (verified in
`~/.vscode/extensions/betterthantomorrow.calva-2.0.597/package.json` — `.cljd` is in the
`clojure` language's `extensions` list). Its clj-kondo / clojure-lsp analysis therefore runs
over the file and resolves everything Clojure-shaped: `defn`, locals, ordinary ns aliases.
What it sees in

```clojure
(ns acme.main
  (:require ["package:flutter/material.dart" :as m]
            [cljd.flutter :as f]))
```

is a require of a **string** — an unresolvable namespace. So `m/Text` is just an unknown symbol.

ClojureDart does ship clj-kondo hooks
(`resources/clj-kondo.exports/tensegritics/clojuredart/hooks/{flutter,flutter2,cljd_core}.clj`),
but those only teach kondo the *shape* of macros like `f/widget`. They carry no Dart element
data at all.

**Dart-Code** has the full Dart Analysis Server and answers hover / definition perfectly — but
only for `.dart` files. It never sees a `.cljd` buffer.

```
                          ┌──────────────────────┐
              read as     │  Calva / clojure-lsp │  ✓ defn · locals · ns
              Clojure  ┌─▶│  (clj-kondo)         │  ✕ m/Text — unknown symbol
                       │  └──────────────────────┘
  ┌─────────────────┐  │              ╲
  │ acme/main.cljd  │──┤               ╲  ✕ no path
  │ cursor on m/Text│  │                ╲
  └─────────────────┘  │  ┌──────────────────────┐    ┌──────────────────────┐
              cljd.build  │ lib/cljd-out/*.dart  │───▶│ Dart Analysis Server │
              compiles └─▶│ (aliased imports)    │    │ ✓ dartdoc ✓ goto-def │
                          └──────────────────────┘    └──────────────────────┘
```

The missing piece is the resolver in between: cursor position in a `.cljd` buffer →
Dart library + element (+ member) → docs + definition location.

---

## 2. What ClojureDart already gives you

More of this is built than you'd expect. Each piece below exists and is load-bearing — and
each stops one step short of what a hover needs.

Source tree used for all references below:

```
/Users/gavin/.gitlibs/libs/tensegritics/clojuredart/01c41592574d53b5970dfae4e22019dd328083de/
```

Sample project to test against: `/Users/gavin/code/vibe/cljd/hello` (Flutter kind, ns `acme.main`).

### 2.1 The Dart analyzer bridge

`resources/analyzer.dart`, deployed per-project to
`.clojuredart/cache/<sha>/cljd_helper/bin/analyzer.dart`.

**What's there.** A long-running `package:analyzer` process with a two-command line protocol on
stdin. It already does the hard Dart-side work: resolving a library URI against the project's
package config and dumping a class with every method, constructor, field, parameter name and
type (`_visitInterfaceElement`, analyzer.dart:193).

```
→ lib package:flutter/material.dart
← true

→ elt package:flutter/material.dart Text
← {:kind :class :parameters [...] ...}
```

**What's missing.** It emits **no doc comment and no source location** — the two fields a hover
and a jump are made of. Grep confirms: nothing for `documentation`, `nameOffset`, or `source`
anywhere in its 500 lines.

`package:analyzer`'s `Element` exposes `documentationComment`, `nameOffset`, `nameLength` and
`source.fullName` for free. This is ~30 lines across the three emit sites.

**This is the one hard blocker. Nothing else can produce doc text.**

Useful accident: `clj/src/cljd/build.clj:230` only copies the resource in
`(when-not (.exists analyzer-dart) …)` — so you can drop your own patched `analyzer.dart` into
the cache dir and cljd will leave it alone. Good enough for a spike; fork or vendor for real.

### 2.2 The symbol resolution logic

All in `clj/src/cljd/compiler.cljc`:

| line | fn |
|------|-----|
| 552  | `resolve-symbol` |
| 518  | `resolve-non-local-symbol` |
| 571  | `resolve-type` |
| 1367 | `resolve-static-member` |
| 1003 | `dart-member-lookup` |
| 1108 | `resolve-dart-instance-method` |

**What's there.** Exactly the logic that turns `m/Text`, `m.Colors/red` and `.style` into a Dart
lib + element. Complete, correct, battle-tested — it's what the compiler itself runs.

**What's missing.** It reads from `@nses`, which only exists inside a running compile. No way to
call it from an editor without hosting a compiler process or reimplementing the parts you need.

### 2.3 The source map

`compiler.cljc:170` (`*source-map*`), `build.clj:386` (`smap-search`).

**What's there.** A real position map between generated Dart and original ClojureDart, already
used in anger: it's what rewrites Flutter stack traces back to `.cljd` line/column during
`flutter run` (see `smap-line`, build.clj:415).

**What's missing.** It runs **Dart → cljd**. A hover needs the inverse. And it lives only in the
`cljd.build` JVM's memory — there is no `spit` of it anywhere on disk.

### 2.4 A socket REPL into the live compiler

`build.clj:685` — a `clojure.core.server` socket on a random port, written to `REPL.lock` in
the project root, running in the same JVM that holds `nses` and `analyzer-info`.

**What's missing.** It's a cljd REPL aimed at evaluating forms on the device, not a query
interface. Using it as one means hovers only work while a build watch is running.

---

## 3. Two routes

Both start at a cursor in a `.cljd` buffer and end at the same answer. They differ in which
existing machine they lean on.

```
                     ┌──────────────────┐   ┌────────────────┐   ┌──────────────────────┐
              ┌─────▶│ inverted smap    │──▶│ position in    │──▶│ Dart Analysis Server │  A
              │      │ (persisted)      │   │ cljd-out/*.dart│   │ (executeHoverProvider)│
┌───────────┐ │      └──────────────────┘   └────────────────┘   └──────────────────────┘
│  cursor   │─┤
│  in .cljd │ │      ┌──────────────────┐   ┌────────────────┐   ┌──────────────────────┐
└───────────┘ └─────▶│ ns aliases + form│──▶│ lib + element  │──▶│ analyzer.dart        │  B
                     │ (rewrite-clj)    │   │ material.dart  │   │ (patched)            │
                     └──────────────────┘   │ · Text         │   └──────────────────────┘
                                            └────────────────┘
```

The choice is the middle step: **A translates a position**, **B translates a name**.

### Route A — map the position, delegate to Dart-Code

Persist and invert the smap so you can go `cljd(file,line,col)` → position in
`lib/cljd-out/*.dart`. Then call the built-in `vscode.executeHoverProvider` /
`vscode.executeDefinitionProvider` commands against the generated Dart URI — VSCode lets one
extension query another's providers — and re-render the result over the `.cljd` buffer.

- **Inherit:** Dart-Code's exact dartdoc rendering, real go-to-definition into Flutter SDK
  sources. Correct by construction.
- **Pay:** upstream patch to emit the smap to disk, an inverted index, and a hard dependency on
  the build being current — stale/unsaved buffers degrade.

### Route B — resolve the symbol yourself  ← recommended

Parse the `.cljd` with rewrite-clj: read the `ns` requires, find the symbol under the cursor,
classify it.

| form | resolves to |
|------|-------------|
| `m/Text` | lib `package:flutter/material.dart`, element `Text` |
| `m.Colors/red` | element `Colors`, member `red` |
| `.style`, `.title` | **named parameter of the enclosing constructor call** — falls straight out of that constructor's `:parameters`, no type inference needed |

That third row is the important one: it's the bulk of real Flutter code and it's purely
syntactic.

- **Get:** no running build required, works on unsaved buffers, ~90% of the Flutter surface.
- **Give up:** anything needing real type inference (`.foo` on a local, symbols threaded through
  macros) falls back or returns nothing.

### Verdict

**Build B.** Add A later as the fallback for inference-hard cases, once the analyzer patch from
step 1 has already paid for itself.

---

## 4. The build list

Step 1 blocks everything downstream — both routes need it.

### 1. Patch `analyzer.dart`  ⟵ hard blocker

Emit `:doc`, `:file`, `:offset`, `:length` on classes, constructors, methods and fields.
Fork ClojureDart or vendor the helper.

Emit sites in `resources/analyzer.dart`:
- `_visitInterfaceElement` (~:193) — the class map, plus its `methods` / `constructors` /
  `fields` loops
- the top-level function emitter (~:356)
- the top-level field emitters (~:312, ~:325)

Note `M()` (analyzer.dart:18) drops null/false/empty values, so absent docs cost nothing.
Doc strings need EDN-escaping the same way `Platform.version` is escaped in `main()`.

### 2. A resolve daemon

Clojure or babashka. Owns the analyzer subprocess, exposes one call over stdio JSON-RPC:

```
resolve {file, line, col}
  → {doc, signature, defUri, defRange}
```

Holds the rewrite-clj parse and the per-ns alias table; caches `elt` responses the way the
compiler already does (`mk-live-analyzer-info`, compiler.cljc:209).

### 3. The VSCode extension

Register a `HoverProvider` and a `DefinitionProvider` for
`{language: 'clojure', pattern: '**/*.cljd'}`.

No conflict with Calva: VSCode merges hovers from multiple providers and offers multiple
definitions as a peek list, so you never have to displace it. **This is where all the real work
lives.**

### 4. The keybinding

`cmd+enter` is free — Calva's eval bindings are all `ctrl+enter`-based (checked its
`contributes.keybindings`). One entry in `keybindings.json`:

```json
{ "key": "cmd+enter",
  "command": "editor.action.revealDefinition",
  "when": "editorTextFocus && resourceExtname == .cljd" }
```

The jump is free once step 3's `DefinitionProvider` answers.

---

## 5. What was checked

**Verified directly** against the source on this machine:

- analyzer.dart emits no docs and no locations (grep: no `documentation`, `nameOffset`, `source`).
- Its stdin protocol is exactly the two `lib` / `elt` commands, returning EDN.
- The helper is copied from `resources/analyzer.dart` only when absent (build.clj:230).
- The smap runs Dart → cljd, in memory, with no persistence anywhere in `build.clj` or
  `compiler.cljc`.
- The socket REPL and its `REPL.lock` port file (build.clj:685).
- Calva maps `.cljd` → languageId `clojure`, and binds no `cmd+enter`.

**Taken on documentation, not run here:** that `vscode.executeHoverProvider` resolves across
extension boundaries. It's a documented built-in command, and Route A rests on it — worth a
ten-minute spike before committing to that path.
