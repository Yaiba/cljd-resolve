#!/usr/bin/env bb
;; The vendored analyzer patch stays small, additive and tightly confined
;; (cljd-resolve-1sf.11).
;;
;;   bb test/vendor_test.clj
;;
;; helper/bin/analyzer.dart is a patched copy of ClojureDart's
;; resources/analyzer.dart, kept pristine alongside it as
;; vendor/analyzer.dart.upstream. Keeping the patch tiny IS the upgrade
;; strategy -- see vendor/README.md -- so this suite guards the *shape* of the
;; patch, not its behaviour. What it asserts:
;;
;;   pins      the versions vendor/README.md records are the versions
;;             helper/pubspec.lock and helper/pubspec.yaml actually carry
;;   size      the patch has not quietly doubled
;;   additive  nothing upstream emits has been dropped
;;   confined  the only new EDN keys are the four doc/location ones, the only
;;             new declarations are the five helpers that emit them, the
;;             patch pulls in no new imports, and local reloads use the
;;             explicit project root rather than the helper's cwd
;;
;; No Dart, no analyzer, no subprocess: two files read and compared. Runs in
;; the `core` CI tier.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def failures (atom 0))

(defn check [ok? label & [extra]]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label (if extra (str "-- " (pr-str extra)) "")))))

;; ------------------------------------------------------------------- inputs

(def repo-root
  (-> (io/file *file*)                            ; test/vendor_test.clj
      .getCanonicalFile .getParentFile .getParentFile))

(defn- at [& parts] (str (apply io/file repo-root parts)))

(def upstream-path (at "vendor" "analyzer.dart.upstream"))
(def patched-path  (at "helper" "bin" "analyzer.dart"))

(def upstream (slurp upstream-path))
(def patched  (slurp patched-path))

(defn- lines [s] (str/split-lines s))

;; ------------------------------------------------------- the recorded pins
;;
;; vendor/README.md carries a machine-readable `edn` block. Nothing else in
;; the repo restates a version, so the note cannot drift from the lock file
;; without this failing.

(println "\nthe pins vendor/README.md records match the files that carry them")

