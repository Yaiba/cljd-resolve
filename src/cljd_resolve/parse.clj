(ns cljd-resolve.parse
  "Everything the daemon reads out of a `.cljd` buffer with rewrite-clj:
   the ns alias table, the token under the cursor, and -- for a `.named-arg`
   -- the symbols that might name the Dart type it belongs to.

   Purely syntactic. Nothing here talks to the analyzer; `classify` and
   `owner-candidates` hand back *candidates*, and cljd-resolve.resolve decides
   which one actually resolves."
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------- ns parsing

(defn- balance
  "The delimiters `text` is missing, innermost first, or nil when it is already
   balanced. Skips strings, character literals and line comments, so a `(` in a
   docstring does not count.

   A buffer being typed into is unbalanced most of the time, and rewrite-clj
   refuses to parse it -- but everything is appended at the end, so the
   positions of what came before are untouched."
  [^String text]
  (let [n (count text)]
    (loop [i 0, stack (), in-string? false]
      (if (>= i n)
        (seq (cond->> stack in-string? (cons \")))
        (let [c (.charAt text i)]
          (case c
            \; (recur (let [j (.indexOf text "\n" i)] (if (neg? j) n (inc j))) stack false)
            \\ (recur (+ i 2) stack false)          ; \c character literal
            \" (let [end (loop [j (inc i)]
                           (cond (>= j n) nil
                                 (= \\ (.charAt text j)) (recur (+ j 2))
                                 (= \" (.charAt text j)) (inc j)
                                 :else (recur (inc j))))]
                 (if end (recur end stack false) (recur n stack true)))
            (\( \[ \{) (recur (inc i) (conj stack (case c \( \) \[ \] \{ \})) false)
            (\) \] \}) (recur (inc i) (if (= c (first stack)) (rest stack) stack) false)
            (recur (inc i) stack false)))))))

(defn- zip-of
  "A position-tracking zipper over `text`, closing it off first if it does not
   parse -- so a hover works halfway through typing a form."
  [text]
  (letfn [(zip [s] (try (z/of-string s {:track-position? true}) (catch Exception _ nil)))]
    (or (zip text)
        (when-let [cs (balance text)] (zip (str text (apply str cs)))))))

(defn- top-level [text]
  (when-let [zloc (zip-of text)]
    (take-while some? (iterate z/right zloc))))

(defn- head-string
  "The first child of a list node, when it is a token."
  [zloc]
  (when (and zloc (= :list (z/tag zloc)))
    (let [h (z/down zloc)]
      (when (and h (= :token (z/tag h))) (z/string h)))))

(defn- ns-form
  "The `(ns ...)` form of the buffer, read as data. nil if absent or unreadable
   (a `.cljd` ns can hold reader syntax `sexpr` chokes on)."
  [text]
  (when-let [zloc (first (filter #(= "ns" (head-string %)) (top-level text)))]
    (try (z/sexpr zloc) (catch Exception _ nil))))

(defn- libspec-info [spec]
  (cond
    (string? spec) nil                                   ; ["package:x.dart"] -- no alias
    (vector? spec)
    (let [[lib & opts] spec
          ;; a half-typed `["a.dart" :as` leaves a dangling key -- drop it
          {:keys [as refer]} (into {} (comp (partition-all 2)
                                            (filter #(= 2 (count %)))
                                            (map vec))
                                   opts)]
      (when (string? lib)                                ; a Dart lib, not a cljd ns
        {:aliases (when as {(str as) lib})
         :refers  (when (coll? refer)
                    (into {} (map (fn [r] [(str r) lib])) refer))}))
    :else nil))

(defn ns-info
  "=> {:ns acme.main
       :aliases {\"m\" \"package:flutter/material.dart\"}
       :refers  {\"pi\" \"dart:math\"}}

   Only Dart libraries land in the tables -- a `[cljd.flutter :as f]` require
   has nothing behind it the analyzer can answer for."
  [text]
  (let [form  (ns-form text)
        specs (->> (rest form)
                   (filter seq?)
                   (filter #(#{:require :require-macros} (first %)))
                   (mapcat rest))
        infos (keep libspec-info specs)]
    {:ns      (second form)
     :aliases (into {} (mapcat :aliases) infos)
     :refers  (into {} (mapcat :refers) infos)}))

;; ------------------------------------------------------------------- cursor

(defn token-at
  "The token zipper location at 1-based `[row col]`, or nil."
  [text pos]
  (when-let [zloc (zip-of text)]
    (let [found (try (z/find-last-by-pos zloc pos) (catch Exception _ nil))]
      (when (and found (= :token (z/tag found))) found))))

(def ^:private symbol-ish
  ;; a bare symbol -- not a string, keyword, number, or reader form
  #"[^\s\"';:#@^`~()\[\]{}\d][^\s\"';()\[\]{}]*")

(defn- symbol-token [zloc]
  (when (and zloc (= :token (z/tag zloc)))
    (let [s (z/string zloc)]
      (when (re-matches symbol-ish s) s))))

(defn classify
  "Sorts a symbol string into the three shapes route B handles (design.md §3).

     m/Text        => {:kind :qualified :alias \"m\" :type \"Text\"}
     m/Text.rich   => {:kind :qualified :alias \"m\" :type \"Text\"  :member \"Text.rich\"}
     m.Colors/red  => {:kind :qualified :alias \"m\" :type \"Colors\" :member \"red\"}
     .style        => {:kind :dot-name  :member \"style\"}
     .-length      => {:kind :dot-name  :member \"length\"}
     pi            => {:kind :bare      :name \"pi\"}"
  [s]
  (when (and s (seq s) (re-matches symbol-ish s))
    (cond
      (str/starts-with? s ".-")
      (when (> (count s) 2) {:kind :dot-name :member (subs s 2)})

      (str/starts-with? s ".")
      (let [m (subs s 1)]
        (when (and (seq m) (not (str/includes? m ".")) (not (str/includes? m "/")))
          {:kind :dot-name :member m}))

      (str/includes? s "/")
      (let [[prefix nm] (str/split s #"/" 2)
            [alias & path] (str/split prefix #"\." -1)]
        (when (and (seq alias) (seq nm) (< (count path) 2))
          (if (seq path)
            ;; m.Colors/red -- static member of a type reached through the alias
            {:kind :qualified :alias alias :type (first path) :member nm}
            ;; m/Text or m/Text.rich -- a type, or one of its named constructors
            (let [[t & more] (str/split nm #"\." -1)]
              (when (seq t)
                (cond-> {:kind :qualified :alias alias :type t}
                  (seq more) (assoc :member nm)))))))

      :else {:kind :bare :name s})))

;; ---------------------------------------------------------- owner candidates

(defn- left-siblings
  "Siblings before `zloc`, nearest first. `z/left` already skips whitespace."
  [zloc]
  (rest (take-while some? (iterate z/left zloc))))

(defn- candidate-of
  "The symbol a sibling contributes: a call's head, or the symbol itself."
  [zloc]
  (let [s (or (head-string zloc) (symbol-token zloc))]
    (when (and s (re-matches symbol-ish s) (not (str/starts-with? s ".")))
      s)))

(defn- head-candidate [zloc]
  (let [s (head-string zloc)]
    (when (and s (re-matches symbol-ish s) (not (str/starts-with? s ".")))
      s)))

(defn owner-candidates
  "For a `.named-arg` token, the symbols that might name the Dart type it
   belongs to -- nearest first.

   The enclosing call's head covers plain constructor calls,
   `(m/Text \"hi\" .style ...)`. The preceding siblings cover cljd.flutter's
   `f/widget` / `f/run` DSL, where `.home` attaches to the widget written just
   before it rather than to the form it sits in. Purely positional, so a
   candidate that names nothing in Dart simply fails to resolve.

   nil when the token is its list's head -- that is `(.substring s 1)`, a
   method call on a value, which needs type inference route B does not do."
  [zloc]
  (when (and zloc (seq (left-siblings zloc)))
    (loop [z zloc, depth 0, acc []]
      (if (or (nil? z) (>= depth 3))
        (vec (distinct (remove nil? acc)))
        (recur (z/up z) (inc depth)
               (-> acc
                   (conj (head-candidate (z/up z)))
                   (into (keep candidate-of (left-siblings z)))))))))

(defn cursor-info
  "Everything the resolver needs about 1-based `[row col]` in `text`."
  [text pos]
  (when-let [zloc (token-at text pos)]
    (when-let [s (symbol-token zloc)]
      (when-let [c (classify s)]
        (assoc c
               :symbol s
               :range (z/position-span zloc)
               :owners (when (= :dot-name (:kind c)) (owner-candidates zloc)))))))
