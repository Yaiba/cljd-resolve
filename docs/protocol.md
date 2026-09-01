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

## `complete`

The same cursor, asked the other way round: not what this symbol *is* but what
it could become.

```json
{"jsonrpc":"2.0","id":2,"method":"complete",
 "params":{"file":"/p/src/acme/main.cljd","line":16,"character":8}}
```

```json
{"jsonrpc":"2.0","id":2,"result":{
  "target":"named-args", "prefix":"sty",
  "lib":"package:flutter/material.dart", "type":"Text", "owner":"m/Text",
  "segment":{"start":{"line":16,"character":8},"end":{"line":16,"character":11}},
  "range":{"start":{"line":16,"character":7},"end":{"line":16,"character":11}},
  "items":[{"label":"style", "kind":"parameter", "detail":"TextStyle?",
            "container":"Text(style:)", "sort":"0style"}]}}
```

`target` says which set of names the prefix was matched against, and is what
tells a client which Dart shape it is looking at:

| `target` | the prefix | candidates |
|---|---|---|
| `library` | `m/Scaf` | public top-level names of the aliased library |
| `members` | `m.Colors/re` | **static** members of the type named before the `/` |
| `constructors` | `m/Text.ri` | the type's named constructors (keyed `Text.rich`) **and** its statics — ClojureDart spells `m/Text.rich` and `m/Icons.add` alike |
| `named-args` | `.sty` | named parameters of the enclosing constructor call — named or unnamed — then the class's instance members |
| `refers` | `pi` | the `:refer`red names in the `ns` form |

`items` is already filtered to `prefix` and sorted by `sort`, which carries the
group a candidate came from — an editor ranks on the label alone and has no way
to know a constructor's named parameter is the better answer for `.sty` than a
field that happens to share its name. `segment` is the span an accepted
candidate replaces; `range` is the whole token it sits in. An empty `items` is
an answer, not an error, and a cursor where no Dart name can go is `result:
null` — same as `resolve`.

`library` items carry no `detail`. The list is the whole export namespace —
1865 names for `package:flutter/material.dart` — and a signature apiece would
mean resolving every one of them to fill a column the user reads one row of.
`describe` supplies it for the row they land on.

## `describe`

The doc and full signature for **one** candidate, asked for as the user moves
down the list. Sending them with the list instead would put every docstring in
a library on the wire for a dropdown that shows one.

```json
{"jsonrpc":"2.0","id":3,"method":"describe",
 "params":{"file":"/p/src/acme/main.cljd", "lib":"package:flutter/material.dart",
           "type":"Text", "label":"style", "target":"named-args"}}
```

The result is a `resolve` result, minus `originRange` — `lib`, `type` and
`label` are what `complete` already sent, so this is a lookup into a class map
the analyzer still has cached, not a search.

Two optional params carry what a label alone cannot say. `member` is the key a
candidate lives under when that differs from the text the editor inserts — a
static offered as `Icons.add` is keyed `add`. `ctor` is the constructor a
`named-args` list was read off, so a parameter two constructors disagree about
is described against the right one: `ListView`'s `itemCount` is `required int`
on `.separated` and `int?` on `.builder`. Both come straight back from
`complete`.

Anything `complete` offers, `describe` resolves: both walk the class map with
the same preferences, so accepting a candidate cannot land the cursor on
something the hover then has nothing to say about.

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

Three commands: `lib` asks whether a library resolves, `elt` resolves one name
in it, and `names` answers the whole export namespace as `{"Text" :class ...}`
— the one question `elt` cannot be asked, since it needs a name you already
have. `names` lives in `helper/bin/names.dart` rather than in the vendored
`analyzer.dart`, which gains only an import and a `case`; see
[vendor/README.md](../vendor/README.md).

That protocol is one EDN **form** per command — not one line. `M()` writes newlines between map
entries, so responses span many lines; read them with a pushback reader, the way ClojureDart's
own `mk-live-analyzer-info` does.
