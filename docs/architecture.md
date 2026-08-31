# How it works

Three pieces, each of which knows nothing about the one below it:

```
extension/  a VSCode HoverProvider and DefinitionProvider -- knows nothing about Dart
src/        a babashka daemon: cursor in a .cljd buffer -> Dart element
helper/     a patched copy of ClojureDart's Dart-analyzer bridge
```

[design.md](../design.md) is the pre-build document — why hovers don't work today, and the two
routes considered. This describes what was built, and why it is shaped the way it is.
The wire format between the extension and the daemon is [protocol.md](protocol.md).

## The patched analyzer helper

`helper/` is a vendored copy of ClojureDart's Dart-analyzer bridge
(`resources/analyzer.dart` @ `01c41592`, analyzer 6.4.1), patched to emit the two things a
hover and a jump are made of and which upstream drops on the floor.

`vendor/analyzer.dart.upstream` is the pristine original, kept for `diff`.
Upgrading it — the pins, what breaks when they drift, and how to merge a new upstream —
is [vendor/README.md](../vendor/README.md); `bb test:vendor` fails if the patch stops being
small, additive and confined.

### New keys

Added to every class, extension, constructor, method, field, enum value, top-level function,
top-level variable **and parameter**:

| key | |
|---|---|
| `:doc` | the doc comment, `///` and `/** */` markers stripped, as EDN-escaped markdown |
| `:file` | absolute path of the declaring `.dart` file |
| `:offset` | character offset of the element's name in that file |
| `:length` | length of the name |

Absent values are simply not emitted — `M()` already drops nulls, so undocumented elements
cost nothing.

### Three cases worth calling out

**`.style` and friends.** Design §3 route B leans on named parameters resolving out of the
enclosing constructor's `:parameters`. A `this.style` parameter carries no doc of its own, so
`paramDocSource` follows the field formal to the field it forwards to (and `super.style` one
constructor further up, up to 4 hops). Hovering `.style` in `(m/Text ... :style ...)` therefore
gets `TextStyle`'s real prose, not nothing.

**Synthetic elements.** `T get widget` induces a synthetic `FieldElement` with no doc and
`nameOffset == -1`; `const pi` is reached through a synthetic getter. `deSynth` walks to
whichever side actually holds the source position, in both directions.

**Escaping.** Doc comments contain quotes, backslashes and newlines. `S()` escapes them the
same way `main()` already escapes `Platform.version`.

### Cost

Docs are emitted unconditionally, which is ~3.3× the payload: `elt package:flutter/material.dart
Scaffold` goes from 17 KB to 57 KB. Fine for a daemon that caches per element; worth knowing
before dropping this on top of a cljd build's analyzer cache.

Static analysis is unchanged — `dart analyze` reports the same 7 pre-existing warnings as
upstream, and no new ones.

### Drop-in compatibility

