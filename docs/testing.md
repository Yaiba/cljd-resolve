# Tests

```bash
bb test                        # everything that does not need a Flutter SDK
bb test:parse                  # syntax only -- no Dart, instant
bb test:registry               # the analyzer registry's supervision rules, no Dart
bb test:extension              # the extension, no VSCode and no Dart, instant
bb test:vendor                 # the vendored analyzer patch's shape, no Dart, instant
bb test:analyzer    [project]  # the patched helper
bb test:resolve     [project]  # the daemon end to end
bb test:flutter     [project]  # the last two again, against a real Flutter SDK
bb test:material-ui [project]  # and again, against the standalone material_ui
```

Every suite prints one line per check and exits non-zero on failure.

## Targets

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

## CI

`.github/workflows/ci.yml` runs the four no-SDK suites and the fixture tier on every push. The
flutter tier is weekly and on demand, against a throwaway `flutter create` app with
`material_ui` added to it -- it runs both Material targets, is slow, and pins us to an SDK
release, and nothing in it is a regression the fixture tier would miss.

## The suites

**`test/analyzer_test.clj`** — 25-odd checks against the target library. Beyond presence, every
`:offset`/`:length` is spot-checked by seeking into the named file and confirming the characters
there really are the element's name, and the target's fattest class is read back whole through
`clojure.edn` to prove the escaping holds.

**`test/vendor_test.clj`** — the *shape* of the vendored patch rather than its behaviour: the
pins `vendor/README.md` records are the ones `helper/pubspec.yaml` and `pubspec.lock` carry, the
patch has not quietly grown, it drops nothing upstream emits, and it adds no EDN keys,
declarations or imports beyond the four doc/location ones. Two files read and compared — no Dart.

**`test/registry_test.clj`** — the analyzer registry with the process layer stubbed out: one
`dart run` per root under concurrency, a dead helper stopped before it is replaced, and a broken
helper backed off rather than recompiled on every hover. Plus which library URIs are allowed to
reach the helper at all, proved by what does and does not get written to it. No Dart.

**`test/parse_test.clj`** — the alias table, symbol classification, owner candidates, buffers
mid-edit (unbalanced, or holding a token that cannot be read), Dart rendering, and offset →
line/column. No subprocess, so it runs in a blink.

**`test/extension_test.js`** — plain node, no VSCode and no Dart. Covers the dartdoc-to-markdown
rendering, the daemon client (concurrency, cancellation, timeouts, a daemon that will not start),
and the protocol check — including that the real `bin/cljd-resolve` answers the version this
client was built against, which is the one place the two numbers meet. Then it loads
`extension.js` against a stubbed `vscode` module (`test/vscode_stub.js`) and a canned daemon
(`test/fake_daemon.js`) to check the providers, commands, warm-up and version skew are wired the
way VSCode will call them.

**`test/resolve_test.clj`** — drives `bin/cljd-resolve` as a subprocess over the wire protocol,
against a buffer that is never written to disk. Every `defRange` is confirmed by reading the
Dart file and checking the characters at that line and column really are the element's name.
