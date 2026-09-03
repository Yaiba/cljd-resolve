(ns cljd-resolve.parse
  "Everything the daemon reads out of a `.cljd` buffer with rewrite-clj:
   the ns alias table, the token under the cursor, and -- for a `.named-arg`
   -- the symbols that might name the Dart type it belongs to.

   Purely syntactic. Nothing here talks to the analyzer; `classify` and
   `owner-candidates` hand back *candidates*, and cljd-resolve.resolve decides
   which one actually resolves."
  (:require [clojure.string :as str]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------- ns parsing

(defn- string-end
  "The index just past the closing quote of the string opening at `i`, or nil
   when it never closes."
  [^String text i]
  (let [n (count text)]
    (loop [j (inc i)]
      (cond (>= j n)                nil
            (= \\ (.charAt text j)) (recur (+ j 2))
            (= \" (.charAt text j)) (inc j)
            :else                   (recur (inc j))))))

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
            \" (if-let [end (string-end text i)]
                 (recur end stack false)
                 (recur n stack true))
            (\( \[ \{) (recur (inc i) (conj stack (case c \( \) \[ \] \{ \})) false)
            (\) \] \}) (recur (inc i) (if (= c (first stack)) (rest stack) stack) false)
            (recur (inc i) stack false)))))))

(def ^:private token-break
  "Characters that end a bare token: whitespace and the delimiters. Strings,
   character literals and comments end one too -- `token-spans` handles those
   itself, since it has to skip over them anyway.

   Nothing else does, reader syntax included: `#`, `'`, `^` and friends are
   read together with whatever follows them, so `foo#/` is judged as the one
   unreadable token it is, and a dispatch left dangling mid-edit -- the `#` of
   a `#(` on its way to being typed -- is one `repair` can fix."
  (set " \t\r\n\f,()[]{}"))

(defn- token-spans
  "The `[start end)` spans of the bare tokens in `text` -- symbols, numbers and
   keywords. Skips strings, character literals and line comments, like
   `balance`."
  [^String text]
  (let [n (count text)]
    (loop [i 0, start nil, acc []]
      (if (>= i n)
        (cond-> acc start (conj [start i]))
        (let [c    (.charAt text i)
              ;; a break -- the token in progress ends at `i`, and the scan
              ;; picks back up here
              past (case c
                     \; (let [j (.indexOf text "\n" i)] (if (neg? j) n (inc j)))
                     \\ (+ i 2)                       ; \c character literal
                     \" (or (string-end text i) n)
                     (when (token-break c) (inc i)))]
          (if past
            (recur past nil (cond-> acc start (conj [start i])))
            (recur (inc i) (or start i) acc)))))))

(def ^:private filler
  "The character an unreadable token is rewritten with. Deliberately not the
   completion `sentinel`: `info-at` tells the two apart to know whether the
   token under the cursor is one it repaired."
  \z)

(defn- readable?
  "Whether rewrite-clj can read `s` on its own."
  [s]
  (try (p/parse-string s) true (catch Exception _ false)))

