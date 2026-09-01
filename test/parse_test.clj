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
  (check= "m.Colors/pink" (:symbol (at 11 45)) "dotted-alias static member")
  ;; heading a call means the symbol is a constructor invocation, so the
  ;; resolver hovers the constructor rather than the whole class
  (check (:head? (at 9 8)) "m/MaterialApp heads a call")
  (check (:head? (at 18 8)) "m/Text.rich heads a call")
  (check (not (:head? (at 11 45))) "m.Colors/pink is an argument, not a head")
  (check (not (:head? (at 12 5))) ".home is not a head"))

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

;; ---------------------------------------------------------------- completion
;;
;; The half-typed symbol. Non-empty prefixes already parse; the empty ones --
;; `m/`, `m.Colors/`, `m/Text.` -- are not readable symbols at all, and they
;; are the keystroke a dropdown is expected on.

(println "\ncompletion-info")

;; A buffer whose ns table is the one above, so a completion can be asked for
;; on any line appended to it.
(defn- typing
  "`completion-info` for `line` appended to a real ns form, with the cursor
   before 1-based column `col`."
  [line col]
  (parse/completion-info
    (str "(ns a (:require [\"package:flutter/material.dart\" :as m]\n"
         "                [\"dart:math\" :refer [pi]]))\n"
         line)
    [3 col]))

(defn- shape [line col ks]
  (select-keys (typing line col) ks))

(check= {:target :library :alias "m" :prefix "Scaf"}
  (shape "(m/Scaf" 8 [:target :alias :prefix])
  "m/Scaf -- top-level names of the aliased library")
(check= {:target :library :alias "m" :prefix ""}
  (shape "(m/" 4 [:target :alias :prefix])
  "m/ -- an empty prefix, which rewrite-clj cannot read as a symbol at all")
(check= {:target :members :alias "m" :type "Colors" :prefix "re"}
  (shape "(m.Colors/re" 13 [:target :alias :type :prefix])
  "m.Colors/re -- static members of the type named before the `/`")
(check= {:target :members :alias "m" :type "Colors" :prefix ""}
  (shape "(m.Colors/" 11 [:target :alias :type :prefix])
  "m.Colors/ -- empty again")
(check= {:target :constructors :alias "m" :type "Text" :prefix "Text.ri"}
  (shape "(m/Text.ri" 11 [:target :alias :type :prefix])
  "m/Text.ri -- named constructors, whose keys carry the type")
(check= {:target :constructors :alias "m" :type "Text" :prefix "Text."}
  (shape "(m/Text." 9 [:target :alias :type :prefix])
  "m/Text. -- the other unreadable one")
(check= {:target :named-args :prefix "sty"}
  (shape "(m/Text \"hi\" .sty" 18 [:target :prefix])
  ".sty -- named parameters of the enclosing call")
(check= "m/Text" (first (:owners (typing "(m/Text \"hi\" .sty" 18)))
  ".sty still finds its owner through the spliced sentinel")
(check= {:target :named-args :prefix ""}
  (shape "(m/Text \"hi\" ." 15 [:target :prefix])
  ". -- an empty named argument")
(check= {:target :refers :prefix "p"}
  (shape "(p" 3 [:target :prefix])
  "a bare symbol reports as :refers -- the caller decides what to do with it")

;; The cursor is not always at the end of the token it is in.
(check= {:target :library :prefix "Scaf" :symbol "m/Scaffold"}
  (shape "(m/Scaffold" 8 [:target :prefix :symbol])
  "m/Scaf|fold -- filters on what precedes the cursor, replaces the whole token")
(check= {:target :constructors :type "Text" :prefix "Te"}
  (shape "(m/Text.rich" 6 [:target :type :prefix])
  "m/Te|xt.rich -- the type is read off the clean token, not off the sentinel")

;; Spans. `:segment` is what an accepted candidate replaces; `:range` is the
;; token it sits in. Both must be the positions of the buffer as typed, with
;; no trace of the sentinel that made it parse.
(check= [[3 2] [3 8]] (:range (typing "(m/Scaf" 8))
  ":range spans exactly the typed token")
(check= [[3 4] [3 8]] (:segment (typing "(m/Scaf" 8))
  ":segment starts after the `/`, so the editor filters on `Scaf` not `m/Scaf`")
(check= [[3 4] [3 4]] (:segment (typing "(m/" 4))
  "an empty segment is the empty span at the cursor")
(check= [[3 15] [3 18]] (:segment (typing "(m/Text \"hi\" .sty" 18))
  ":segment starts after the leading `.`")
(check= [[3 4] [3 12]] (:segment (typing "(m/Scaffold" 8))
  ":segment runs to the end of the token, not to the cursor")

;; Where no Dart name can go.
(check (nil? (typing "(m/Text \"hi" 11)) "inside a string literal")
(check (nil? (typing "(m/Text ;; a comment" 15)) "inside a comment")
(check (nil? (typing "(m/Text" 3)) "m|/Text -- the cursor is before the segment")
(check= {:target :refers :prefix ""} (shape "  " 2 [:target :prefix])
  "whitespace reports an empty bare prefix, not nil -- the policy lives downstream")
(check (nil? (parse/completion-info "(m/Scaf" [9 2])) "a row past the end of the buffer")
(check (some? (typing "(m/Scaf" 99))
  "a column past the end of the line clamps to it rather than sliding down a row")

