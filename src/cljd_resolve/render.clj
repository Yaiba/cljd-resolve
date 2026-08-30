(ns cljd-resolve.render
  "Turns the analyzer's element maps back into Dart source syntax, so a hover
   reads the way the same declaration reads in a `.dart` file."
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

(defn signature
  "A one-line Dart declaration for `m`, an analyzer element map. `name` is the
   key it was found under -- the analyzer keeps names in the enclosing map, not
   in the element."
  [m name]
  (when (map? m)
    (let [tps (type-params-str (:type-parameters m))]
      (case (:kind m)
        :class
        (str "class " name tps
             (when-let [s (type-str (:super m))]
               (when-not (= "Object" s) (str " extends " s))))

        :constructor
        (str (when (:const m) "const ") name tps "(" (params-str (:parameters m)) ")")

        :method
        (str (when (:static m) "static ")
             (type-str (:return-type m)) " " name tps "(" (params-str (:parameters m)) ")")

        :function
        (str (type-str (:return-type m)) " " name tps "(" (params-str (:parameters m)) ")")

        :field
        (str (when (:static m) "static ")
             (if (:const m) "const " (when-not (:setter m) "final "))
             (type-str (:type m)) " " name)

        :parameter
        (param-str (assoc m :name name))

        (str name)))))
