#!/usr/bin/env bb
;; End-to-end test for the resolve daemon (design.md §4 step 2): drives it as a
;; subprocess over its newline-delimited JSON-RPC protocol and checks that
;; every shape route B claims -- m/Text, m.Colors/red, .style -- comes back
;; with a doc and a jumpable location.
;;
;;   bb test/resolve_test.clj [dart-project-dir]
;;
;; Runs against the checked-in fixture by default (Dart SDK, nothing else).
;; Name a Flutter project -- an argument, or CLJD_TEST_PROJECT -- to run the
;; same assertions against package:flutter/material.dart instead. See
;; cljd-resolve.test-target.

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[cljd-resolve.test-target :as t :refer [check head prefix?]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def target (t/begin! "resolve_test" *command-line-args*))
(def project (:project target))
(def v (:vocab target))

;; The buffer under test is written into the project (never saved to disk --
;; it is passed as `text`, which is also what proves unsaved buffers work).
(def file (str project "/src/acme/scratch.cljd"))

;; Route B is purely syntactic, so the widget names here are the target's, not
;; Flutter's -- `f/run` and its `.named-arg` DSL resolve the same either way.
(def src
  (str/join "\n"
    ["(ns acme.scratch"
     (str "  (:require [\"" (:lib v) "\" :as m]")
     "            [\"dart:math\" :as math :refer [pi]]"
     "            [cljd.flutter :as f]))"
     ""
     "(defn main []"
     "  (f/run"
     (str "    (m/" (:app v))
     (str "      ." (:title v) " \"hi\")")
     (str "    ." (:home v))
     (str "    (m/" (:panel v) ")")
     (str "    ." (:body v))
     (str "    (m/" (:text v) " \"hi\" ." (:style-param v)
          " (m/" (:style-class v) " ." (:color-param v)
          " m." (:colors v) "/" (:color-field v) "))")
     "    (math/max 1 2)"
     "    pi"
     "    (.substring s 1)"
     (str "    m/" (:plain v))
     (str "    m/" t/unknown-element "))")]))

;; ------------------------------------------------------------------ harness

(def proc
  (p/process ["bb" "-m" "cljd-resolve.daemon"]
             {:in :stream :out :stream :err :inherit :dir (t/repo-root)}))

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

(defn spot
  "The span of the `n`th `token` in the buffer -- so the cursor lands on a
   symbol by name rather than on a line and column counted by hand."
  [token & [n]]
  (t/locate src token n))

(defn at-sym
  "Resolve with the cursor one character inside `token`."
  [token & [n]]
  (let [{:keys [line character]} (spot token n)]
    (at line (inc character))))

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

(println (str "\nm/" (:app v) "  -- heading a call, so the constructor"))
(let [r (at-sym (str "m/" (:app v)))
      s (spot (str "m/" (:app v)))]
  (check (= "constructor" (:kind r)) "kind -- what a constructor call hovers to in Dart" r)
  (check (prefix? (:doc r) (:app-doc v))
         "the constructor's doc, not the class's" (head (:doc r) 50))
  (check (prefix? (:signature r) (:app-signature v)) "signature" (head (:signature r) 40))
  (check (= (:app v) (:container r)) "container is the class" r)
  (check (located? r (:app v)) "jumps to the constructor" (:defRange r))
  (check (= {:start {:line (:line s) :character (:character s)}
             :end   {:line (:line s) :character (:end s)}}
            (:originRange r))
         "originRange covers the symbol in the .cljd buffer" (:originRange r)))

(println (str "\nm/" (:plain v) "  -- a bare reference, so still the class"))
(let [r (at-sym (str "m/" (:plain v)))]
  (check (= "class" (:kind r)) "kind -- not a call, so the type itself" r)
  (check (= (:plain-signature v) (:signature r)) "signature" r)
  (check (located? r (:plain v)) "jumps to the class declaration" (:defRange r)))

(println (str "\n." (:title v) "  -- named parameter of the enclosing constructor call"))
(let [r (at-sym (str "." (:title v)))]
  (check (= "parameter" (:kind r)) "kind" r)
  (check (= (str (:app v) "(" (:title v) ":)") (:container r)) "container names the constructor" r)
  (check (= (:title-signature v) (:signature r)) "signature" r)
  (check (located? r (:title v)) "jumps to the parameter" (:defRange r)))

(println (str "\n." (:home v) " / ." (:body v)
              "  -- cljd.flutter's DSL, owner is the preceding widget"))
(let [home (at-sym (str "." (:home v)))
      body (at-sym (str "." (:body v)))]
  (check (= (str (:app v) "(" (:home v) ":)") (:container home))
         (str "." (:home v) " -> " (:app v)) home)
  (check (= (str "m/" (:app v)) (:owner home))
         (str "." (:home v) " owner is the preceding form") home)
  (check (= (str (:panel v) "(" (:body v) ":)") (:container body))
         (str "." (:body v) " -> " (:panel v)) body)
  (check (string? (:doc body)) (str "." (:body v) " has a doc")))

(println (str "\n." (:style-param v) " / ." (:color-param v)
              "  -- nested constructor calls"))
(let [style (at-sym (str "." (:style-param v)))
      color (at-sym (str "." (:color-param v)))]
  (check (= (str (:text v) "(" (:style-param v) ":)") (:container style))
         (str "." (:style-param v) " -> " (:text v)) style)
  (check (prefix? (:doc style) (:style-doc v))
         (str "." (:style-param v) " inherits the field's doc") style)
  (check (= (str (:style-class v) "(" (:color-param v) ":)") (:container color))
         (str "." (:color-param v) " -> the nearer " (:style-class v)) color))

(println (str "\nm." (:colors v) "/" (:color-field v)
              "  -- static member through a dotted alias"))
(let [r (at-sym (str "m." (:colors v) "/" (:color-field v)))]
  (check (= "field" (:kind r)) "kind" r)
  (check (= (:color-signature v) (:signature r)) "signature" r)
  (check (= (:colors v) (:container r)) "container is the type" r)
  (check (located? r (:color-field v)) "jumps to the static field" (:defRange r)))

(println "\nmath/max and pi  -- a top-level fn and a :refer")
(let [mx (at-sym "math/max")
      p  (at-sym "pi" 2)]                    ; the 1st `pi` is the :refer itself
  (check (= "function" (:kind mx)) "math/max is a function" mx)
  (check (located? mx "max") "math/max is located")
  (check (= "dart:math" (:lib p)) ":refer resolves pi to dart:math" p)
  (check (string? (:doc p)) "pi has a doc"))

(println "\nwhat route B gives up on")
(check (nil? (at-sym ".substring")) "(.substring s 1) -- needs type inference, returns null")
(check (nil? (at-sym (str "m/" t/unknown-element)))
       "an unknown element, returns null")
(check (nil? (at-sym "main")) "`main` -- a plain Clojure symbol, returns null")
(check (nil? (at (.indexOf (str/split-lines src) "") 0)) "an empty line, returns null")

(println "\ncaching")
(let [t0 (System/currentTimeMillis)
      _  (at-sym (str "m/" (:app v)))
      warm (- (System/currentTimeMillis) t0)]
  (check (< warm 500) (str "a repeat lookup is served from cache (" warm "ms)")))
(check (:ok (rpc "clearCache" nil)) "clearCache")
(check (some? (at-sym (str "m/" (:app v)))) "still resolves after the cache is dropped")

;; ----------------------------------------------------------------

(rpc "shutdown" nil)
(.close in)
@proc
(t/finish!)
