#!/usr/bin/env bb
;; Regression test for the patched helper (design.md §4 step 1).
;;
;;   bb test/analyzer_test.clj [dart-project-dir]
;;
;; Drives bin/analyzer.dart over its stdin protocol and asserts that the
;; :doc / :file / :offset / :length we added actually show up -- and that the
;; whole EDN payload still reads back cleanly.
;;
;; Runs against the checked-in fixture by default (Dart SDK, nothing else).
;; Name a Flutter project -- an argument, or CLJD_TEST_PROJECT -- to run the
;; same assertions against package:flutter/material.dart instead, or set
;; CLJD_TEST_TARGET=material_ui for the standalone Material of Flutter 3.47.
;; See cljd-resolve.test-target.

(require '[babashka.process :as p]
         '[cljd-resolve.test-target :as t :refer [check head prefix?]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def target (t/begin! "analyzer_test" *command-line-args*))
(def project (:project target))
(def v (:vocab target))
(def lib (:lib v))

(def helper (str (.getCanonicalPath (io/file (t/repo-root) "helper")) "/bin/analyzer.dart"))

;; ---------------------------------------------------------------- protocol

(def helper-cwd
  ;; Deliberately neither the project nor one of its ancestors. Local-library
  ;; classification must follow the project argument, not the helper's cwd.
  (.toFile (java.nio.file.Files/createTempDirectory
             "cljd-resolve-analyzer-cwd-"
             (make-array java.nio.file.attribute.FileAttribute 0))))

(def proc
  (p/process ["dart" "run" helper project]
             {:dir helper-cwd :in :stream :out :stream :err :inherit}))

(def out (java.io.PushbackReader. (io/reader (:out proc))))
(def in  (io/writer (:in proc)))

(defn ask
  "Sends one command, reads back the single EDN form it answers with.
   Responses are newline-formatted, so they are forms -- not lines."
  [cmd]
  (.write in (str cmd "\n"))
  (.flush in)
  (edn/read {:eof ::eof} out))

(defn elt [name] (ask (str "elt " lib " " name)))

(def banner (edn/read out))                 ; {:dart "..."}
(check (string? (:dart banner)) "startup banner carries :dart version" banner)

;; ---------------------------------------------------------------- helpers

(defn located?
  "An element map that can be jumped to: absolute file + a char offset."
  [m]
  (and (string? (:file m))
       (str/ends-with? (:file m) ".dart")
       (.isFile (io/file (:file m)))
       (int? (:offset m))
       (nat-int? (:offset m))))

(defn spot-check-offset
  "Reads the file at :offset and confirms :length characters there really are
   the element's name -- proves the offsets are usable, not just present."
  [m expected-name]
  (let [{:keys [file offset length]} m]
    (and (located? m)
         (= expected-name (subs (slurp file) offset (+ offset (or length 0)))))))

;; ---------------------------------------------------------------- tests

(println "\nlib" lib)
(check (true? (ask (str "lib " lib))) "the target library resolves")

(println "\nelt" lib (:text v))
(let [text (elt (:text v))
      ctor (get text (:text v))
      style-param (->> (:parameters ctor)
                       (filter #(= (:style-param v) (str (:name %))))
                       first)
      style-field (get text (:style-param v))]
  (check (prefix? (:doc text) (:text-doc v))
         "class doc, comment markers stripped" (head (:doc text) 40))
  (when (= "fixture" (:name target))
    (check (true? (:local-lib text))
           "project library is local even when the helper cwd is elsewhere"))
  (check (not (str/includes? (:doc text) "///")) "no /// left in class doc")
  (check (spot-check-offset text (:text v)) "class :offset/:length point at the class name"
         (select-keys text [:file :offset :length]))
  (check (some? ctor) "constructor is present")
  (check (located? ctor) "constructor is located" (select-keys ctor [:file :offset :length]))
  (check (some? style-param) (str "constructor has a `" (:style-param v) "` named parameter"))
  (check (prefix? (:doc style-param) (:style-doc v))
         "`this.style` parameter inherits the field's doc" (head (:doc style-param) 40))
  (check (spot-check-offset style-param (:style-param v)) "parameter :offset points at its name")
  (check (prefix? (:doc style-field) (:style-doc v)) "the field itself has its doc")
  (check (spot-check-offset style-field (:style-param v)) "field :offset points at its name")
  (let [build (get text (:method v))]
    (check (located? build) (str "method `" (:method v) "` is located"))
    (check (spot-check-offset build (:method v)) "method :offset points at its name")))

(when-let [button-name (:button v)]
  (println "\nelt" lib button-name " (required / function-typed parameters)")
  (let [button (elt button-name)
        ctor (get button button-name)
        params (into {} (map (juxt (comp str :name) identity) (:parameters ctor)))
        child (get params (:child-param v))
        callback (get params (:callback-param v))
        named-callback (get params (:named-callback-param v))
        generic-callback (get params (:generic-callback-param v))
        named-type (:type named-callback)
        generic-type (:type generic-callback)]
    (check (true? (:required child)) "required survives on a named constructor parameter" child)
    (check (= :function (get-in callback [:type :kind]))
           "a void callback remains a structured function type" callback)
    (let [[context index] (:parameters named-type)]
      (check (and (= [:named :named] (mapv :kind (:parameters named-type)))
                  (true? (:required context))
                  (true? (:optional index)))
             "a callback preserves named, required, and optional parameter flags"
             named-type))
    (check (= "int" (get-in generic-type [:return-type :element-name]))
           "a generic callback preserves its return type" generic-type)
    (check (= ["T"] (mapv :element-name (:type-parameters generic-type)))
           "a generic callback preserves its type formals" generic-type)
    (check (= ["T" "T"]
              (mapv #(get-in % [:type :element-name]) (:parameters generic-type)))
           "a generic callback preserves its parameter types" generic-type)))

(println "\nelt" lib (:colors v) " (a static member, as in m.Colors/red)")
(let [colors (elt (:colors v))
      red (get colors (:color-field v))]
  (check (some? red) (str "static field `" (:color-field v) "` is present"))
  (check (string? (:doc red)) "the static field has a doc" (head (:doc red)))
  (check (spot-check-offset red (:color-field v)) "its :offset points at its name"))

(println "\nelt dart:math max  (top-level function)")
(let [m (ask "elt dart:math max")]
  (check (= :function (:kind m)) "kind is :function")
  (check (string? (:doc m)) "top-level function has a doc")
  (check (spot-check-offset m "max") "top-level function is located"))

(println "\nelt dart:math pi  (top-level const)")
(let [m (ask "elt dart:math pi")]
  (check (string? (:doc m)) "top-level variable has a doc")
  (check (spot-check-offset m "pi") "top-level variable is located"))

(println "\nelt" lib (:enum v) " (enum)")
(let [m (elt (:enum v))
      value (get m (:enum-value v))]
  (check (string? (:doc m)) "enum has a doc")
  (check (string? (:doc value)) (str "enum value `" (:enum-value v) "` has a doc"))
  (check (spot-check-offset value (:enum-value v)) "enum value is located"))

(println "\nelt" lib (:context v) " (abstract / getters)")
(let [m (elt (:context v))
      widget (get m (:context-getter v))]
  (check (string? (:doc widget)) "synthetic field from a getter still has a doc")
  (check (spot-check-offset widget (:context-getter v))
         "synthetic field resolves to its accessor's position"))

(println "\nelt" lib (:panel v) " (bulk EDN round-trip)")
(let [m (elt (:panel v))]
  (check (map? m) (str (:panel v) " parses"))
  (check (every? #(or (not (map? %)) (not (contains? % :doc)) (string? (:doc %)))
                 (tree-seq coll? seq m))
         "every :doc in the tree is a string (escaping holds)"))

(println "\nelt" lib t/unknown-element)
(check (nil? (elt t/unknown-element)) "unknown element -> nil")

;; ------------------------------------------------------------------- names
;;
;; The one question `elt` cannot answer -- it resolves a name you already have.
;; Lives in helper/bin/names.dart rather than in the vendored analyzer.dart,
;; so the patch stays an import and a `case`. See vendor/README.md.

(println "\nnames" lib)
(let [ns (ask (str "names " lib))]
  (check (map? ns) "a library answers with a map of name -> kind" (type ns))
  (check (every? string? (keys ns)) "keyed by name")
  (check (every? keyword? (vals ns)) "valued by kind" (take 3 (vals ns)))
  (check (= :class (get ns (:text v))) (str (:text v) " is a class") (get ns (:text v)))
  (check (= :class (get ns (:panel v))) (str (:panel v) " is a class"))
  (check (= :enum (get ns (:enum v))) (str (:enum v) " is an enum") (get ns (:enum v)))
  (check (contains? ns (:colors v)) (str (:colors v) " is exported"))
  ;; A setter is exported under `foo=`, which is not a name anything completes
  ;; to -- and its getter is already in the map under `foo`.
  (check (not-any? #(str/ends-with? % "=") (keys ns))
         "no `foo=` setter names" (filter #(str/ends-with? % "=") (keys ns)))
  (check (not-any? #(str/starts-with? % "_") (keys ns))
         "nothing private" (filter #(str/starts-with? % "_") (keys ns)))
  ;; Everything `elt` can be asked about has to be in here, or completion
  ;; offers names the hover cannot then answer for.
  (check (every? #(some? (elt %)) [(:text v) (:panel v) (:enum v)])
         "and every name in it resolves as an element"))

(println "\nnames dart:math")
(let [ns (ask "names dart:math")]
  (check (= :function (get ns "max")) "max is a function" (get ns "max"))
  (check (= :field (get ns "pi")) "pi is a field -- a top-level const" (get ns "pi")))

(println "\nnames -- a library that is not there")
(check (nil? (ask "names package:no_such_package/nope.dart"))
       "an unresolvable library -> nil")

;; ----------------------------------------------------------------

(.close in)
@proc
(.delete helper-cwd)
(t/finish!)