The patch is additive, and `mk-live-analyzer-info` (`compiler.cljc:208`) maps over analyzer maps
generically — unknown scalar keys fall through its `:else [k v]` branch untouched. So the
patched helper can stand in for the stock one in `.clojuredart/cache/<sha>/cljd_helper/bin/`
without breaking a build. *(Verified by reading the compiler's reader, not by running a build.)*

## The resolve daemon

`src/cljd_resolve/` is a babashka daemon that answers *cursor in a `.cljd` buffer → Dart
element*. It owns the analyzer subprocess, so the extension has nothing to know about Dart.
Its wire protocol is [protocol.md](protocol.md).

### What resolves

| in the buffer | resolves to |
|---|---|
| `(m/Text ...)` | the **unnamed constructor** of `Text` — see below |
| `m/Text` elsewhere | the class `Text` in the aliased library |
| `m/Text.rich` | its named constructor |
| `m.Colors/red` | static member `red` of `Colors` |
| `pi` (`:refer`red) | top-level `pi` in `dart:math` |
| `.style` | the named parameter of the enclosing constructor call — else a field or method of that class |

Aliases come from the `ns` form, and only string requires get one: `[cljd.flutter :as f]` has
nothing behind it the analyzer can answer for, so `f/run` deliberately resolves to nothing.

**`.style` and the flutter DSL.** In `(m/Text "hi" .style ...)` the owner is the enclosing
call's head. But cljd.flutter's `f/run` / `f/widget` write `.home` as a *sibling* of the widget
it attaches to:

```clojure
(f/run
  (m/MaterialApp ...)
  .home
  (m/Scaffold ...)
  .body
  ...)
```

so the daemon falls back to the nearest preceding sibling that names a Dart type — `.home` →
`MaterialApp`, `.body` → `Scaffold`. Purely positional; a candidate that names nothing in Dart
simply fails to resolve and the next one is tried.

**A call head is a constructor, not a class.** Hovering `Text` in `Text('hi')` in a `.dart`
file shows the *constructor's* doc — Dart-Code asks the analysis server for `documentation:
"full"`, so nothing is truncated; it is simply pointing at a smaller element. `(m/Text "hi" ...)`
is the same invocation, so a symbol heading a call resolves to the unnamed constructor too.
For `Text` that is 375 characters against the class's 4228. A symbol that is *not* a call head
— `m/Center` standing alone in an `f/run` body — stays the class, which again is what `.dart`
gives for a bare type reference. Where the constructor carries no doc of its own, the class is
still the better answer and is kept.

**Unbalanced buffers.** A buffer being typed into is unbalanced most of the time and rewrite-clj
refuses it, so on a parse failure the daemon appends the missing delimiters and reparses. Only
appending, so every position before the cursor is untouched.

**What it gives up on**, exactly as design.md §3 says route B would: `(.substring s 1)` — a
method call on a value — needs real type inference, and returns null rather than a guess.

### Cost

Startup is ~3 s (one `dart run` compile) plus ~5.5 s to resolve the first library, once per
project — see [warm-up](#warm-up), which is what keeps that off the first hover. Resolving an
element out of a resolved library is ~10 ms, and every `lib` and `elt` answer is then cached in
memory, so a repeat lookup is sub-millisecond. `clearCache` drops it when the project's own Dart
changes.

### When the analyzer stops answering

The daemon serves one request at a time, so a helper that is alive but never replies would wedge
every hover until you restarted things by hand. Each request therefore runs under a deadline —
20 s by default, `CLJD_RESOLVE_TIMEOUT_MS` to change it. On expiry that helper is retired and its
child destroyed, which is what unwinds the blocked read; the failing request reports the timeout
and the next one starts a fresh helper. A helper that keeps wedging is backed off exactly like
one that will not start, so a broken `dart` costs one compile per backoff window rather than one
per hover.

## The VSCode extension

`extension/` registers a `HoverProvider` and a `DefinitionProvider` for `.cljd` files and
answers both from one shared `resolve` call to the daemon. It knows nothing about Dart,
rewrite-clj or the analyzer — the daemon owns all of that. Plain CommonJS with no dependencies
and no build step: what is in the directory is what runs.

```
extension.js   activation, the two providers, commands, status bar, warm-up
client.js      the daemon subprocess and its JSON-RPC -- no `vscode` import
hover.js       one resolve result -> hover markdown -- no `vscode` import
```

Neither `client.js` nor `hover.js` imports `vscode`, which is what makes the interesting half
testable from plain node.

### It sits beside Calva, not on top of it

The selector is `{language: 'clojure', scheme: 'file', pattern: '**/*.cljd'}` — the same
language Calva claims. That is fine: VSCode merges hovers from every provider that answers and
offers multiple definitions as a peek list. Calva keeps answering for everything Clojure-shaped
and we answer for the Dart edge; neither has to displace the other.

### What it does with a result

`signature` becomes a ```` ```dart ```` fence, so the declaration reads the way it reads in a
`.dart` file — past 72 characters its parameters break one per line, the way `dart format`
breaks them, rather than running off the side of the popup. `doc` is dartdoc, which is *almost*
markdown — `hover.js` closes the gap:

- `{@template …}`, `{@macro …}`, `{@tool …}` and friends are doc-generator markers, not prose,
  so the markers go and the text between them stays.
- `[TextStyle]`, `[TextStyle.color]`, `[of()]` are dartdoc cross-references; VSCode would render
  them as broken links, so they become `` `TextStyle` ``. Real markdown links, reference links
  and reference definitions are left alone, and so is anything inside a code fence — where the
  brackets are Dart source.

`originRange` becomes the hover's range, so the underline covers exactly `.style` and not the
whole form. `defUri`/`defRange` become a `LocationLink`, so a peek highlights the `.cljd` symbol
it came from as well as the Dart it lands on.

### Warm-up

Two costs land once per project, and both are seconds, not milliseconds: starting the analyzer
is a `dart run` compile (~3 s), and resolving the first library out of the Flutter SDK is
another ~5.5 s. Resolving an *element* out of an already-resolved library is ~10 ms.

So opening a `.cljd` file warms the project up — **in chunks, not in one request.** The daemon
serves one request at a time, so a single warm-up call would put a hover typed during it behind
the whole ~8 s. Instead `warmUp` pays only the `dart run` and answers with the libraries the
buffer's `ns` form requires, and the extension asks for them one `warmUpLib` at a time, awaiting
each. The daemon reads stdin line by line, so a hover sent mid warm-up is simply the next line
it reads: it waits for one chunk — ~3 s, then ~5.5 s, then ~10 ms — rather than for the batch.
No concurrency anywhere, on either side.

On `hello`'s `main.cljd` the whole thing is ~8 s at file-open, after which the first hover — on
a class the analyzer has never been asked for — is ~13 ms. Without it that 8 s sits on the first
hover instead. Set `cljd-resolve.warmUp` to false to opt out; a project whose warm-up fails is
simply warmed again the next time one of its files opens.

### Why it runs from the checkout

The extension is five dependency-free files, but what it drives does not travel: the launcher
runs babashka against `bb.edn` and `src/` at the repo root, and those run a Dart helper that
only works after a `dart pub get` in `helper/` on the machine using it. A package could carry
the JavaScript and none of the rest — and you would still need `bb` and a Dart SDK installed —
so there is no `.vsix`, and both supported installs point VSCode at `extension/` where it
already sits.

The symlink install is supported deliberately: the daemon is found by `realpath`ing the
extension directory first, so `../bin/cljd-resolve` (or `.cmd`) means this repo and not the
extensions directory. That is the extension's whole search — the repo above it, then `PATH` —
so a daemon kept anywhere else has to be named in `cljd-resolve.daemonPath`.

Both launchers — `bin/cljd-resolve` (POSIX `sh`) and `bin/cljd-resolve.cmd` (Windows) — find
`bb.edn` relative to themselves, so they work from any current directory.

### When the daemon is unhappy

A failure is never a popup — a broken daemon means no hover, not an error on every mouse move.
Everything goes to the log instead. A crashed daemon is respawned on the next hover; three
failed spawns in a row and it stops trying until you restart it, so a missing `bb` cannot turn
into a process per mouse move.

**Version skew.** Every daemon the extension starts is asked once, with `ping`, which protocol it
speaks. Skew is unlikely by construction — there is no `.vsix`, so the extension runs from the
checkout that holds the daemon and the two move together — and what is left is a
`cljd-resolve.daemonPath` aimed at a second, older clone, or a symlinked install whose repo has
not been pulled. Neither announces itself, so a mismatch is called out loudly and is not fatal:
the log says what happened and what to do, the status bar shows a warning for as long as it is
true, and hovers keep being answered. Refusing to serve would turn skew that is usually harmless
into no hovers at all, over something the extension cannot fix for you.

## The keybinding

The extension contributes it, so there is nothing to put in `keybindings.json`:

```json
{ "command": "editor.action.revealDefinition",
  "key": "ctrl+alt+enter", "mac": "cmd+enter",
  "when": "editorTextFocus && resourceExtname == .cljd" }
```

`cmd+enter` is free on macOS — Calva's eval bindings are all `ctrl+enter`-based. Elsewhere
`ctrl+enter` is Calva's, so the default is `ctrl+alt+enter`; `alt+F12` (peek) and `F12` work
unchanged everywhere.
