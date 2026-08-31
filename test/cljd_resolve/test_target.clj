(ns cljd-resolve.test-target
  "What the integration suites run against, and the names they look for there.

   The analyzer and resolve suites used to hardcode one Flutter checkout on
   one machine (cljd-resolve-1sf.1). They now run against a *target*: a Dart
   project plus a vocabulary of the declarations to probe in it.

     fixture      test/fixture -- checked in, dependency-free, needs only a
                  Dart SDK. The default, so `bb test` works on a clean
                  checkout.
     flutter      a real Flutter/cljd project, named by CLJD_TEST_PROJECT or
                  as the suite's first argument. The optional tier: the same
                  assertions against package:flutter/material.dart.
     material_ui  the same assertions again against
                  package:material_ui/material_ui.dart, the standalone
                  package Flutter 3.47 split Material out into. Needs a
                  project that depends on it, so it is never implied --
                  ask for it with CLJD_TEST_TARGET.

   Selection, highest first: the suite's first CLI argument, then
   CLJD_TEST_PROJECT, then the fixture. Naming a project implies the flutter
   vocabulary unless CLJD_TEST_TARGET says otherwise.

   A target that cannot run (no `dart` on PATH, no project named for the
   flutter tier) is a skip, so `bb test` stays green on a machine with no Dart
   -- unless CLJD_TEST_STRICT is set, which turns every skip into a failure.
   CI sets it on the tiers it means to actually run."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private this-file *file*)

(defn repo-root []
  (-> (io/file this-file)              ; test/cljd_resolve/test_target.clj
      .getCanonicalFile
      .getParentFile .getParentFile .getParentFile
      .getPath))

(defn- under [& parts]
  (.getPath (apply io/file (repo-root) parts)))

;; ------------------------------------------------------------- vocabularies
;;
;; The suites assert on shapes, not on Flutter: a documented class whose
;; `this.x` named parameters inherit their field's doc, a static const reached
;; through a dotted alias, an enum, an abstract getter. Each target names the
;; declarations that have those shapes in it.

;; Every Material name below is shared by two libraries: Flutter 3.47 split
;; Material out of the SDK into a standalone `material_ui` package, and the
;; declarations came along unrenamed. Only the URI they are imported from
;; differs, which is the whole of what the second target exercises.
(def ^:private material
  {:text             "Text"
   :text-doc         "A run of text with a single style."
   :style-param      "style"
   :style-doc        "If non-null, the style to use"
   :method           "build"
   :panel            "Scaffold"
   :body             "body"
   :colors           "Colors"
   :color-field      "red"
   :color-signature  "static const MaterialColor red"
   :enum             "MainAxisAlignment"
   :enum-value       "center"
   :context          "BuildContext"
   :context-getter   "widget"
   :app              "MaterialApp"
   :app-doc          "Creates a MaterialApp."
   :app-signature    "const MaterialApp({"
   :title            "title"
   :title-signature  "String? title"
   :home             "home"
   :style-class      "TextStyle"
   :color-param      "color"
   :plain            "Center"
   :plain-signature  "class Center extends Align"})

(def vocabularies
  {"fixture"
   {:lib              "package:cljd_resolve_fixture/widgets.dart"
    :text             "Label"
    :text-doc         "A run of text with a single style."
    :style-param      "style"
    :style-doc        "If non-null, the style to use"
    :method           "build"
    :panel            "Panel"
    :body             "body"
    :colors           "Palette"
    :color-field      "red"
    :color-signature  "static const Tint red"
    :enum             "Fit"
    :enum-value       "center"
    :context          "Host"
    :context-getter   "widget"
    :app              "App"
    :app-doc          "Creates an App."
    :app-signature    "const App({"
    :title            "title"
    :title-signature  "String? title"
    :home             "home"
    :style-class      "LabelStyle"
    :color-param      "color"
    :plain            "Middle"
    :plain-signature  "class Middle extends Align"
    ;; Fixture-only shapes that exercise analyzer -> render preservation of
    ;; required named parameters and structured function types.
    :button           "Button"
    :child-param      "child"
    :callback-param   "onPressed"
    :named-callback-param "onBuild"
    :generic-callback-param "compare"}

   "flutter"     (assoc material :lib "package:flutter/material.dart")
   "material_ui" (assoc material :lib "package:material_ui/material_ui.dart")})

;; A name no target defines, so it must resolve to nil in all of them.
(def unknown-element "NoSuchThingHere")

;; ------------------------------------------------------------------ target

(defn- env [k] (let [v (System/getenv k)] (when-not (str/blank? v) v)))

(defn strict? [] (some? (env "CLJD_TEST_STRICT")))

(defn- on-path? [exe]
  (some (fn [d] (let [f (io/file d exe)] (and (.isFile f) (.canExecute f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn- named-project
  "The project the caller asked for, if any."
  [args]
  (or (first (remove str/blank? args)) (env "CLJD_TEST_PROJECT")))

(defn- target-name [args]
  (or (env "CLJD_TEST_TARGET")
      (if (named-project args) "flutter" "fixture")))

(defn- pub-get!
  "`dart pub get` in `dir` when it has no package config yet -- so a clean
   checkout does not need a setup step before `bb test`."
  [dir]
  (when-not (.isFile (io/file dir ".dart_tool" "package_config.json"))
    (println "  ... dart pub get in" (str dir))
    (let [{:keys [exit out err]} @(p/process ["dart" "pub" "get"]
                                             {:dir dir :out :string :err :string})]
      (when-not (zero? exit)
        (throw (ex-info (str "`dart pub get` failed in " dir "\n" out err) {:dir dir}))))))

(defn- library?
  "True when `lib` resolves in `project` according to the analyzer helper.

   This is deliberately a one-shot helper rather than a dependency on the
   production analyzer registry: target selection is test harness setup, and
   each suite will start the helper it actually exercises after this probe."
  [project lib]
  (let [helper (under "helper" "bin" "analyzer.dart")
        proc   (p/process ["dart" "run" helper project]
                          {:in :stream :out :stream :err :inherit})
        out    (java.io.PushbackReader. (io/reader (:out proc)))
        in     (io/writer (:in proc))]
    (try
      ;; Consume the startup banner before asking the first question.
      (edn/read {:eof nil} out)
      (.write in (str "lib " lib "\n"))
      (.flush in)
      (true? (edn/read {:eof nil} out))
      (finally
        (.close in)
        (.close out)
        (when (p/alive? proc) (p/destroy-tree proc))))))

(defn- unavailable!
  "Prints and exits for a target that cannot run."
  [suite why]
  (println (if (strict?) "FAIL" "SKIP") suite "--" why)
  (System/exit (if (strict?) 1 0)))

(defn resolve-target
  "=> {:project :vocab :name}, {:skip \"why\"} for a missing SDK or project,
   or {:error \"why\"} for a target that does not exist. Never exits."
  [args]
  (let [tname   (target-name args)
        vocab   (get vocabularies tname)
        project (or (named-project args)
                    (when (= "fixture" tname) (under "test" "fixture")))]
    (cond
      ;; a misconfigured target is a mistake, not a missing SDK -- never a skip
      (nil? vocab)
      {:error (str "unknown CLJD_TEST_TARGET " (pr-str tname) " -- expected one of "
                   (str/join ", " (sort (keys vocabularies))))}

      (nil? project)
      {:skip (str "the " tname " target needs a project: set CLJD_TEST_PROJECT"
                  " or pass one as the first argument")}

      (not (.isDirectory (io/file project)))
      {:skip (str "no such project directory: " project)}

      (not (on-path? "dart"))
      {:skip "no `dart` on PATH"}

      :else
      {:name tname
       :vocab vocab
       ;; the helper's analyzer refuses anything but an absolute normalized path
       :project (.getCanonicalPath (io/file project))})))

(defn begin!
  "Resolves the target for `suite`, preparing the Dart packages it needs.
   Exits -- 0 for a skip, 1 under CLJD_TEST_STRICT -- when it cannot run."
  [suite args]
  (let [{:keys [skip error project] tname :name :as t} (resolve-target args)]
    (when error
      (println "FAIL" suite "--" error)
      (System/exit 1))
    (when skip
      (unavailable! suite skip))
    (println suite "-- target" tname "at" project)
    (pub-get! (under "helper"))                  ; the analyzer package itself
    (when (= "fixture" tname) (pub-get! project))
    (when-not (library? project (:lib (:vocab t)))
      (unavailable! suite
                    (str "the target library " (:lib (:vocab t))
                         " does not resolve in " project)))
    t))

;; ------------------------------------------------------------------ cursors

(defn occurrences
  "Every `token` in `src`, as 0-based LSP spans."
  [src token]
  (let [lines (str/split-lines src)]
    (for [[row line] (map-indexed vector lines)
          col (loop [from 0, acc []]
                (if-let [i (str/index-of line token from)]
                  (recur (inc i) (conj acc i))
                  acc))]
      {:line row :character col :end (+ col (count token))})))

(defn locate
  "The `n`th (1-based, default 1) occurrence of `token` in `src`. Lets a suite
   point at a symbol by name instead of counting lines into a buffer."
  [src token & [n]]
  (or (nth (vec (occurrences src token)) (dec (or n 1)) nil)
      (throw (ex-info (str "no occurrence " (or n 1) " of " (pr-str token) " in the buffer")
                      {:token token}))))

;; ------------------------------------------------------------------ reports

(def failures (atom 0))

(defn check [ok? label & [extra]]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label
                 (if extra
                   (let [s (pr-str extra)] (str "-- " (subs s 0 (min 220 (count s)))))
                   "")))))

(defn finish!
  "Prints the tally and exits with it."
  []
  (println)
  (if (zero? @failures)
    (println "all checks passed")
    (println @failures "check(s) FAILED"))
  (System/exit (if (zero? @failures) 0 1)))

(defn prefix?
  "`s` starts with `p` -- reported with the head of `s`, since a doc mismatch
   is only readable next to what actually came back."
  [s p]
  (str/starts-with? (str s) p))

(defn head
  "The first `n` characters of `s`, for a failure message."
  ([s] (head s 60))
  ([s n] (some-> s str (subs 0 (min n (count (str s)))))))
