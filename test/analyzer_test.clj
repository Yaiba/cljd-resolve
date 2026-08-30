#!/usr/bin/env bb
;; Regression test for the patched helper (design.md §4 step 1).
;;
;;   bb test/analyzer_test.clj [flutter-project-dir]
;;
;; Drives bin/analyzer.dart over its stdin protocol and asserts that the
;; :doc / :file / :offset / :length we added actually show up -- and that the
;; whole EDN payload still reads back cleanly.

(require '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def project (or (first *command-line-args*) "/Users/gavin/code/vibe/cljd/hello"))
(def helper (str (.getCanonicalPath (io/file "helper")) "/bin/analyzer.dart"))

(def failures (atom 0))

(defn check [ok? label & [extra]]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label (if extra (str "-- " (pr-str extra)) "")))))

;; ---------------------------------------------------------------- protocol

(def proc
  (p/process ["dart" "run" helper project]
             {:in :stream :out :stream :err :inherit}))

(def out (java.io.PushbackReader. (io/reader (:out proc))))
(def in  (io/writer (:in proc)))

(defn ask
  "Sends one command, reads back the single EDN form it answers with.
   Responses are newline-formatted, so they are forms -- not lines."
  [cmd]
  (.write in (str cmd "\n"))
  (.flush in)
  (edn/read {:eof ::eof} out))

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

(println "\nlib package:flutter/material.dart")
(check (true? (ask "lib package:flutter/material.dart")) "material.dart resolves")

(println "\nelt package:flutter/material.dart Text")
(let [text (ask "elt package:flutter/material.dart Text")
      ctor (get text "Text")
      style-param (->> (:parameters ctor) (filter #(= 'style (:name %))) first)
      style-field (get text "style")]
  (check (str/starts-with? (:doc text) "A run of text with a single style.")
         "class doc, comment markers stripped" (subs (str (:doc text)) 0 40))
  (check (not (str/includes? (:doc text) "///")) "no /// left in class doc")
  (check (spot-check-offset text "Text") "class :offset/:length point at `Text`"
         (select-keys text [:file :offset :length]))
  (check (some? ctor) "constructor is present")
  (check (located? ctor) "constructor is located" (select-keys ctor [:file :offset :length]))
  (check (some? style-param) "constructor has a `style` named parameter")
  (check (str/starts-with? (str (:doc style-param)) "If non-null, the style to use")
         "`this.style` parameter inherits the field's doc"
         (some-> (:doc style-param) (subs 0 (min 40 (count (:doc style-param))))))
  (check (spot-check-offset style-param "style") "parameter :offset points at `style`")
  (check (str/starts-with? (str (:doc style-field)) "If non-null, the style to use")
         "field `style` has its doc")
  (check (spot-check-offset style-field "style") "field :offset points at `style`")
  (let [build (get text "build")]
    (check (located? build) "method `build` is located")
    (check (spot-check-offset build "build") "method :offset points at `build`")))

(println "\nelt package:flutter/material.dart Colors  (m.Colors/red)")
(let [colors (ask "elt package:flutter/material.dart Colors")
      red (get colors "red")]
  (check (some? red) "static field `red` is present")
  (check (string? (:doc red)) "`red` has a doc"
         (some-> (:doc red) (subs 0 (min 60 (count (:doc red))))))
  (check (spot-check-offset red "red") "`red` :offset points at `red`"))

(println "\nelt dart:math max  (top-level function)")
(let [m (ask "elt dart:math max")]
  (check (= :function (:kind m)) "kind is :function")
  (check (string? (:doc m)) "top-level function has a doc")
  (check (spot-check-offset m "max") "top-level function is located"))

(println "\nelt dart:math pi  (top-level const)")
(let [m (ask "elt dart:math pi")]
  (check (string? (:doc m)) "top-level variable has a doc")
  (check (spot-check-offset m "pi") "top-level variable is located"))

(println "\nelt package:flutter/material.dart MainAxisAlignment  (enum)")
(let [m (ask "elt package:flutter/material.dart MainAxisAlignment")
      center (get m "center")]
  (check (string? (:doc m)) "enum has a doc")
  (check (string? (:doc center)) "enum value `center` has a doc")
  (check (spot-check-offset center "center") "enum value is located"))

(println "\nelt package:flutter/material.dart BuildContext  (abstract / getters)")
(let [m (ask "elt package:flutter/material.dart BuildContext")
      widget (get m "widget")]
  (check (string? (:doc widget)) "synthetic field from `get widget` still has a doc")
  (check (spot-check-offset widget "widget") "synthetic field resolves to its accessor's position"))

(println "\nelt package:flutter/material.dart Scaffold  (bulk EDN round-trip)")
(let [m (ask "elt package:flutter/material.dart Scaffold")]
  (check (map? m) "Scaffold parses")
  (check (every? #(or (not (map? %)) (not (contains? % :doc)) (string? (:doc %)))
                 (tree-seq coll? seq m))
         "every :doc in the tree is a string (escaping holds)"))

(println "\nelt package:flutter/material.dart NoSuchThingHere")
(check (nil? (ask "elt package:flutter/material.dart NoSuchThingHere")) "unknown element -> nil")

;; ----------------------------------------------------------------

(.close in)
@proc
(println)
(if (zero? @failures)
  (println "all checks passed")
  (println @failures "check(s) FAILED"))
(System/exit (if (zero? @failures) 0 1))
