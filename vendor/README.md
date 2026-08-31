# Vendoring `analyzer.dart`

`vendor/analyzer.dart.upstream` is a pristine copy of ClojureDart's
`resources/analyzer.dart`. Nothing runs it. It exists so that
`helper/bin/analyzer.dart` — the copy we actually ship — can be diffed against
what it was forked from, and so that a future upgrade is a three-way merge
rather than an archaeology exercise.

The patch is deliberately tiny. It adds `:doc`, `:file`, `:offset` and
`:length` to the maps the helper already emits (README.md, *Step 1*), plus one
reload correction: local libraries are classified against the explicit Dart
project root rather than the helper process's cwd. **Keeping it that small is
the upgrade strategy.** Every line we add to it is a line someone has to
re-apply by hand the next time upstream moves, so the standing rule is: fix
things on the Clojure side unless only the helper has the information or
behavior needed for a correct fix. Two known upstream warts — the
whitespace-in-the-protocol issue and the `import '${lib}'` interpolation at
`analyzer.dart.upstream:437` — are deliberately *not* fixed here for exactly
that reason.

`bb test:vendor` enforces all of this. It is a fast, Dart-free check and runs
as part of `bb test`.

## The pins

```edn
;; Read by test/vendor_test.clj, which checks each value against the file that
;; actually carries it. Change these here and there in the same commit.
{:analyzer    "6.4.1"
 :clojuredart "01c41592"}
```

| pin | source of truth | |
|---|---|---|
| `analyzer 6.4.1` | `helper/pubspec.lock` | the resolved version the patch was written and tested against |
| `>=6.2.0 <7.0.0` | `helper/pubspec.yaml` | the constraint that keeps us on the first element model |
| ClojureDart `01c41592` | the header comment in `helper/pubspec.yaml` | the commit `analyzer.dart.upstream` was copied from |
| Dart `>=3.5.0 <4.0.0` | the `sdks:` stanza of `helper/pubspec.lock` | what resolved the lock |

## What breaks if they drift

**The analyzer major version is the real cliff.** Everything the patch touches
lives on analyzer's *first* element model: `Element`, `.documentationComment`,
`.nameOffset` / `.nameLength`, `.source`, `.isSynthetic`,
`PropertyInducingElement.getter`, `PropertyAccessorElement.variable`,
`FieldFormalParameterElement.field`,
`SuperFormalParameterElement.superConstructorParameter`, and
`ThrowingElementVisitor`. The 7.x line introduces a second model
(`Element2` / `Fragment`) in which a name and its offset belong to a *fragment*
rather than to the element. Crossing that boundary is not a patch refresh — it
is a rewrite of `addMeta`, `deSynth` and `paramDocSource`, and upstream
ClojureDart has to cross it first. That is why `helper/pubspec.yaml` says
`<7.0.0` and not `^6.2.0`; do not widen it casually.

**A minor analyzer bump is usually free**, because the patch reads only
long-stable accessors. `dart pub upgrade --directory=helper` inside the `6.x`
range, then `bb test:analyzer`, is the whole exercise. If a getter it uses is
deprecated, that shows up as a `dart analyze` warning against a known
baseline: upstream already carries 7 pre-existing warnings, and the patch adds
none. A new warning is a signal, not noise.

**ClojureDart drift is the quieter one.** `helper/` is drop-in compatible with
the stock helper in `.clojuredart/cache/<sha>/cljd_helper/bin/` only because
the patch is additive and `mk-live-analyzer-info` (`compiler.cljc:208`) lets
unknown scalar keys fall through. If upstream's `analyzer.dart` moves and this
copy does not, the two diverge silently: the daemon keeps working — it only
ever talks to *our* copy — but the drop-in claim in README.md quietly stops
being true, and the eventual merge gets harder every commit. Re-pinning is
cheap; letting it rot is not.

**The Dart SDK floor** matters least. The lock resolved under `>=3.5.0 <4.0.0`;
the patch uses no syntax newer than what upstream already uses.

## The shape of the patch

Measured against the current tree by `bb test:vendor`:

| | |
|---|---|
| upstream | 500 lines |
| patched | 596 lines |
| changed lines (added + removed) | 124 |
| removed lines | 14 |
| new top-level declarations | `S`, `stripDoc`, `deSynth`, `addMeta`, `paramDocSource` |
| new EDN keys | `:doc`, `:file`, `:offset`, `:length` |
| new imports | none |

Thirteen of the 14 removed lines are not deletions of behaviour. Twelve are
`return {` / `classData[…] = {` lines rewritten to hoist a map literal into a
local so `addMeta` has something to add to; the thirteenth is upstream's
commented-out `//':required'`, which the signature work restored. The remaining
replacement changes `pathContext.current` to `projectDirectoryPath` in the
local-library reload check, so classification follows the same root the
analysis collection uses. Nothing upstream emits is dropped — `bb test:vendor`
asserts that directly, by comparing the two files' EDN vocabularies rather than
by trusting the line count.

## Pulling a new upstream `analyzer.dart`

The procedure assumes the patch is still small. If it isn't, fix that first.

1. **Record where you are.** `bb test:vendor` on a clean tree, and keep its
   printed numbers. They are the "before" side of the upgrade.

2. **Fetch the new upstream file** from ClojureDart at the commit you intend to
   pin — `resources/analyzer.dart` — and save it as
   `vendor/analyzer.dart.upstream.new`. Do not overwrite the old one yet; the
   old one is the merge base.

3. **Three-way merge.** The old vendored file is the base, the new upstream is
   one side, our patched helper is the other:

   ```bash
   git merge-file helper/bin/analyzer.dart \
                  vendor/analyzer.dart.upstream \
                  vendor/analyzer.dart.upstream.new
   ```

   Conflicts land in `helper/bin/analyzer.dart` for you to resolve by hand.
   They will nearly always be at the emit sites, because that is the only
   place the patch touches. If a conflict is anywhere *else*, stop and ask why
   — that is a sign the patch has spread.

4. **Resolve toward upstream.** When upstream restructures a map literal, take
   upstream's version and re-hoist it into a local, then re-add the `addMeta`
   call. Do not carry forward our formatting; keep the file looking like
   upstream so the *next* merge is small too.

5. **Promote the new base.**

   ```bash
   mv vendor/analyzer.dart.upstream.new vendor/analyzer.dart.upstream
   ```

6. **Re-pin.** Update the `edn` block above, the header comment in
   `helper/pubspec.yaml`, and the `@ <sha>` in README.md's *Step 1*. Run
   `dart pub get --directory=helper` and commit the resulting
   `helper/pubspec.lock`.

7. **Check the shape.** `bb test:vendor`. If it fails on a threshold, read the
   failure before touching the threshold: a merge that grew the patch by 40
   lines is telling you something about step 4. Raising a ceiling is a
   deliberate act — do it in the same commit, with a note saying why.

8. **Check the behaviour.** `bb test:analyzer` (fixture; needs a Dart SDK),
   then `bb test:resolve`, then `dart analyze helper` against the 7-warning
   baseline. If you have a Flutter checkout, `bb test:flutter` and
   `bb test:material-ui` are the ones that exercise real doc inheritance
   through `this.style` / `super.style`.

9. **Re-check the drop-in claim** if `mk-live-analyzer-info` moved: README.md
   asserts that a cljd build can use our helper in place of the stock one.
   That claim rests on unknown keys falling through, not on the file being
   identical.
