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
;; same assertions against package:flutter/material.dart instead, or set
;; CLJD_TEST_TARGET=material_ui for the standalone Material of Flutter 3.47.
;; See cljd-resolve.test-target.

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
    (concat
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
       (str "    m/" (:plain v))]
      (when-let [button (:button v)]
        [(str "    (m/" button)
         (str "      ." (:child-param v) " (m/" (:plain v) ")")
         (str "      ." (:callback-param v) " callback")
         (str "      ." (:named-callback-param v) " builder")
         (str "      ." (:generic-callback-param v) " comparator)")])
      [(str "    m/" (:colors v) "." (:color-field v))
       (str "    (m/" (:named-ctor v) " \"hi\" ." (:style-param v) " nil)")
       (str "    m/" t/unknown-element "))")])))

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
(let [r (rpc "ping" nil)]
  (check (:ok r) "daemon answers")
  ;; What a client checks itself against, so a daemon and an extension from
  ;; different checkouts say so instead of quietly disagreeing about the
  ;; shape of a result (cljd-resolve-1sf.10). test/extension_test.js is where
  ;; the two numbers are actually compared.
  (check (pos-int? (:protocol r)) "and names the wire protocol version" r))

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

(when-let [button (:button v)]
  (println (str "\nm/" button "  -- required and function-typed parameters"))
  (let [ctor (at-sym (str "m/" button))
        child (at-sym (str "." (:child-param v)))
        callback (at-sym (str "." (:callback-param v)))
        named-callback (at-sym (str "." (:named-callback-param v)))
        generic-callback (at-sym (str "." (:generic-callback-param v)))]
    (check (str/includes? (:signature ctor) "required Widget child")
           "constructor signature renders required" (:signature ctor))
    (check (= "required Widget child" (:signature child))
           "required named parameter hover" child)
    (check (= "void Function()? onPressed" (:signature callback))
           "void callback hover" callback)
    (check (= "void Function({required Host context, int index})? onBuild"
              (:signature named-callback))
           "callback hover renders named parameters" named-callback)
    (check (= "int Function<T>(T, T)? compare" (:signature generic-callback))
           "generic callback hover renders type formals" generic-callback)))

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

(println "\na library URI that would corrupt the helper's command")
;; A URI is a *string* in the ns form of a buffer nobody has saved, so it can
;; hold a space -- and the helper splits its commands on whitespace, so a space
;; would shift its tokens and ask something else entirely. Refused on this side
;; (cljd-resolve-1sf.10): the cursor resolves to nothing, and the helper is
;; left able to answer the next question.
(let [bad  (str/join "\n"
             ["(ns acme.corrupt"
              (str "  (:require [\"" (:lib v) " " (:plain v) "\" :as m]))")
              ""
              (str "(defn main [] m/" (:plain v) ")")])
      {:keys [line character]} (t/locate bad (str "m/" (:plain v)))
      r    (rpc "resolve" {:file file :text bad :line line :character (inc character)})]
  (check (nil? r) "the cursor resolves to nothing rather than being sent" r))
(check (some? (at-sym (str "m/" (:plain v)))) "and the helper still answers afterwards")

;; ------------------------------------------------------------------ completion
;;
;; The same shapes asked the other way round: not "what is this symbol" but
;; "what could it become". Each case gets its own buffer, one half-typed token
;; in it, because that is what a buffer being typed into actually looks like.

(println (str "\nm/" (:colors v) "." (:color-field v)
              "  -- a static through the slash-and-dot spelling"))
;; Written this way as often as `m.Colors/red` in real cljd code, and spelled
;; exactly like the named constructor `m/Text.rich` -- the class map keys the
;; two differently, so only one of them can be a straight lookup.
(let [r (at-sym (str "m/" (:colors v) "." (:color-field v)))]
  (check (= "field" (:kind r)) "kind" r)
  (check (= (:color-signature v) (:signature r)) "signature" r)
  (check (= (:colors v) (:container r)) "container is the type" r)
  (check (located? r (:color-field v)) "jumps to the static field" (:defRange r)))

