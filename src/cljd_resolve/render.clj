(ns cljd-resolve.render
  "Turns the analyzer's element maps back into Dart source syntax, so a hover
   reads the way the same declaration reads in a `.dart` file -- long parameter
   lists included, which are broken one per line the way `dart format` does."
  (:require [clojure.string :as str]))

(defn type-str
  "`TextStyle`, `List<Widget>`, `Color?`, `void`."
  [t]
  (when (map? t)
    (let [nm  (or (:element-name t) (some-> (:qname t) str))
          tps (seq (keep type-str (:type-parameters t)))]
      (when nm
        (str nm
             (when tps (str "<" (str/join ", " tps) ">"))
             (when (:nullable t) "?"))))))

(defn- type-params-str [tps]
  (let [names (keep #(or (:element-name %) (some-> (:qname %) str)) tps)]
    (when (seq names) (str "<" (str/join ", " names) ">"))))

(defn param-str [p]
  (str/join " " (remove str/blank? [(type-str (:type p)) (str (:name p))])))

(defn params-str
  "Dart's parameter syntax: required positionals, then `[optional]`, then
   `{named}`."
  [params]
  (let [named (filter #(= :named (:kind %)) params)
        pos   (remove #(= :named (:kind %)) params)
        opt   (filter :optional pos)
        req   (remove :optional pos)]
    (str/join ", "
              (concat (map param-str req)
                      (when (seq opt)   [(str "[" (str/join ", " (map param-str opt)) "]")])
                      (when (seq named) [(str "{" (str/join ", " (map param-str named)) "}")])))))

(def ^:private wrap-at
  "How wide a one-line declaration may be before its parameter list is broken
   one per line. A Flutter constructor blows past any such number -- `Text`'s
   is 400-odd characters -- and a hover popup does not wrap it for you."
  72)

(defn- arglist
  "The `(...)` of a declaration, given everything to the left of it. One line
   while it fits inside `wrap-at`; past that, one parameter per line, broken
   the way `dart format` breaks it -- the `{` or `[` rides the end of the line
   above, and every entry keeps its trailing comma."
  [prefix params]
  (let [flat (str prefix "(" (params-str params) ")")]
    (if (<= (count flat) wrap-at)
      flat
      (let [named (filter #(= :named (:kind %)) params)
            pos   (remove #(= :named (:kind %)) params)
            opt   (filter :optional pos)
            req   (remove :optional pos)
            [o c] (cond (seq named) ["{" "}"] (seq opt) ["[" "]"] :else [nil nil])
            grp   (if (seq named) named opt)
            reqs  (map #(str "  " (param-str %)) req)
            reqs  (if (and o (seq reqs))
                    (concat (map #(str % ",") (butlast reqs))
                            [(str (last reqs) ", " o)])
                    (map #(str % ",") reqs))]
        (str prefix "(" (when (and o (empty? req)) o) "\n"
             (str/join "\n" (concat reqs (map #(str "  " (param-str %) ",") grp)))
             "\n" c ")")))))

(defn signature
  "The Dart declaration for `m`, an analyzer element map -- one line unless
   `arglist` had to break it. `name` is the key it was found under; the
   analyzer keeps names in the enclosing map, not in the element."
  [m name]
  (when (map? m)
    (let [tps (type-params-str (:type-parameters m))]
      (case (:kind m)
        :class
        (str "class " name tps
             (when-let [s (type-str (:super m))]
               (when-not (= "Object" s) (str " extends " s))))

        :constructor
        (arglist (str (when (:const m) "const ") name tps) (:parameters m))

        :method
        (arglist (str (when (:static m) "static ")
                      (type-str (:return-type m)) " " name tps)
                 (:parameters m))

        :function
        (arglist (str (type-str (:return-type m)) " " name tps) (:parameters m))

        :field
        (str (when (:static m) "static ")
             (if (:const m) "const " (when-not (:setter m) "final "))
             (type-str (:type m)) " " name)

        :parameter
        (param-str (assoc m :name name))

        (str name)))))
