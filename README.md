# cljd-resolve

Hover docs and jump-to-definition for ClojureDart in VSCode. See [design.md](design.md)
for the full design; this README covers what is built.

## Status

| step | | |
|---|---|---|
| 1 | Patch `analyzer.dart` to emit docs + locations | **done** |
| 2 | Resolve daemon | **done** |
| 3 | VSCode extension | **done** |
| 4 | `cmd+enter` keybinding | **done** — the extension contributes it |

## Layout

```
helper/     the patched analyzer.dart (step 1)
vendor/     the pristine upstream analyzer.dart, for `diff`
src/        the resolve daemon (step 2)
bin/        cljd-resolve -- launches the daemon
extension/  the VSCode extension (steps 3 and 4)
test/       five suites plus test/fixture, the Dart project they run against
```

## Step 1 — the patched analyzer helper

`helper/` is a vendored copy of ClojureDart's Dart-analyzer bridge
(`resources/analyzer.dart` @ `01c41592`, analyzer 6.4.1), patched to emit the two things a
hover and a jump are made of and which upstream drops on the floor.

`vendor/analyzer.dart.upstream` is the pristine original, kept for `diff`.

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

## Running it

```bash
dart pub get --directory=helper

printf 'lib package:flutter/material.dart\nelt package:flutter/material.dart Text\n' \
  | dart run helper/bin/analyzer.dart /path/to/a/flutter/project
```

The protocol is one EDN **form** per command — not one line. `M()` writes newlines between map
entries, so responses span many lines; read them with a pushback reader, the way
`mk-live-analyzer-info` does.

## Step 2 — the resolve daemon

`src/cljd_resolve/` is a babashka daemon that answers *cursor in a `.cljd` buffer → Dart
element*. It owns the step-1 analyzer subprocess, so the extension in step 3 has nothing to
know about Dart.

```bash
bin/cljd-resolve            # newline-delimited JSON-RPC on stdio
```

### Protocol

One JSON object per line in, one per line out. Line-delimited rather than LSP's
`Content-Length` framing — the only client is our own extension, and this is drivable from a
shell. Positions on the wire are LSP's: **0-based** `line` and `character`.

```json
{"jsonrpc":"2.0","id":1,"method":"resolve",
 "params":{"file":"/p/src/acme/main.cljd","line":16,"character":8}}
```

```json
{"jsonrpc":"2.0","id":1,"result":{
  "kind":"parameter", "name":"style", "container":"Text(style:)", "owner":"m/Text",
  "signature":"TextStyle? style",
  "doc":"If non-null, the style to use for this text.\n\n...",
  "lib":"package:flutter/material.dart",
  "defUri":"file:///.../widgets/text.dart",
  "defRange":{"start":{"line":509,"character":9},"end":{"line":509,"character":14}},
  "originRange":{"start":{"line":16,"character":7},"end":{"line":16,"character":13}}}}
```

`defUri`/`defRange` are the jump; `doc`/`signature` are the hover; `originRange` is the span in
the `.cljd` buffer, so the editor underlines exactly the symbol. An unresolvable cursor is
`result: null`, never an error.

