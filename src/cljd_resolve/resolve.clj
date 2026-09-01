(ns cljd-resolve.resolve
  "cursor in a `.cljd` buffer -> Dart element (design.md §3, route B).

   Three shapes, all syntactic:

     m/Text          a type, or a top-level fn/var, in the aliased library --
                     but its unnamed constructor when it heads a call, which
                     is the element a `.dart` hover on `Text('hi')` shows
     m.Colors/red    a static member of a type in the aliased library
     .style          a named parameter of the enclosing constructor call --
                     or, failing that, a member of that class

   Anything needing real type inference resolves to nil rather than guessing."
  (:require [cljd-resolve.analyzer :as an]
            [cljd-resolve.parse :as parse]
            [cljd-resolve.render :as render]
            [clojure.string :as str]))

(defn- located?
  "An element the analyzer could place in a file -- everything downstream of a
   hover needs at least one of a doc or a location to be worth returning."
  [m]
  (and (map? m) (or (:doc m) (and (:file m) (:offset m)))))

(defn- class-map?
  "The analyzer returns a class as a map of member-name -> member map. A
   top-level function or variable has no such string keys."
  [m]
  (and (map? m) (some string? (keys m))))

(defn- ctor
  "The constructor of class map `cls` named `name` (the unnamed one is keyed by
   the class's own name)."
  [cls name]
  (let [c (get cls name)]
    (when (= :constructor (:kind c)) c)))

