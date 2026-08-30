#!/usr/bin/env bb
;; Tests for the syntactic half of the daemon (design.md §4 step 2) -- alias
;; table, cursor classification, owner candidates, Dart rendering, and the
;; offset -> line/column conversion. No Dart, no analyzer; runs in a blink.
;;
;;   bb test/parse_test.clj

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[cljd-resolve.parse :as parse]
         '[cljd-resolve.render :as render]
         '[cljd-resolve.daemon :as daemon]
         '[clojure.string :as str])

(def failures (atom 0))

(defn check [ok? label & [extra]]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label (if extra (str "-- " (pr-str extra)) "")))))

(defn check= [expected actual label]
  (check (= expected actual) label {:expected expected :actual actual}))

;; --------------------------------------------------------------- the buffer

(def src
  (str/join "\n"
    ["(ns acme.main"
     "  (:require [\"package:flutter/material.dart\" :as m]"
     "            [\"dart:math\" :as math :refer [pi]]"
     "            [\"package:flutter/services.dart\"]"
     "            [cljd.flutter :as f]))"
     ""
     "(defn main []"
     "  (f/run"
     "    (m/MaterialApp"
     "      .title \"hi\""
     "      .theme (m/ThemeData .primarySwatch m.Colors/pink))"
     "    .home"
     "    (m/Scaffold"
     "      .appBar (m/AppBar .title (m/Text \"hi\")))"
     "    .body"
     "    (m/Text \"hi\" .style (m/TextStyle .color m.Colors/red))"
     "    (.substring s 1)"
     "    (m/Text.rich x)))"]))

;; ------------------------------------------------------------------ ns-info