(defn- form-start
  "The index of the next form at or after `i` -- whitespace and comments
   skipped -- or nil when the form `i` sits in ends first, or the buffer does."
  [^String text i]
  (let [n (count text)]
    (loop [j i]
      (let [c (when (< j n) (.charAt text j))]
        (cond
          (nil? c)                     nil
          (or (Character/isWhitespace c) (= \, c)) (recur (inc j))
          (= \; c)                     (let [k (.indexOf text "\n" j)]
                                         (when-not (neg? k) (recur (inc k))))
          (#{\) \] \}} c)              nil
          :else                        j)))))

(defn- attached?
  "Whether the token `s` ending at `end` reads as nothing on its own only
   because it is a reader prefix -- `^:no-doc`, `#`, `#_`, `'` -- standing in
   front of a form that is really there. Then it is valid code: `repair` must
   leave it alone, metadata and all, rather than flatten it into a symbol.

   A `#` binds to whatever comes immediately next, so `#(` is attached and
   `# (` is not; every other prefix may have whitespace before its form."
  [^String text s end]
  (and (readable? (str s "{} x"))       ; a map and a value: enough for any prefix
       (if (str/ends-with? s "#")
         (= end (form-start text end))
         (some? (form-start text end)))))

(defn- rewritten
  "A token of exactly `s`'s length that rewrite-clj can read, to stand in for
   the unreadable `s`. A keyword keeps the `:` or `::` it opens with, so it is
   rewritten into a keyword rather than a symbol -- but only where that leaves
   something readable (`:::foo` does not) and something to rewrite (`:` is all
   colon), so the answer is checked before it is handed back. All filler always
   reads: a token holds no whitespace or delimiter to interrupt it."
  [s]
  (letfn [(fill [i] (apply str (concat (take i s) (repeat (- (count s) i) filler))))]
    (let [kept (fill (min 2 (count (take-while #(= \: %) s))))]
      (if (readable? kept) kept (fill 0)))))

(defn- repair
  "`text` with every token rewrite-clj refuses to read -- a half-typed `m/`,
   `m.Colors/`, `1.2.3`, a `^` still waiting for whatever it hangs off --
   overwritten character for character with a readable one.

   rewrite-clj reads the buffer as a whole, so without this a single unfinished
   symbol anywhere takes the alias table and every hover in the file with it.
   Same length, so every position outside the broken token is where it was, and
   nothing a delimiter is nested in moves. Reader syntax standing in front of
   the form it belongs to reads as nothing on its own but is not broken, and is
   left as it is -- see `attached?`."
  [^String text]
  (let [sb (StringBuilder. text)]
    (doseq [[start end] (token-spans text)
            :let  [s (subs text start end)]
            :when (and (not (readable? s)) (not (attached? text s end)))]
      (.replace sb start end (rewritten s)))
    (str sb)))

(defn- zip-of
  "A position-tracking zipper over `text`, repaired first if it does not parse:
   delimiters left open are closed off, and tokens that cannot be read are
   rewritten -- so a hover works halfway through typing a form, and goes on
   working everywhere else while one sits unfinished."
  [text]
  (letfn [(zip [s] (try (z/of-string s {:track-position? true}) (catch Exception _ nil)))
          (closed [s] (or (zip s) (when-let [cs (balance s)] (zip (str s (apply str cs))))))]
    (or (closed text)
        (let [fixed (repair text)]
          (when (not= fixed text) (closed fixed))))))

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

(defn- call-head?
  "The token is the first child of an enclosing list -- so the form is a call,
   `(m/Text \"hi\" ...)`, and in Dart terms a constructor invocation."
  [zloc]
  (boolean (and (= :list (some-> (z/up zloc) z/tag))
                (empty? (left-siblings zloc)))))

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
               :head? (call-head? zloc)
               :owners (when (= :dot-name (:kind c)) (owner-candidates zloc)))))))

;; ---------------------------------------------------------------- completion
;;
;; Completion asks about a symbol that is still being typed, and the shapes
;; that matter most are the ones rewrite-clj will not read at all: `m/`,
;; `m.Colors/` and `m/Text.` are not symbols, so `cursor-info` answers nil at
;; exactly the keystroke that should open a dropdown. (A non-empty prefix --
;; `m/Scaf`, `.sty` -- is already a readable token and needs none of this.)
;;
;; So a sentinel name character is spliced in at the cursor before parsing,
;; and removed again by position afterwards. Splicing rather than appending
;; because the cursor is not always at the end of its token: `m/Scaf|fold` is
;; a real thing to ask about, and its answer replaces the whole token while
;; filtering on only what precedes the cursor.

(def ^:private sentinel
  "The character spliced in at the cursor so the token parses. Any symbol
   character does -- it is removed by index, never by name -- and it never
   leaves this namespace: it goes into the buffer handed to rewrite-clj and
   comes straight back out of every string reported from here."
  \X)

(defn- cursor-at
  "1-based `[row col]` in `text` as `[offset col]`, both clamped to the end of
   that row -- a column past the line end is a cursor in the trailing
   whitespace an editor allows, not a position on a later line. The clamped
   column comes back with the offset because everything downstream measures
   against the same one; clamping only the offset is how the sentinel lands in
   one place and gets looked for in another.

   nil when `row` is past the end of the buffer."
  [^String text row col]
  (loop [i 0, r 1]
    (if (= r row)
      (let [eol (let [n (.indexOf text "\n" i)] (if (neg? n) (count text) n))
            off (min (+ i (dec col)) eol)]
        [off (inc (- off i))])
      (let [n (.indexOf text "\n" i)]
        (when-not (neg? n) (recur (inc n) (inc r)))))))

(defn- strip-at
  "`s` without the character at 0-based `i`."
  [^String s i]
  (str (subs s 0 i) (subs s (inc i))))

(defn- segment-start
  "Where the part being completed begins inside a token: after the `/` of a
   qualified symbol, after the leading `.` or `.-` of a named argument, and at
   the start of a bare one.

   `m/Text.rich` deliberately keeps its `Text.` inside the segment: the
   analyzer keys a named constructor under the whole of it, so that is what a
   candidate has to be filtered against."
  [kind ^String s]
  (case kind
    :qualified (inc (.indexOf s "/"))
    :dot-name  (if (str/starts-with? s ".-") 2 1)
    0))

(defn- spliced
  "`text` with the sentinel at 1-based `[row col]`, and the column it landed
   in -- clamped, so the two always agree about where it is. nil past the end
   of the buffer."
  [text row col]
  (when-let [[off col] (cursor-at text row col)]
    [(str (subs text 0 off) sentinel (subs text off)) col]))

(defn- info-at
  "`completion-info`, given a buffer the sentinel is already in."
  [text' row col]
  (when-let [cur (cursor-info text' [row col])]
    (let [[[r1 c1] [r2 c2]] (:range cur)
          kind (:kind cur)
          i    (- col c1)                  ; the sentinel's index in the token
          sym  (:symbol cur)
          s    (strip-at sym i)            ; the token as actually typed
          seg  (segment-start kind s)]
      ;; The sentinel is what makes the token parse, so everything left of it
      ;; -- the alias, the `/`, the leading `.` -- is already clean. A cursor
      ;; *before* the segment is asking to complete something that is not a
      ;; name: `m|/Text`, or the `.` of `.style`.
      ;;
      ;; A token without the sentinel where it was spliced is one `repair`
      ;; rewrote: what is being typed there could not be read even with the
      ;; sentinel in it, and there is nothing to complete against.
      (when (and (= sentinel (nth sym i nil)) (<= seg i))
        (let [tail   (subs s seg)                    ; the whole segment
              quali  (when (= :qualified kind) (subs s 0 (dec seg)))
              target (case kind
                       :dot-name :named-args
                       :bare     :refers
                       (cond
                         ;; m.Colors/re -- the type is named before the `/`
                         (str/includes? quali ".")  :members
                         ;; m/Text.ri -- and here, inside the segment
                         (str/includes? tail ".")   :constructors
                         :else                      :library))]
          (cond-> {:target  target
                   :prefix  (subs s seg i)
                   :symbol  s
                   :range   [[r1 c1] [r2 (dec c2)]]
                   :segment [[r1 (+ c1 seg)] [r2 (dec c2)]]}
            (= :qualified kind)   (assoc :alias (:alias cur))
            ;; `:type` is read back off the clean token rather than taken from
            ;; `classify`, which saw the sentinel: for a constructor the type
            ;; sits inside the segment, so `m/Te|xt.rich` would otherwise
            ;; resolve against `TeXxt`.
            (= :members target)      (assoc :type (:type cur))
            (= :constructors target) (assoc :type (first (str/split tail #"\." 2)))
            (= :named-args target)   (assoc :owners (:owners cur))))))))

(defn completion-info
  "What is being typed at 1-based `[row col]`, where the cursor sits *before*
   `col` -- so the prefix is the completing segment up to it, and what follows
   is the rest of a token being edited in the middle.

     m/Scaf      => {:target :library      :alias \"m\"                :prefix \"Scaf\"}
     m.Colors/re => {:target :members      :alias \"m\" :type \"Colors\" :prefix \"re\"}
     m/Text.ri   => {:target :constructors :alias \"m\" :type \"Text\"   :prefix \"Text.ri\"}
     .sty        => {:target :named-args   :owners [\"m/Text\"]        :prefix \"sty\"}
     pi          => {:target :refers                                 :prefix \"pi\"}

   `:segment` is the span an accepted candidate replaces, `:range` the whole
   token; both 1-based and end-exclusive, like `cursor-info`'s. A prefix may be
   empty -- that is `m/`, the keystroke this exists for -- and nil comes back
   only where no Dart name can go: a string, a comment, a number, or a cursor
   sitting before the segment it would complete.

   Descriptive, not a policy. An empty `:refers` prefix is reported like any
   other, and it is the caller that decides offering every bare symbol in the
   buffer is Calva's job rather than ours."
  [text [row col]]
  (when-let [[text' col] (spliced text row col)]
    (info-at text' row col)))

(defn completion-context
  "=> {:cursor <completion-info> :ns <ns-info>}, both read off the *same*
   repaired buffer.

   They have to be. `m/` and `m.Colors/` are not readable symbols -- that is
   the whole reason the sentinel exists -- so a buffer holding one does not
   parse at all, and an alias table read off the raw text comes back empty at
   exactly the keystroke completion exists for. Nothing resolves without an
   alias, so completion would work everywhere except where it is needed.

   nil past the end of the buffer; `:cursor` may be nil on its own where no
   Dart name can go, and the alias table is still worth having there."
  [text [row col]]
  (when-let [[text' col] (spliced text row col)]
    {:cursor (info-at text' row col)
     :ns     (ns-info text')}))
