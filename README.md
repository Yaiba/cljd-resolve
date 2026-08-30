# cljd-resolve

Hover docs and jump-to-definition for ClojureDart in VSCode. See [design.md](design.md)
for the full design; this README covers what is built.

## Status

| step | | |
|---|---|---|
| 1 | Patch `analyzer.dart` to emit docs + locations | **done** |
| 2 | Resolve daemon | **done** |
| 3 | VSCode extension | not started |
| 4 | `cmd+enter` keybinding | not started |

## Layout

```
helper/     the patched analyzer.dart (step 1)
vendor/     the pristine upstream analyzer.dart, for `diff`
src/        the resolve daemon (step 2)
bin/        cljd-resolve -- launches the daemon
test/       three suites; `bb test` runs them all
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
to find the Dart project. Other methods: `ping`, `clearCache`, `shutdown`.

### What resolves

| in the buffer | resolves to |
|---|---|
| `m/Text` | class `Text` in the aliased library |
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

**Unbalanced buffers.** A buffer being typed into is unbalanced most of the time and rewrite-clj
refuses it, so on a parse failure the daemon appends the missing delimiters and reparses. Only
appending, so every position before the cursor is untouched.

**What it gives up on**, exactly as design.md §3 says route B would: `(.substring s 1)` — a
method call on a value — needs real type inference, and returns null rather than a guess.

### Cost

Analyzer startup is ~8 s (one `dart run` compile), once per project, on the first hover. Every
`elt` answer is then cached in memory, so a repeat lookup is sub-millisecond. `clearCache` drops
it when the project's own Dart changes.

## Tests

```bash
bb test                      # all three suites
bb test:parse                # syntax only -- no Dart, instant
bb test:analyzer [project]   # the patched helper (step 1)
bb test:resolve  [project]   # the daemon end to end (step 2)
```

The project defaults to `../cljd/hello`. All three print one line per check and exit non-zero on
failure.

**`test/analyzer_test.clj`** — 30 checks against a real Flutter SDK. Beyond presence, every
`:offset`/`:length` is spot-checked by seeking into the named file and confirming the characters
there really are the element's name, and the whole `Scaffold` payload is read back through
`clojure.edn` to prove the escaping holds.

**`test/parse_test.clj`** — the alias table, symbol classification, owner candidates, unbalanced
buffers, Dart rendering, and offset → line/column. No subprocess, so it runs in a blink.

**`test/resolve_test.clj`** — drives `bin/cljd-resolve` as a subprocess over the wire protocol,
against a buffer that is never written to disk. Every `defRange` is confirmed by reading the
Dart file and checking the characters at that line and column really are the element's name.