(defn- named-param [c member]
  (->> (:parameters c)
       (filter #(and (= :named (:kind %)) (= member (str (:name %)))))
       first))

(defn- dotted-static
  "`add` out of `Icons.add`, when `member` is `type`'s own dotted spelling.

   ClojureDart writes a named constructor `m/Text.rich` and a static
   `m/Icons.add` identically, and real cljd code uses both freely. The analyzer
   keys a named constructor under the whole `Text.rich` and a static under the
   bare `add`, so the second spelling needs the type stripped back off before
   the class map has anything to say about it."
  [type member]
  (let [p (str type ".")]
    (when (and type member (str/starts-with? member p) (> (count member) (count p)))
      (subs member (count p)))))

(defn- find-in-class
  "`member` inside class map `cls`: a named parameter of constructor
   `ctor-name` first -- that is what `.style` means in a cljd constructor call
   -- then any member of the class itself."
  [cls ctor-name member]
  (or (when-let [c (ctor cls ctor-name)]
        (when-let [p (named-param c member)]
          [(assoc p :kind :parameter) (str ctor-name "(" member ":)")]))
      ;; some classes are reached through a type alias, whose constructors are
      ;; keyed under the alias -- fall back to any constructor that has it
      (some (fn [[k v]]
              (when (and (string? k) (= :constructor (:kind v)))
                (when-let [p (named-param v member)]
                  [(assoc p :kind :parameter) (str k "(" member ":)")])))
            cls)
      (let [m (get cls member)]
        (when (map? m) [m nil]))))

;; --------------------------------------------------------------- resolution

(defn- lookup-type
  "`alias`/`type` -> [class-or-element-map lib], via the ns alias table."
  [a aliases alias type]
  (when-let [lib (get aliases alias)]
    (when-let [e (an/element a lib type)]
      [e lib])))

(defn- resolve-qualified [a {:keys [aliases]} {:keys [alias type member head?]}]
  (when-let [[e lib] (lookup-type a aliases alias type)]
    (cond
      member
      (when (class-map? e)
        (if-let [[m via] (find-in-class e type member)]
          {:element m :name member :lib lib :container (or via type)}
          ;; Not a named constructor, so `m/Icons.add` -- the same spelling
          ;; reaching a static, which the class map keys bare.
          (when-let [bare (dotted-static type member)]
            (when-let [[m via] (find-in-class e type bare)]
              {:element m :name bare :lib lib :container (or via type)}))))

      ;; `(m/Text "hi" ...)` is a constructor invocation, so hover the
      ;; constructor -- which is what a `.dart` hover on `Text('hi')` shows --
      ;; rather than the class and its whole dartdoc (375 chars against 4228,
      ;; for Text). Only when that constructor is itself documented; where it
      ;; is not, the class prose is still the better answer.
      (and head? (class-map? e) (:doc (ctor e type)))
      {:element (ctor e type) :name type :lib lib :container type}

      :else
      {:element e :name type :lib lib :container lib})))

(defn- resolve-bare [a {:keys [refers]} {:keys [name]}]
  (when-let [lib (get refers name)]
    (when-let [e (an/element a lib name)]
      {:element e :name name :lib lib :container lib})))

(defn- resolve-dot-name
  "Tries each owner candidate in turn -- nearest first -- and takes the first
   one that both names a Dart class and has `member` in it."
  [a {:keys [aliases]} {:keys [member owners]}]
  (some (fn [cand]
          (let [{:keys [alias type] k :kind m :member} (parse/classify cand)]
            (when (and (= :qualified k) (get aliases alias)
                       ;; A named constructor heads a call as ordinarily as an
                       ;; unnamed one -- `(m/ListView.builder .itemCount ...)`
                       ;; -- and the parameters are that constructor's. A
                       ;; static, `m.Colors/red`, heads nothing: it is a value,
                       ;; and the named arguments beside it are not its own.
                       (or (nil? m) (dotted-static type m)))
              (when-let [[cls lib] (lookup-type a aliases alias type)]
                (when (class-map? cls)
                  (when-let [[e via] (find-in-class cls (or m type) member)]
                    {:element e :name member :lib lib
                     :container (or via type) :owner cand}))))))
        owners))

(defn- presented
  "The flat map a resolved element is handed back as: what it is, where it is,
   and how to render it. Shared with `describe`, which reaches the same
   elements by name rather than by cursor."
  [{:keys [element name] :as hit}]
  (when (located? element)
    (merge (select-keys element [:doc :file :offset :length])
           (select-keys hit [:lib :container :owner])
           {:kind      (clojure.core/name (or (:kind element) :unknown))
            :name      name
            :signature (render/signature element name)})))

(defn resolve-cursor
  "The whole of route B. `text` is the buffer (possibly unsaved); `file` only
   locates the Dart project. `row`/`col` are 1-based.

   => {:doc :signature :kind :name :container :lib :symbol :range
       :file :offset :length}, or nil."
  [{:keys [file text row col]}]
  (let [text (or text (slurp file))
        cur  (parse/cursor-info text [row col])
        nsi  (parse/ns-info text)]
    (when (and cur (or (seq (:aliases nsi)) (seq (:refers nsi))))
      (when-let [a (an/for-file file)]
        (when-let [hit (case (:kind cur)
                         :qualified (resolve-qualified a nsi cur)
                         :bare      (resolve-bare a nsi cur)
                         :dot-name  (resolve-dot-name a nsi cur)
                         nil)]
          (some-> (presented hit)
                  (assoc :symbol (:symbol cur) :range (:range cur))))))))

;; -------------------------------------------------------------- completion
;;
;; The same three shapes again, asked the other way round: not "what is this
;; symbol" but "what could it become". Every candidate offered here is one
;; `resolve-cursor` above would then resolve -- the completer walks the class
;; map with `find-in-class`'s own preferences rather than a set of its own, so
;; accepting a candidate cannot land the cursor on something the hover then
;; has nothing to say about.
;;
;; `m/Scaf` -- the whole aliased library -- is the one shape that needs
;; something `elt` cannot do, since that answers for a name you already have.
;; It is served by the helper's `names` command, which is why that command
;; exists (helper/bin/names.dart).

(defn- item
  "One candidate. `group` orders the set it came from ahead of or behind the
   others -- the editor's own ranking is a fuzzy match on the label alone, and
   it has no way to know that a constructor's named parameter is a better
   answer for `.sty` than a field that happens to share the name.

   `:member` is the key the candidate lives under in its class map, sent only
   when it differs from the label -- a static reached as `m/Icons.add` is
   inserted as `Icons.add` and keyed `add`, and `describe` needs the key."
  [m label container group & [member]]
  (let [detail (render/detail m label)]
    (cond-> {:label label
             :kind  (name (or (:kind m) :unknown))
             :sort  (str group label)}
      container                      (assoc :container container)
      detail                         (assoc :detail detail)
      (and member (not= member label)) (assoc :member member))))

(defn- ctor-params
  "The named parameters of the constructor keyed `ctor-name` in `cls`, else of
   whichever constructor it has -- `find-in-class`'s fallback, for a class
   reached through a type alias whose constructors are keyed under the alias.

   `ctor-name` is the class's own name for the unnamed constructor and the
   whole `ListView.builder` for a named one, which is how the analyzer keys
   them both."
  [cls ctor-name]
  (let [from (fn [k c] (for [p (:parameters c) :when (= :named (:kind p))]
                         [p (str k "(" (:name p) ":)")]))]
    (or (when-let [c (get cls ctor-name)]
          (when (= :constructor (:kind c)) (seq (from ctor-name c))))
        (some (fn [[k v]]
                (when (and (string? k) (= :constructor (:kind v)))
                  (seq (from k v))))
              cls))))

(defn- members
  "The class's own members, `pred` deciding which kind of access is being
   completed."
  [cls pred]
  (for [[k v] cls :when (and (string? k) (map? v) (pred v))] [k v]))

(defn- distinct-labels
  "First candidate of each name wins. `Label`'s `style` parameter and the
   `style` field it forwards to are one row in a dropdown, not two -- and the
   parameter is the one `find-in-class` would resolve, so it is the one kept."
  [items]
  (second (reduce (fn [[seen out] i]
                    (if (seen (:label i))
                      [seen out]
                      [(conj seen (:label i)) (conj out i)]))
                  [#{} []]
                  items)))

(defn- complete-named-args
  "`.sty` -- the named parameters of the enclosing constructor call, then the
   class's instance members. The first owner candidate that names a class
   wins.

   The head may name a *named* constructor: `(m/ListView.builder .itemCount
   ...)` is as ordinary in ClojureDart as `(m/Scaffold .body ...)`, and its
   parameters are that constructor's, not the unnamed one's. So a candidate
   carrying a `Type.ctor` member is an owner like any other -- it just says
   which constructor to read the parameters off."
  [a aliases owners]
  (some (fn [cand]
          (let [{:keys [alias type] k :kind m :member} (parse/classify cand)]
            (when (and (= :qualified k) (get aliases alias)
                       ;; `m.Colors/red` heads nothing -- a static member is a
                       ;; value, and its named arguments are not these.
                       (or (nil? m) (str/starts-with? (str m) (str type "."))))
              (when-let [[cls lib] (lookup-type a aliases alias type)]
                (when (class-map? cls)
                  (let [ctor-key (or m type)]
                    ;; Which constructor the parameters were read off, so
                    ;; `describe` reads the same one back. Without it a name
                    ;; two constructors share -- `ListView`'s `itemCount` is
                    ;; `required` on `.separated` and optional on `.builder` --
                    ;; is described against whichever comes first.
                    {:lib lib :owner cand :type type :ctor ctor-key
                     :items (concat
                             (for [[p via] (ctor-params cls ctor-key)]
                               (item (assoc p :kind :parameter) (str (:name p)) via 0))
                             (for [[k v] (members cls #(and (#{:field :method} (:kind %))
                                                            (not (:static %))))]
                               (item v k type 1)))}))))))
        owners))

(defn- complete-members
  "`m.Colors/re` -- statics only. An instance member is perfectly spellable in
   ClojureDart, but not through a dotted alias, so offering one would be
   offering something that does not compile."
  [a aliases alias type]
  (when-let [[cls lib] (lookup-type a aliases alias type)]
    (when (class-map? cls)
      {:lib lib :type type
       :items (for [[k v] (members cls #(and (#{:field :method} (:kind %)) (:static %)))]
                (item v k type 0))})))

(defn- complete-constructors
  "`m/Text.ri` -- everything `alias/Type.name` can reach.

   Two things, not one. ClojureDart spells a named constructor `m/Text.rich`
   and a static `m/Icons.add` the same way, and real cljd code uses both
   freely -- so offering only constructors here answers nothing at all for a
   library of icons or colours. Constructors rank first because the shape is
   most often a widget being built.

   The unnamed constructor is keyed by the class's own name and carries
   `:named false`; the cursor has already committed to a dot, so it is not a
   candidate. Statics are keyed bare, so their label is rebuilt with the type
   the cursor named and `:member` carries the key they are actually under."
  [a aliases alias type]
  (when-let [[cls lib] (lookup-type a aliases alias type)]
    (when (class-map? cls)
      {:lib lib :type type
       :items (concat
               (for [[k v] (members cls #(and (= :constructor (:kind %)) (:named %)))]
                 (item v k type 0))
               (for [[k v] (members cls #(and (#{:field :method} (:kind %)) (:static %)))]
                 (item v (str type "." k) type 1 k)))})))

(defn- complete-library
  "`m/AnimatedCro` -- the top-level names of the aliased library.

   No `detail` on these. The list is the whole of `package:flutter/material.dart`
   -- 1865 names -- and a signature apiece would mean resolving 1865 elements
   to fill a column the user reads one row of. The kind is enough for the icon;
   `describe` fills the rest in for the row they land on."
  [a aliases alias]
  (when-let [lib (get aliases alias)]
    (when-let [ns (an/names a lib)]
      {:lib lib
       :items (for [[nm kind] ns]
                {:label nm :kind (name (or kind :unknown)) :sort (str 0 nm)})})))

(defn- complete-refers
  "A `:refer`red name -- answered off the ns table, so it costs one `elt` per
   candidate and nothing at all when the prefix matches none.

   Only for a non-empty prefix. Every bare symbol in a `.cljd` buffer would
   otherwise arrive here on every keystroke, and Clojure's own names are
   Calva's to answer for."
  [a refers prefix]
  (when (seq prefix)
    (let [hits (for [[nm lib] refers :when (str/starts-with? nm prefix)] [nm lib])]
      (when (seq hits)
        {:items (for [[nm lib] hits
                      :let [e (an/element a lib nm)]
                      :when (map? e)]
                  (item e nm lib 0))}))))

(defn complete-cursor
  "Candidates for the half-typed symbol at 1-based `row`/`col` in `text`.

   => {:target :named-args :prefix \"sty\" :segment [[r c] [r c]] :range [...]
       :lib ... :type ... :owner ...
       :items [{:label :kind :detail :container :sort}]}

   `:items` is filtered to `:prefix` here rather than left to the editor: the
   editor filters what it was sent, and it is this side that knows a named
   constructor is keyed `Text.rich` while the label shown is the same string.
   nil when the cursor is nowhere a Dart name can go."
  [{:keys [file text row col]}]
  (let [text (or text (slurp file))
        ;; Both off one repaired buffer. Read separately, the alias table
        ;; would be empty for every shape that ends in `/` -- see
        ;; `parse/completion-context`.
        {cur :cursor nsi :ns} (parse/completion-context text [row col])]
    (when (and cur (or (seq (:aliases nsi)) (seq (:refers nsi))))
      (when-let [a (an/for-file file)]
        (let [{:keys [target prefix alias type owners]} cur
              {:keys [aliases refers]} nsi
              hit (case target
                    :named-args   (complete-named-args a aliases owners)
                    :members      (complete-members a aliases alias type)
                    :constructors (complete-constructors a aliases alias type)
                    :refers       (complete-refers a refers prefix)
                    :library      (complete-library a aliases alias)
                    nil)]
          (merge (select-keys cur [:target :prefix :range :segment])
                 (select-keys hit [:lib :type :owner :ctor])
                 ;; Dedupe before sorting: `:sort` carries the group each
                 ;; candidate came from, so the seq is already in the order
                 ;; that decides which duplicate is the one to keep.
                 {:items (vec (sort-by :sort
                                       (distinct-labels
                                        (filter #(str/starts-with? (:label %) prefix)
                                                (:items hit)))))}))))))

(def ^:private in-a-class
  "The completion targets whose candidates live inside one class map."
  #{"named-args" "members" "constructors"})

(defn describe
  "One completion candidate, in full -- the same map `resolve-cursor` hands
   back for the same element.

   The editor asks for this only for the row the user has highlighted, so a
   list of a thousand candidates costs a thousand labels rather than a
   thousand docstrings. It is a lookup, not a search: `lib`, `type` and
   `label` are what `complete` already sent, and the class map behind them is
   still in the analyzer's cache."
  [{:keys [file lib type label target member ctor]}]
  (when (and file lib label)
    (when-let [a (an/for-file file)]
      ;; `member` is the key the candidate is under when that is not the label
      ;; the editor inserts -- `m/Icons.add` is keyed `add`. `ctor` is the
      ;; constructor the list was built from, so a parameter is described
      ;; against the one that offered it.
      (let [key (or member label)]
        (presented
         (if (and type (in-a-class (some-> target name)))
           (when-let [cls (an/element a lib type)]
             (when (class-map? cls)
               (when-let [[m via] (find-in-class cls (or ctor type) key)]
                 {:element m :name key :lib lib :container (or via type)})))
           (when-let [e (an/element a lib key)]
             {:element e :name key :lib lib :container lib})))))))
