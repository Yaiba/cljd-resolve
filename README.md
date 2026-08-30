# cljd-resolve

Hover docs and jump-to-definition for ClojureDart in VSCode. See [design.md](design.md)
for the full design; this README covers what is built.

## Status

| step | | |
|---|---|---|
| 1 | Patch `analyzer.dart` to emit docs + locations | **done** |
| 2 | Resolve daemon | not started |
| 3 | VSCode extension | not started |
| 4 | `cmd+enter` keybinding | not started |

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

## Tests

```bash
bb test/analyzer_test.clj [flutter-project-dir]   # defaults to ../cljd/hello
```

30 checks against a real Flutter SDK. Beyond presence, every `:offset`/`:length` is spot-checked
by seeking into the named file and confirming the characters there really are the element's
name, and the whole `Scaffold` payload is read back through `clojure.edn` to prove the escaping
holds.
