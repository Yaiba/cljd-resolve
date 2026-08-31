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
            [cljd-resolve.render :as render]))

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
        (when-let [[m via] (find-in-class e type member)]
          {:element m :name member :lib lib :container (or via type)}))

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
            (when (and (= :qualified k) (nil? m) (get aliases alias))
              (when-let [[cls lib] (lookup-type a aliases alias type)]
                (when (class-map? cls)
                  (when-let [[e via] (find-in-class cls type member)]
                    {:element e :name member :lib lib
                     :container (or via type) :owner cand}))))))
        owners))

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
          (let [{:keys [element name]} hit]
            (when (located? element)
              (merge (select-keys element [:doc :file :offset :length])
                     (select-keys hit [:lib :container :owner])
                     {:kind      (clojure.core/name (or (:kind element) :unknown))
                      :name      name
                      :signature (render/signature element name)
                      :symbol    (:symbol cur)
                      :range     (:range cur)}))))))))