(println (str "\n(m/" (:named-ctor v) " ...)  -- a named constructor heads a call"))
(let [r (at-sym (str "." (:style-param v)) 2)]     ; the 2nd is inside the m/…rich call
  (check (= "parameter" (:kind r)) "its named arguments resolve" r)
  (check (str/starts-with? (or (:container r) "") (:named-ctor v))
         "against that constructor, not the unnamed one" r)
  (check (= (str "m/" (:named-ctor v)) (:owner r)) "owned by the call head" r))

(println "\ncompletion")

(def completion-head
  [(str "(ns acme.completing")
   (str "  (:require [\"" (:lib v) "\" :as m]")
   "            [\"dart:math\" :as math :refer [pi]]"
   "            [cljd.flutter :as f]))"
   "(defn main []"
   "  (f/run"])

(defn completing
  "Completions for `line` appended to a real ns form, cursor at its end."
  [line]
  (rpc "complete" {:file file :text (str/join "\n" (conj completion-head line))
                   :line (count completion-head) :character (count line)}))

(defn labels [r] (mapv :label (:items r)))
(defn item-of [r label] (first (filter #(= label (:label %)) (:items r))))
(defn offers? [r label] (some? (item-of r label)))

;; `.sty` -- named parameters of the enclosing constructor call
(let [line (str "    (m/" (:text v) " \"hi\" ." (subs (:style-param v) 0 2))
      r    (completing line)
      it   (item-of r (:style-param v))]
  (check (= "named-args" (:target r)) "a `.name` completes named arguments" r)
  (check (= (subs (:style-param v) 0 2) (:prefix r)) "prefix is what precedes the cursor" r)
  (check (some? it) (str "offers ." (:style-param v)) (labels r))
  (check (= "parameter" (:kind it)) "as a parameter, not the field it forwards to" it)
  (check (= (str (:text v) "(" (:style-param v) ":)") (:container it))
         "container names the constructor it belongs to" it)
  (check (string? (:detail it)) "carries a one-line detail for the dropdown" it)
  (check (= 1 (count (filter #{(:style-param v)} (labels r))))
         "and offers it once -- the parameter shadows the field of the same name"
         (labels r))
  ;; The invariant that makes this safe: anything offered is something the
  ;; hover can then answer for, because both walk the class map the same way.
  (check (every? (fn [{:keys [label]}]
                   (some? (rpc "describe" {:file file :lib (:lib r) :type (:type r)
                                           :label label :target (:target r)})))
                 (:items r))
         "every candidate offered is one `describe` resolves" (labels r)))

;; The empty prefix -- `.` on its own, which is not a readable token at all
(let [r (completing (str "    (m/" (:text v) " \"hi\" ."))]
  (check (= "named-args" (:target r)) "a bare `.` still classifies" r)
  (check (= "" (:prefix r)) "with an empty prefix" r)
  (check (offers? r (:style-param v)) "and offers the whole parameter list" (labels r)))

;; `m.Colors/` -- statics through a dotted alias. The trailing `/` is the
;; shape rewrite-clj cannot read, so this is the one that proves the alias
;; table survives the repair.
(let [r  (completing (str "    m." (:colors v) "/"))
      it (item-of r (:color-field v))]
  (check (= "members" (:target r)) "a dotted alias completes members" r)
  (check (= (:colors v) (:type r)) "of the type named before the `/`" r)
  (check (some? it) (str "offers " (:color-field v)) (take 8 (labels r)))
  (check (str/starts-with? (or (:detail it) "") "static") "detail says static" it)
  ;; Offering an instance member here would be offering something that does
  ;; not compile: `m.Type/name` is static access and nothing else.
  (check (every? #(str/starts-with? (or (:detail %) "") "static") (:items r))
         "and offers nothing but statics"
         (remove #(str/starts-with? (or (:detail %) "") "static") (:items r))))

;; `m/Text.` -- named constructors
(let [r  (completing (str "    m/" (:text v) "."))
      it (item-of r (:named-ctor v))]
  (check (= "constructors" (:target r)) "a trailing dot completes named constructors" r)
  (check (= (:text v) (:type r)) "of the type before it" r)
  (check (some? it) (str "offers " (:named-ctor v)) (labels r))
  (check (= "constructor" (:kind it)) "kind" it)
  (check (not (offers? r (:text v)))
         "but not the unnamed one -- the cursor has committed to a dot" (labels r))
  (check (some? (rpc "describe" {:file file :lib (:lib r) :type (:type r)
                                 :label (:named-ctor v) :target (:target r)}))
         "and `describe` resolves it"))

;; A `:refer`red name, answered off the ns table
(let [r  (completing "    p")
      it (item-of r "pi")]
  (check (= "refers" (:target r)) "a bare symbol reports as :refers" r)
  (check (some? it) "offers the :refer'd pi" (labels r))
  (check (= "dart:math" (:container it)) "named against its library" r)
  ;; The one target whose candidates come from more than one library, so the
  ;; library is on the item and not on the result -- there is none that would
  ;; be true of the whole list. Without it the editor has no address to ask
  ;; `describe` with, and the row is offered with no documentation at all.
  (check (nil? (:lib r)) "the result names no library, because the list spans several" r)
  (check (= "dart:math" (:lib it)) "each item carries its own instead" it)
  (check (some? (rpc "describe" {:file file :lib (:lib it) :label "pi"
                                 :target (:target r)}))
         "and `describe` resolves it off that"))
(check (empty? (:items (completing "    ")))
  "an empty bare prefix offers nothing -- Clojure's own names are Calva's job")

;; The one shape still missing, and the reason it is (cljd-resolve-26b): the
;; helper answers `elt` for a name you already have, and cannot be asked what
;; names a library holds.
(let [r  (completing (str "    m/" (subs (:panel v) 0 3)))
      it (item-of r (:panel v))]
  (check (= "library" (:target r)) "an aliased prefix completes the library" r)
  (check (= (subs (:panel v) 0 3) (:prefix r)) "filtered to the prefix" r)
  (check (some? it) (str "offers " (:panel v)) (labels r))
  (check (= "class" (:kind it)) "as a class" it)
  (check (every? #(str/starts-with? (:label %) (subs (:panel v) 0 3)) (:items r))
         "and nothing that does not match" (labels r))
  ;; No detail on these. The list is the whole library -- 1866 names for
  ;; package:flutter/material.dart -- and a signature apiece would mean
  ;; resolving every one of them to fill a column the user reads one row of.
  (check (nil? (:detail it)) "carrying no signature, which the list has no room for" it)
  (let [d (rpc "describe" {:file file :lib (:lib r) :label (:panel v) :target "library"})]
    (check (= "class" (:kind d)) "`describe` fills that in for the highlighted row" d)
    (check (string? (:doc d)) "with the class doc" (head (:doc d) 40))
    (check (some? (:defUri d)) "and somewhere to jump to" d)))

;; A whole library really is the whole library, and its shape is the one the
;; hover can answer for.
(let [r (completing "    m/")]
  (check (= "" (:prefix r)) "an empty alias prefix is the whole export namespace" r)
  (check (> (count (:items r)) 10) "which is more than a handful" (count (:items r)))
  (check (some? (item-of r (:text v))) (str "including " (:text v)))
  (check (some? (item-of r (:enum v))) (str "and the enum " (:enum v)))
  (check (= "enum" (:kind (item-of r (:enum v)))) "kinded as an enum" (item-of r (:enum v)))
  (check (not-any? #(str/starts-with? (:label %) "_") (:items r))
         "and nothing private"))

;; A library nobody can resolve answers with nothing -- not, as the analyzer
;; would have it left alone, with the whole of `dart:core` under the alias.
(let [r (rpc "complete" {:file file
                         :text (str/join "\n" ["(ns acme.bogus"
                                               "  (:require [\"package:no_such_package/nope.dart\" :as m]))"
                                               "(defn main [] (m/Sca"])
                         :line 2 :character 20})]
  (check (= "library" (:target r)) "the shape still classifies" r)
  (check (empty? (:items r)) "but there is nothing behind the alias" (take 5 (labels r))))

;; Spans. The editor filters and replaces against these, so they must describe
;; the buffer as typed, with no trace of the sentinel that made it parse.
(let [line (str "    m." (:colors v) "/")
      r    (completing line)]
  (check (= {:line (count completion-head) :character (count line)}
            (get-in r [:segment :start]))
         "an empty segment is the empty span at the cursor" (:segment r))
  (check (= (get-in r [:segment :start]) (get-in r [:segment :end]))
         "start and end alike" (:segment r))
  (check (= 4 (get-in r [:range :start :character]))
         "the range covers the whole token" (:range r)))
(let [line (str "    (m/" (:text v) " \"hi\" ." (subs (:style-param v) 0 2))
      r    (completing line)]
  (check (= (- (count line) 2) (get-in r [:segment :start :character]))
         "a named argument's segment starts after the leading `.`" (:segment r))
  (check (= (count line) (get-in r [:segment :end :character]))
         "and runs to the end of the token" (:segment r)))

;; Nowhere a Dart name can go.
(check (nil? (completing (str "    (m/" (:text v) " \"hi")))
       "inside a string literal, no completion at all")
(check (nil? (completing "    ;; a comment"))
       "inside a comment")

;; `m/Type.name` reaches two different things, and real cljd code uses both:
;; `m/Text.rich` is a named constructor, `m/Icons.add` and `m/Colors.yellow`
;; are statics. Offering only the first answers nothing at all for a library
;; of icons or colours.
(let [r  (completing (str "    m/" (:colors v) "." (subs (:color-field v) 0 1)))
      it (item-of r (str (:colors v) "." (:color-field v)))]
  (check (= "constructors" (:target r)) "a static reached with a slash and a dot" r)
  (check (= (:colors v) (:type r)) "names its type" r)
  (check (some? it) (str "offers " (:colors v) "." (:color-field v)) (take 6 (labels r)))
  (check (= "field" (:kind it)) "as the field it is" it)
  ;; The label is what gets inserted; the key it lives under in the class map
  ;; is the bare name, and `describe` needs that one.
  (check (= (:color-field v) (:member it)) "carrying the key it is stored under" it)
  (let [d (rpc "describe" {:file file :lib (:lib r) :type (:type r)
                           :label (:label it) :member (:member it) :target (:target r)})]
    (check (= (:color-signature v) (:signature d))
           "and `describe` resolves it through that key" d)))

;; A named constructor heads a call as ordinarily as an unnamed one, and its
;; named arguments are its own.
(let [r  (completing (str "    (m/" (:named-ctor v) " ." (subs (:style-param v) 0 2)))
      it (item-of r (:style-param v))]
  (check (= "named-args" (:target r)) "a named-constructor call head owns its arguments" r)
  (check (= (:text v) (:type r)) "resolved against the class" r)
  (check (= (:named-ctor v) (:ctor r)) "and against that constructor in particular" r)
  (check (some? it) (str "offers ." (:style-param v)) (labels r))
  (check (str/starts-with? (or (:container it) "") (:named-ctor v))
         "attributed to the constructor that offered it" it))

;; Two constructors can share a parameter name and disagree about it, so the
;; constructor the list came from has to travel with the candidate.
(let [r (completing (str "    (m/" (:named-ctor v) " ." (subs (:style-param v) 0 2)))
      d (rpc "describe" {:file file :lib (:lib r) :type (:type r) :ctor (:ctor r)
                         :label (:style-param v) :target (:target r)})]
  (check (str/starts-with? (or (:container d) "") (:named-ctor v))
         "`describe` reads the parameter off the same constructor" d))

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