;; The sentinel is an implementation detail and must not appear in any string
;; that comes back.
(doseq [[label line col] [["m/Scaf" "(m/Scaf" 8] ["m/" "(m/" 4]
                          ["m.Colors/" "(m.Colors/" 11] ["m/Text." "(m/Text." 9]
                          [".sty" "(m/Text \"hi\" .sty" 18]]]
  (let [info (typing line col)
        strs (filter string? (concat (vals info) (:owners info)))]
    (check (not-any? #(str/includes? % "X") strs)
           (str "no sentinel leaks out of " label) strs)))

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
(check= "{required Widget child, TextStyle? style}"
  (render/params-str [{:name 'child :kind :named :required true
                       :type {:element-name "Widget"}}
                      {:name 'style :kind :named :optional true
                       :type {:element-name "TextStyle" :nullable true}}])
  "`required` on the named parameters that have it, and only those")
(check= "String data"
  (render/param-str {:name 'data :kind :positional :required true
                     :type {:element-name "String"}})
  "a required positional stays bare -- `required` is named-only in Dart")
(check= "required Widget child"
  (render/signature {:kind :parameter :required true
                     :type {:element-name "Widget"}}
                    "child")
  "`required` survives the standalone named-parameter hover")

;; function types: the analyzer sends the whole structure, and Dart spells it
;; out -- `Function` alone would not compile, and `Function<T>` is plain wrong.

(def ^:private void-t {:element-name "void"})
(defn- fn-t [m]
  (merge {:kind :function :element-name "Function" :qname 'dc.Function
          :return-type void-t :parameters []}
         m))

(check= "void Function()" (render/type-str (fn-t {})) "bare function type")
(check= "void Function()?"
  (render/type-str (fn-t {:nullable true}))
  "nullable function type")
(check= "void Function(int)"
  (render/type-str (fn-t {:parameters [{:kind :positional
                                        :type {:element-name "int"}}]}))
  "a function type's parameters may be unnamed")
(check= "void Function({required BuildContext context, int index})"
  (render/type-str
    (fn-t {:parameters [{:name 'context :kind :named :required true
                         :type {:element-name "BuildContext"}}
                        {:name 'index :kind :named :optional true
                         :type {:element-name "int"}}]}))
  "named parameters inside a function type, `required` included")
(check= "T Function<T>(T value)"
  (render/type-str
    (fn-t {:return-type {:element-name "T"}
           :type-parameters [{:element-name "T"}]
           :parameters [{:name 'value :kind :positional
                         :type {:element-name "T"}}]}))
  "a generic function type's type parameters go before the `(`, not after it")
(check= "void Function(void Function(int i)? onTap)"
  (render/type-str
    (fn-t {:parameters
           [{:name 'onTap :kind :positional
             :type (fn-t {:nullable true
                          :parameters [{:name 'i :kind :positional
                                        :type {:element-name "int"}}]})}]}))
  "nested callbacks")
(check= "const Foo({required Widget child, void Function()? onTap})"
  (render/signature {:kind :constructor :const true
                     :parameters [{:name 'child :kind :named :required true
                                   :type {:element-name "Widget"}}
                                  {:name 'onTap :kind :named :optional true
                                   :type (fn-t {:nullable true})}]}
                    "Foo")
  "a constructor with a required child and a callback")
(check= "class Text extends StatelessWidget"
  (render/signature {:kind :class :super {:element-name "StatelessWidget"}} "Text")
  "class signature")
(check= "const Text(String data)"
  (render/signature {:kind :constructor :const true
                     :parameters [{:name 'data :kind :positional
                                   :type {:element-name "String"}}]}
                    "Text")
  "constructor signature")
(check= (str/join "\n" ["const Text("
                        "  String data, {"
                        "  Key? key,"
                        "  TextStyle? style,"
                        "  TextHeightBehavior? textHeightBehavior,"
                        "})"])
  (render/signature {:kind :constructor :const true
                     :parameters [{:name 'data :kind :positional
                                   :type {:element-name "String"}}
                                  {:name 'key :kind :named
                                   :type {:element-name "Key" :nullable true}}
                                  {:name 'style :kind :named
                                   :type {:element-name "TextStyle" :nullable true}}
                                  {:name 'textHeightBehavior :kind :named
                                   :type {:element-name "TextHeightBehavior" :nullable true}}]}
                    "Text")
  "past 72 chars the parameters break one per line, dart-format style")
(check= (str/join "\n" ["const MaterialApp({"
                        "  Key? key,"
                        "  GlobalKey<NavigatorState>? navigatorKey,"
                        "  RouteInformationProvider? routeInformationProvider,"
                        "})"])
  (render/signature {:kind :constructor :const true
                     :parameters [{:name 'key :kind :named
                                   :type {:element-name "Key" :nullable true}}
                                  {:name 'navigatorKey :kind :named
                                   :type {:element-name "GlobalKey" :nullable true
                                          :type-parameters [{:element-name "NavigatorState"}]}}
                                  {:name 'routeInformationProvider :kind :named
                                   :type {:element-name "RouteInformationProvider" :nullable true}}]}
                    "MaterialApp")
  "with no positionals the `{` rides the opening paren")
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