`params.text` is optional and holds the buffer's **unsaved** contents; `file` is then used only
to find the Dart project. Other methods: `warmUp` (starts the analyzer for `file`'s project and
resolves the libraries its `ns` form requires, answering `{ok, libs}` when they are ready — see
[warm-up](#warm-up)), `ping`, `clearCache`, `shutdown`.

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

## Step 3 — the VSCode extension

`extension/` registers a `HoverProvider` and a `DefinitionProvider` for `.cljd` files and
answers both from a single `resolve` call to the step-2 daemon. It knows nothing about Dart,
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
breaks them, rather than running off the side of the popup. `doc` is dartdoc, which is *almost* markdown — `hover.js` closes the gap:

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

So opening a `.cljd` file fires `warmUp`, which starts the analyzer and then resolves every
library the buffer's `ns` form requires. On `hello`'s `main.cljd` that is ~8 s at file-open,
after which the first hover — on a class the analyzer has never been asked for — is ~13 ms.
Without it that 8 s sits on the first hover instead. Set `cljd-resolve.warmUp` to false to opt
out.

### Installing it

The extension needs to find `bin/cljd-resolve`, which needs `bb` on its `PATH`.

```bash
# from the repo, for a scratch window
code --extensionDevelopmentPath="$PWD/extension" /path/to/a/cljd/project

# or, to have it always on
ln -s "$PWD/extension" ~/.vscode/extensions/cljd-resolve
```

The symlink is supported deliberately: the daemon is found by `realpath`ing the extension
directory first, so `../bin/cljd-resolve` means this repo and not `~/.vscode/extensions`.

**If hovers do nothing, it is almost always `bb`.** VSCode launched from Finder inherits a much
barer `PATH` than your terminal does. *ClojureDart: Show Resolve Log* says so plainly; add the
directory to `cljd-resolve.extraPath` (`/opt/homebrew/bin`, `/usr/local/bin` and `~/.local/bin`
are already tried as fallbacks).

### Settings and commands

| setting | |
|---|---|
| `cljd-resolve.daemonPath` | path to `bin/cljd-resolve`; empty means next to the extension, then the repo above it, then `PATH` |
| `cljd-resolve.extraPath` | directories prepended to `PATH` when spawning the daemon |
| `cljd-resolve.requestTimeout` | ms to wait for one resolve (default 20000) |
| `cljd-resolve.warmUp` | start the analyzer when a `.cljd` file opens (default true) |
| `cljd-resolve.trace` | log every request and result |

*ClojureDart: Restart Resolve Daemon* · *Clear Analyzer Cache* — after the project's own Dart
changes · *Show Resolve Log*.

A status bar item appears for `.cljd` files: spinning while the analyzer starts, and a click
away from the log.

### When the daemon is unhappy

A failure is never a popup — a broken daemon means no hover, not an error on every mouse move.
Everything goes to the log instead. A crashed daemon is respawned on the next hover; three
failed spawns in a row and it stops trying until you restart it, so a missing `bb` cannot turn
into a process per mouse move.

## Step 4 — the keybinding

The extension contributes it, so there is nothing to put in `keybindings.json`:

```json
{ "command": "editor.action.revealDefinition",
  "key": "ctrl+alt+enter", "mac": "cmd+enter",
  "when": "editorTextFocus && resourceExtname == .cljd" }
```

`cmd+enter` is free on macOS — Calva's eval bindings are all `ctrl+enter`-based. Elsewhere
`ctrl+enter` is Calva's, so the default is `ctrl+alt+enter`; `alt+F12` (peek) and `F12` work
unchanged everywhere.

## Tests

```bash
bb test                        # everything that does not need a Flutter SDK
bb test:parse                  # syntax only -- no Dart, instant
bb test:registry               # the analyzer registry's supervision rules, no Dart
bb test:extension              # the extension, no VSCode and no Dart, instant
bb test:analyzer    [project]  # the patched helper (step 1)
bb test:resolve     [project]  # the daemon end to end (step 2)
bb test:flutter     [project]  # the last two again, against a real Flutter SDK
bb test:material-ui [project]  # and again, against the standalone material_ui
```

Every suite prints one line per check and exits non-zero on failure.

The two integration suites run against a **target**: a Dart project, plus the names of the
declarations to probe in it (`test/cljd_resolve/test_target.clj`). There are three.

`fixture` is the default — `test/fixture`, a checked-in, dependency-free Dart package whose
`lib/widgets.dart` mirrors the *shapes* the suites care about (a documented class whose
`this.x` named parameters inherit their field's doc, a static const reached through a dotted
alias, an enum, an abstract getter) under names of its own: `App`, `Panel`, `Label`,
`Palette/red`. It needs a Dart SDK and nothing else, and `dart pub get` is run for you on
first use, so `bb test` works on a clean checkout.

`flutter` is the same assertions against `package:flutter/material.dart` — `MaterialApp`,
`Scaffold`, `Text`, `Colors.red`. It needs a real Flutter project, named as the suite's first
argument or in `CLJD_TEST_PROJECT`; naming one selects this target, and `CLJD_TEST_TARGET`
overrides that either way.

`material_ui` is those same assertions once more against
`package:material_ui/material_ui.dart`, the standalone package Flutter 3.47 split Material out
of the SDK into. Nothing in the resolver knows what a Flutter library is — an alias's URI comes
out of the `ns` form and goes straight to the analyzer — so the two targets share one
vocabulary and differ only in `:lib`, which is exactly the difference worth a regression test.
It needs a project that depends on `material_ui`, so naming a project never implies it; ask for
it with `CLJD_TEST_TARGET=material_ui`, or `bb test:material-ui`.

A suite that cannot run — no `dart` on `PATH`, or no project for the flutter tier — prints
`SKIP` and passes, so a machine without the SDK still gets the rest. `CLJD_TEST_STRICT=1`
turns those skips into failures; CI sets it on the tiers that just installed an SDK, where a
skip would mean a silently empty job.

`.github/workflows/ci.yml` runs the three no-SDK suites and the fixture tier on every push. The
flutter tier is weekly and on demand, against a throwaway `flutter create` app with
`material_ui` added to it -- it runs both Material targets, is slow, and pins us to an SDK
release, and nothing in it is a regression the fixture tier would miss.

**`test/analyzer_test.clj`** — 25-odd checks against the target library. Beyond presence, every
`:offset`/`:length` is spot-checked by seeking into the named file and confirming the characters
there really are the element's name, and the target's fattest class is read back whole through
`clojure.edn` to prove the escaping holds.

**`test/registry_test.clj`** — the analyzer registry with the process layer stubbed out: one
`dart run` per root under concurrency, a dead helper stopped before it is replaced, and a broken
helper backed off rather than recompiled on every hover. No Dart.

**`test/parse_test.clj`** — the alias table, symbol classification, owner candidates, unbalanced
buffers, Dart rendering, and offset → line/column. No subprocess, so it runs in a blink.

**`test/extension_test.js`** — plain node, no VSCode and no Dart. Covers the dartdoc-to-markdown
rendering, the daemon client (concurrency, cancellation, timeouts, a daemon that will not start),
and then loads `extension.js` against a stubbed `vscode` module (`test/vscode_stub.js`) and a
canned daemon (`test/fake_daemon.js`) to check the providers, commands and warm-up are wired the
way VSCode will call them.

**`test/resolve_test.clj`** — drives `bin/cljd-resolve` as a subprocess over the wire protocol,
against a buffer that is never written to disk. Every `defRange` is confirmed by reading the
Dart file and checking the characters at that line and column really are the element's name.