(println "\nns-info")
(let [{:keys [ns aliases refers]} (parse/ns-info src)]
  (check= 'acme.main ns "ns name")
  (check= {"m" "package:flutter/material.dart" "math" "dart:math"} aliases
    "only Dart libs get aliases -- cljd.flutter is dropped")
  (check= {"pi" "dart:math"} refers ":refer maps a bare name to its lib"))

(check (= {} (:aliases (parse/ns-info "(defn f [])")))
       "a buffer with no ns form yields an empty table")
(check (map? (parse/ns-info "(ns broken (:require [\"a.dart\" :as"))
       "an unterminated buffer does not throw")

;; ----------------------------------------------- buffers being typed into

(println "\nunbalanced buffers")
(let [half "(ns a (:require [\"package:flutter/material.dart\" :as m]))\n(m/Text \"hi\" .maxLines 2"]
  (check= {"m" "package:flutter/material.dart"} (:aliases (parse/ns-info half))
    "the ns table survives an unclosed form later in the buffer")
  (check= ".maxLines" (:symbol (parse/cursor-info half [2 14]))
    "a symbol in the unclosed form still resolves")
  (check= "m/Text" (first (:owners (parse/cursor-info half [2 14])))
    "and still finds its owner")
  (check= [[2 14] [2 23]] (:range (parse/cursor-info half [2 14]))
    "positions are unshifted -- the repair only appends"))
(check= "m/Text" (:symbol (parse/cursor-info "(m/Text \"unterminated" [1 2]))
  "an unterminated string is closed off too")
(check= "m/Text" (:symbol (parse/cursor-info "[{(m/Text" [1 4]))
  "several unclosed delimiters, in order")
(check (nil? (parse/cursor-info "(m/Text ;; ( unclosed paren in a comment\n" [1 20]))
  "a `(` inside a comment is not counted as open")

;; ----------------------------------------------------------------- classify

(println "\nclassify")
(check= {:kind :qualified :alias "m" :type "Text"}
  (parse/classify "m/Text") "m/Text")
(check= {:kind :qualified :alias "m" :type "Text" :member "Text.rich"}
  (parse/classify "m/Text.rich") "m/Text.rich -- named constructor")
(check= {:kind :qualified :alias "m" :type "Colors" :member "red"}
  (parse/classify "m.Colors/red") "m.Colors/red -- static member")
(check= {:kind :dot-name :member "style"} (parse/classify ".style") ".style")
(check= {:kind :dot-name :member "length"} (parse/classify ".-length") ".-length")
(check= {:kind :bare :name "pi"} (parse/classify "pi") "bare symbol")
(doseq [s ["m/" "/x" "." ".." ":style" "\"str\"" "" "a.b.c/d"]]
  (check (nil? (parse/classify s)) (str "rejects " (pr-str s)) (parse/classify s)))

;; -------------------------------------------------------------- cursor-info

(println "\ncursor-info")
(let [at (fn [r c] (parse/cursor-info src [r c]))]
  (check= "m/MaterialApp" (:symbol (at 9 8)) "symbol under the cursor")
  (check= [[9 6] [9 19]] (:range (at 9 8)) "range spans exactly the symbol")
  (check (= "m/MaterialApp" (:symbol (at 9 6))) "first char of the symbol counts")
  (check (nil? (at 10 5)) "leading whitespace resolves to nothing")
  (check (nil? (at 10 14)) "a string literal is not a symbol")
  (check= "m.Colors/pink" (:symbol (at 11 45)) "dotted-alias static member"))

;; ---------------------------------------------------------- owner-candidates

(println "\nowner-candidates -- which type a `.name` hangs off")
(let [owners (fn [r c] (:owners (parse/cursor-info src [r c])))]
  (check= "m/MaterialApp" (first (owners 10 7))
    ".title -- the enclosing call's head")
  (check= "m/ThemeData" (first (owners 11 30))
    ".primarySwatch -- the nearer, nested head wins")
  (check= "m/MaterialApp" (second (owners 12 5))
    ".home -- f/run's head does not resolve, the preceding widget does")
  (check= "m/Scaffold" (second (owners 15 5))
    ".body -- attaches to the widget written just before it")
  (check= "m/Text" (first (owners 16 18))
    ".style -- inside the Text call")
  (check (not-any? #(str/starts-with? % ".") (owners 15 5))
         "a `.name` is never itself a candidate" (owners 15 5))
  (check (nil? (owners 17 6))
         "(.substring s 1) -- a method call on a value is left alone"
         (owners 17 6)))

;; ------------------------------------------------------------------- render

(println "\nrender")
(check= "TextStyle?" (render/type-str {:element-name "TextStyle" :nullable true})
  "nullable type")
(check= "List<Widget>"
  (render/type-str {:element-name "List" :type-parameters [{:element-name "Widget"}]})
  "generic type")
(check= "String data, [int n], {TextStyle? style}"
  (render/params-str [{:name 'data :kind :positional :type {:element-name "String"}}
                      {:name 'n :kind :positional :optional true :type {:element-name "int"}}
                      {:name 'style :kind :named :optional true
                       :type {:element-name "TextStyle" :nullable true}}])
  "Dart parameter syntax: required, [optional], {named}")
(check= "class Text extends StatelessWidget"
  (render/signature {:kind :class :super {:element-name "StatelessWidget"}} "Text")
  "class signature")
(check= "const Text(String data)"
  (render/signature {:kind :constructor :const true
                     :parameters [{:name 'data :kind :positional
                                   :type {:element-name "String"}}]}
                    "Text")
  "constructor signature")
(check= "static const MaterialColor red"
  (render/signature {:kind :field :static true :const true
                     :type {:element-name "MaterialColor"}}
                    "red")
  "static const field")
(check= "Widget build(BuildContext context)"
  (render/signature {:kind :method :return-type {:element-name "Widget"}
                     :parameters [{:name 'context :kind :positional
                                   :type {:element-name "BuildContext"}}]}
                    "build")
  "method signature")

;; --------------------------------------------------------- offset -> position

(println "\noffset -> position")
(let [f (java.io.File/createTempFile "cljd-resolve" ".dart")
      text "class A {\n  final int x;\n}\n"]
  (spit f text)
  (let [pos #'daemon/offset->pos
        p   (fn [o] (@pos (.getPath f) o))]
    (check= {:line 0 :character 0} (p 0) "offset 0")
    (check= {:line 0 :character 6} (p 6) "mid first line")
    (check= {:line 1 :character 0} (p 10) "start of the second line")
    (check= {:line 1 :character 12} (p (str/index-of text "x")) "the `x` field name")
    (check= {:line 2 :character 0} (p 25) "start of the last line"))
  (.delete f))

;; ----------------------------------------------------------------

(println)
(if (zero? @failures)
  (println "all checks passed")
  (println @failures "check(s) FAILED"))
(System/exit (if (zero? @failures) 0 1))
