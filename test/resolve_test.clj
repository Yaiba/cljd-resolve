#!/usr/bin/env bb
;; End-to-end test for the resolve daemon (design.md §4 step 2): drives it as a
;; subprocess over its newline-delimited JSON-RPC protocol, against a real
;; Flutter SDK, and checks that every shape route B claims -- m/Text,
;; m.Colors/red, .style -- comes back with a doc and a jumpable location.
;;
;;   bb test/resolve_test.clj [flutter-project-dir]

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def project (or (first *command-line-args*) "/Users/gavin/code/vibe/cljd/hello"))
(def failures (atom 0))

(defn check [ok? label & [extra]]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label
                 (if extra
                   (let [s (pr-str extra)]
                     (str "-- " (subs s 0 (min 220 (count s)))))
                   "")))))

;; The buffer under test is written into the project (never saved to disk --
;; it is passed as `text`, which is also what proves unsaved buffers work).
(def file (str project "/src/acme/scratch.cljd"))

(def src
  (str/join "\n"
    ["(ns acme.scratch"                                          ; 0
     "  (:require [\"package:flutter/material.dart\" :as m]"      ; 1
     "            [\"dart:math\" :as math :refer [pi]]"           ; 2
     "            [cljd.flutter :as f]))"                        ; 3
     ""                                                          ; 4
     "(defn main []"                                             ; 5
     "  (f/run"                                                  ; 6
     "    (m/MaterialApp"                                        ; 7
     "      .title \"hi\")"                                      ; 8
     "    .home"                                                 ; 9
     "    (m/Scaffold)"                                          ; 10
     "    .body"                                                 ; 11
     "    (m/Text \"hi\" .style (m/TextStyle .color m.Colors/red))" ; 12
     "    (math/max 1 2)"                                        ; 13
     "    pi"                                                    ; 14
     "    (.substring s 1)"                                      ; 15
     "    m/NoSuchWidgetHere))"]))                               ; 16

;; ------------------------------------------------------------------ harness

(def proc
  (p/process ["bb" "-m" "cljd-resolve.daemon"]
             {:in :stream :out :stream :err :inherit
              :dir (.getCanonicalPath (io/file "."))}))

(def out (io/reader (:out proc)))
(def in  (io/writer (:in proc)))

(def next-id (atom 0))

(defn rpc [method params]
  (let [id (swap! next-id inc)]
    (.write in (str (json/generate-string {:jsonrpc "2.0" :id id
                                           :method method :params params})
                    "\n"))
    (.flush in)
    (let [line (.readLine out)
          res  (json/parse-string line true)]
      (check (= id (:id res)) (str method " reply carries the request id") res)
      (when-let [e (:error res)] (check false (str method " errored") e))
      (:result res))))

(defn at
  "Resolve at a 0-based LSP position in the unsaved buffer."
  [line character]
  (rpc "resolve" {:file file :text src :line line :character character}))

(defn located?
  "Has a real definition to jump to: a file:// URI and a range inside a file
   that actually contains the element's name there."
  [r name]
  (let [path (some-> (:defUri r) (str/replace #"^file://" ""))
        {:keys [start end]} (:defRange r)]
    (and path (.isFile (io/file path))
         start end
         (let [lines (str/split-lines (slurp path))
               line  (nth lines (:line start) nil)]
           (= name (some-> line (subs (:character start) (:character end))))))))

;; -------------------------------------------------------------------- tests

(println "\nping")
(check (:ok (rpc "ping" nil)) "daemon answers")

(println "\nm/MaterialApp  -- a class through a string alias")
(let [r (at 7 8)]
  (check (= "class" (:kind r)) "kind" r)
  (check (str/starts-with? (str (:doc r)) "An application that uses Material Design.")
         "class doc" (some-> (:doc r) (subs 0 (min 50 (count (:doc r))))))
  (check (= "class MaterialApp extends StatefulWidget" (:signature r)) "signature" r)
  (check (located? r "MaterialApp") "jumps to `MaterialApp` in app.dart" (:defRange r))
  (check (= {:start {:line 7 :character 5} :end {:line 7 :character 18}} (:originRange r))
         "originRange covers the symbol in the .cljd buffer" (:originRange r)))

(println "\n.title  -- named parameter of the enclosing constructor call")
(let [r (at 8 8)]
  (check (= "parameter" (:kind r)) "kind" r)
  (check (= "MaterialApp(title:)" (:container r)) "container names the constructor" r)
  (check (= "String? title" (:signature r)) "signature" r)
  (check (located? r "title") "jumps to the parameter" (:defRange r)))

(println "\n.home / .body  -- cljd.flutter's DSL, owner is the preceding widget")
(let [home (at 9 6)
      body (at 11 6)]
  (check (= "MaterialApp(home:)" (:container home)) ".home -> MaterialApp" home)
  (check (= "m/MaterialApp" (:owner home)) ".home owner is the preceding form" home)
  (check (= "Scaffold(body:)" (:container body)) ".body -> Scaffold" body)
  (check (string? (:doc body)) ".body has a doc"))

(println "\n.style / .color  -- nested constructor calls")
(let [style (at 12 19)
      color (at 12 38)]
  (check (= "Text(style:)" (:container style)) ".style -> Text" style)
  (check (str/starts-with? (str (:doc style)) "If non-null, the style to use")
         ".style inherits the field's doc" style)
  (check (= "TextStyle(color:)" (:container color)) ".color -> the nearer TextStyle" color))

(println "\nm.Colors/red  -- static member through a dotted alias")
(let [r (at 12 46)]
  (check (= "field" (:kind r)) "kind" r)
  (check (= "static const MaterialColor red" (:signature r)) "signature" r)
  (check (= "Colors" (:container r)) "container is the type" r)
  (check (located? r "red") "jumps to `red` in colors.dart" (:defRange r)))

(println "\nmath/max and pi  -- a top-level fn and a :refer")
(let [mx (at 13 6)
      p  (at 14 5)]
  (check (= "function" (:kind mx)) "math/max is a function" mx)
  (check (located? mx "max") "math/max is located")
  (check (= "dart:math" (:lib p)) ":refer resolves pi to dart:math" p)
  (check (string? (:doc p)) "pi has a doc"))

(println "\nwhat route B gives up on")
(check (nil? (at 15 6)) "(.substring s 1) -- needs type inference, returns null")
(check (nil? (at 16 6)) "m/NoSuchWidgetHere -- unknown element, returns null")
(check (nil? (at 5 4)) "`main` -- a plain Clojure symbol, returns null")
(check (nil? (at 4 0)) "an empty line, returns null")

(println "\ncaching")
(let [t0 (System/currentTimeMillis)
      _  (at 7 8)
      warm (- (System/currentTimeMillis) t0)]
  (check (< warm 500) (str "a repeat lookup is served from cache (" warm "ms)")))
(check (:ok (rpc "clearCache" nil)) "clearCache")
(check (some? (at 7 8)) "still resolves after the cache is dropped")

;; ----------------------------------------------------------------

(rpc "shutdown" nil)
(.close in)
@proc
(println)
(if (zero? @failures)
  (println "all checks passed")
  (println @failures "check(s) FAILED"))
(System/exit (if (zero? @failures) 0 1))
