# The daemon's wire protocol

`bin/cljd-resolve` speaks newline-delimited JSON-RPC on stdio: one JSON object per line in,
one per line out. Line-delimited rather than LSP's `Content-Length` framing — the only client
is our own extension, and this is drivable from a shell. Positions on the wire are LSP's:
**0-based** `line` and `character`.

```bash
bin/cljd-resolve
```

## `resolve`

Cursor in a `.cljd` buffer → Dart element.

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
to find the Dart project.

## Other methods

| method | |
|---|---|
| `warmUp` | starts the analyzer for `file`'s project, answers `{ok, libs}` — the libraries the buffer's `ns` form requires |
| `warmUpLib` | resolves one of those libraries |
| `ping` | `{"ok":true,"protocol":1}` |
| `clearCache` | drops the in-memory `lib`/`elt` cache, after the project's own Dart changes |
| `shutdown` | |

A client drives `warmUpLib` one library at a time rather than as one batch — see
[warm-up](architecture.md#warm-up) for why.

## Versioning

`protocol` is the version of this wire surface, bumped when a change to it would be *misread* by
a peer built against the previous one — a renamed result key, a param whose meaning moves; a
method added on the end is not such a change. The extension asks once per daemon it starts and
says so in its log if the answer is not the number it was built against.

Deliberately a plain number and not a capability negotiation: there is one client, it lives in
this repository, and it is installed from this checkout, so the two normally move together and
the one bit worth having is the one that says when they did not.

## Library URIs the daemon will not send

An alias's library URI is a **string** in the `ns` form of a buffer nobody has saved, so it can
hold anything — and the analyzer helper takes its commands whitespace-delimited (`line.split(" ")`)
and asks its question by interpolating the URI straight into `import '${lib}' as libalias;`. A
space shifts its tokens and silently asks something else; a quote or a newline closes that import
and injects Dart.

Both of those are upstream ClojureDart code, identical in `vendor/analyzer.dart.upstream` —
patching them there would widen the vendored diff and worsen every upgrade — so the check is on
this side of the pipe, in `analyzer.clj`. Only a well-formed `dart:` or `package:` URI is sent;
anything else resolves to nothing, which is what an unresolvable cursor does anyway. An allow-list
of the two schemes rather than a blocklist of the characters that hurt: they are the only things
an alias may name that the analyzer can answer for.

## The analyzer helper's own protocol

One level down, the daemon talks to the patched `helper/bin/analyzer.dart` in EDN:

```bash
dart pub get --directory=helper

printf 'lib package:flutter/material.dart\nelt package:flutter/material.dart Text\n' \
  | dart run helper/bin/analyzer.dart /path/to/a/flutter/project
```

That protocol is one EDN **form** per command — not one line. `M()` writes newlines between map
entries, so responses span many lines; read them with a pushback reader, the way ClojureDart's
own `mk-live-analyzer-info` does.