(def pins
  (let [doc (slurp (at "vendor" "README.md"))]
    (some-> (second (re-find #"(?s)```edn\n(.*?)\n```" doc)) edn/read-string)))

(check (map? pins) "vendor/README.md still has an edn pin block" pins)

(when (map? pins)
  (let [lock (slurp (at "helper" "pubspec.lock"))
        yaml (slurp (at "helper" "pubspec.yaml"))
        locked (second (re-find #"(?m)^  analyzer:\n(?:.*\n)*?    version: \"([^\"]+)\"" lock))
        sha    (second (re-find #"(?m)^#.*@\s*([0-9a-f]{7,40})" yaml))
        range* (second (re-find #"(?m)^\s*analyzer:\s*\"([^\"]+)\"" yaml))]
    (check (= (:analyzer pins) locked)
           "helper/pubspec.lock resolves analyzer to the pinned version"
           {:recorded (:analyzer pins) :locked locked})
    (check (= (:clojuredart pins) sha)
           "helper/pubspec.yaml's header names the pinned ClojureDart commit"
           {:recorded (:clojuredart pins) :header sha})
    ;; The patch is written against analyzer's first element model. The 7.x
    ;; line moves names and offsets onto fragments, which `addMeta`, `deSynth`
    ;; and `paramDocSource` would all have to be rewritten for. Widening this
    ;; bound is an upgrade project, not a dependency bump.
    (check (and range* (str/includes? range* "<7.0.0"))
           "helper/pubspec.yaml still holds analyzer below 7.0.0"
           {:constraint range*})))

;; -------------------------------------------------------------------- size
;;
;; Changed lines, counted as a multiset: a line present more often in one file
;; than the other is a changed line. That is near enough to `diff`'s count for
;; a ceiling (122 here against `diff -u`'s 119) and needs no subprocess, so
;; this check runs everywhere `bb` does.
;;
;; The thresholds are set from the tree, not from taste. Measured today:
;; 109 added + 13 removed = 122 changed lines, out of 596. The ceilings give
;; roughly a quarter more room -- enough that a legitimate emit-site tweak or
;; a re-merge against a reformatted upstream does not trip them, tight enough
;; that a patch drifting toward twice its size stops the build. If a real
;; upgrade needs more, raise the number deliberately and say why in the
;; commit; do not raise it to make a red suite green.

(def max-changed-lines 150)
(def max-removed-lines 20)

(defn- multiset-diff
  "[added removed]: lines of `b` not accounted for by `a`, and vice versa."
  [a b]
  (let [fa (frequencies (lines a))
        fb (frequencies (lines b))
        excess (fn [x y] (reduce + 0 (for [[l n] x] (max 0 (- n (get y l 0))))))]
    [(excess fb fa) (excess fa fb)]))

(println "\nthe patch is still small")

(let [[added removed] (multiset-diff upstream patched)
      changed (+ added removed)]
  (println (format "  ..    upstream %d lines, patched %d, changed %d (+%d/-%d)"
                   (count (lines upstream)) (count (lines patched))
                   changed added removed))
  (check (<= changed max-changed-lines)
         (format "at most %d changed lines" max-changed-lines)
         {:changed changed :ceiling max-changed-lines
          :hint "diff -u vendor/analyzer.dart.upstream helper/bin/analyzer.dart"})
  ;; The patch adds keys to maps upstream already builds. It removes lines
  ;; only to hoist a map literal into a local, so the removed side stays an
  ;; order of magnitude smaller than the added one. A patch that starts
  ;; *deleting* upstream is no longer a patch we can re-merge.
  (check (<= removed max-removed-lines)
         (format "at most %d removed lines -- the patch adds, it does not delete"
                 max-removed-lines)
         {:removed removed :ceiling max-removed-lines}))

;; ---------------------------------------------------------------- additive
;;
;; The EDN vocabulary: every `':foo'` / `":foo"` token either file mentions,
;; including ones upstream leaves commented out. Comparing vocabularies says
;; something the line count cannot -- that the patch never stops emitting
;; something upstream emitted -- and it survives reformatting, rewrapping and
;; hoisting, none of which change what goes out on the wire.

(defn- edn-vocab [s]
  (set (map second (re-seq #"['\"](:[a-z][a-z0-9-]*)['\"]" s))))

(def upstream-vocab (edn-vocab upstream))
(def patched-vocab  (edn-vocab patched))

;; The four keys the patch exists to add. docs/architecture.md, "New keys".
(def added-keys #{":doc" ":file" ":offset" ":length"})

(println "\nthe patch is additive")

(check (empty? (remove patched-vocab upstream-vocab))
       "every EDN key upstream emits is still emitted"
       {:dropped (sort (remove patched-vocab upstream-vocab))})

;; ---------------------------------------------------------------- confined

(println "\nthe patch is confined to metadata emission and local reload classification")

(check (= added-keys (set (remove upstream-vocab patched-vocab)))
       "the only new EDN keys are :doc :file :offset :length"
       {:new (sort (remove upstream-vocab patched-vocab))
        :expected (sort added-keys)})

(defn- imports [s]
  (filterv #(str/starts-with? % "import ") (lines s)))

(check (= (imports upstream) (imports patched))
       "the patch pulls in no new imports"
       {:added (vec (remove (set (imports upstream)) (imports patched)))
        :dropped (vec (remove (set (imports patched)) (imports upstream)))})

(check (str/includes? patched
                      "isWithin(pathContext.normalize(projectDirectoryPath),")
       "local reload classification uses the explicit project root")

(check (not (str/includes? patched
                           "isWithin(pathContext.normalize(pathContext.current),"))
       "local reload classification does not depend on the helper cwd")

;; Declarations at indent 0 or 2 -- top-level functions plus the visitor's
;; methods. Statement keywords are dropped so a call that happens to look like
;; a signature (`await doesLibraryExist(...)`) does not show up in a failure.
(def ^:private statement-words
  #{"await" "return" "if" "for" "while" "throw" "new" "final" "var" "const"})

(defn- declarations [s]
  (->> (lines s)
       (keep (fn [l]
               (when-not (str/starts-with? (str/triml l) "//")
                 (when-let [[_ head name*]
                            (re-find #"^ {0,2}([A-Za-z_][A-Za-z0-9_<>,?\[\]. ]*) ([A-Za-z_][A-Za-z0-9_]*)\(" l)]
                   (when-not (statement-words (first (str/split (str/trim head) #"\s+")))
                     name*)))))
       set))

;; The five helpers the patch adds, and nothing else. Anything new appearing
;; here means the patch has grown machinery of its own -- which is exactly the
;; drift that makes the next upstream merge expensive.
(def patch-helpers #{"S" "stripDoc" "deSynth" "addMeta" "paramDocSource"})

(let [up (declarations upstream)
      pa (declarations patched)]
  (check (empty? (remove pa up))
         "no upstream declaration was removed"
         {:missing (sort (remove pa up))})
  (check (= patch-helpers (set (remove up pa)))
         "the only new declarations are the five doc/location helpers"
         {:new (sort (remove up pa)) :expected (sort patch-helpers)}))

;; ----------------------------------------------------------------

(println)
(if (zero? @failures)
  (println "all checks passed")
  (println @failures "check(s) FAILED -- see vendor/README.md before changing a threshold"))
(System/exit (if (zero? @failures) 0 1))
