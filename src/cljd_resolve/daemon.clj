(ns cljd-resolve.daemon
  "The resolve daemon (design.md §4 step 2).

   Newline-delimited JSON-RPC 2.0 on stdio: one JSON object per line in, one
   per line out. Line-delimited rather than LSP's Content-Length framing
   because the only client is our own extension and this is trivially
   testable from a shell.

   Positions on the wire are LSP's: 0-based `line` and `character`. Positions
   inside are rewrite-clj's: 1-based `row`/`col`. The conversion happens here
   and nowhere else.

     {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resolve\",
      \"params\":{\"file\":\"/p/src/acme/main.cljd\",\"line\":16,\"character\":8}}

     {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{
       \"doc\":\"If non-null, the style to use for this text.\\n...\",
       \"signature\":\"TextStyle? style\",
       \"kind\":\"parameter\", \"name\":\"style\", \"container\":\"Text(style:)\",
       \"lib\":\"package:flutter/material.dart\",
       \"defUri\":\"file:///.../text.dart\",
       \"defRange\":{\"start\":{\"line\":331,\"character\":22},
                     \"end\":{\"line\":331,\"character\":27}},
       \"originRange\":{...}}}

   Methods: resolve · ping · clearCache · shutdown."
  (:require [cheshire.core :as json]
            [cljd-resolve.analyzer :as an]
            [cljd-resolve.resolve :as r]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader]))

;; ------------------------------------------------------- offset -> position

(defn- line-starts
  "Character offsets of every line start in `s`."
  [^String s]
  (loop [i 0, acc [0]]
    (let [n (.indexOf s "\n" i)]
      (if (neg? n) acc (recur (inc n) (conj acc (inc n)))))))

(defonce ^:private line-index-cache (atom {}))

(defn- line-index [file]
  (let [f   (io/file file)
        key [(.getPath f) (.lastModified f) (.length f)]]
    (or (get @line-index-cache key)
        (let [idx (line-starts (slurp f))]
          (swap! line-index-cache assoc key idx)
          idx))))

(defn- offset->pos
  "Character offset in `file` -> 0-based {:line :character}."
  [file offset]
  (when (and file offset)
    (let [idx (line-index file)
          ;; the last line start at or before `offset`
          n   (loop [lo 0, hi (dec (count idx))]
                (if (> lo hi)
                  hi
                  (let [mid (quot (+ lo hi) 2)]
                    (if (<= (nth idx mid) offset)
                      (recur (inc mid) hi)
                      (recur lo (dec mid))))))]
      {:line n :character (- offset (nth idx n))})))

(defn- file-uri [file]
  (when file (str (.toUri (.toPath (io/file file))))))

;; --------------------------------------------------------------- the result

(defn- def-range [{:keys [file offset length]}]
  (when-let [start (offset->pos file offset)]
    {:start start
     :end   (or (offset->pos file (+ offset (or length 0))) start)}))

(defn- origin-range
  "The `.cljd` span of the symbol under the cursor, so the editor can underline
   exactly it. rewrite-clj's span is 1-based and end-exclusive."
  [[[r1 c1] [r2 c2]]]
  (when r1
    {:start {:line (dec r1) :character (dec c1)}
     :end   {:line (dec r2) :character (dec c2)}}))

(defn- present [hit]
  (when hit
    (cond-> (select-keys hit [:doc :signature :kind :name :container :lib :symbol :owner])
      true             (assoc :defUri (file-uri (:file hit)))
      (:offset hit)    (assoc :defRange (def-range hit))
      (:range hit)     (assoc :originRange (origin-range (:range hit))))))

;; --------------------------------------------------------------- dispatch

(defn handle
  "Handles one decoded request map. Returns the JSON-RPC `result`."
  [{:keys [method params]}]
  (case method
    "resolve"
    (let [{:strs [file text line character col row]} params
          row (or row (some-> line inc))
          col (or col (some-> character inc))]
      (when (and file row col)
        (present (r/resolve-cursor {:file file :text text :row row :col col}))))

    "ping"       {:ok true}
    "clearCache" (do (an/clear-all-caches!) (reset! line-index-cache {}) {:ok true})
    "shutdown"   (do (an/shutdown-all!) {:ok true})
    (throw (ex-info (str "unknown method: " method) {:code -32601}))))

(defn- respond [id result]
  (println (json/generate-string {:jsonrpc "2.0" :id id :result result})))

(defn- respond-error [id code message]
  (println (json/generate-string {:jsonrpc "2.0" :id id
                                  :error {:code code :message message}})))

(defn serve
  "Reads requests off `rdr` until EOF. Writes responses to *out*, one per line."
  [^BufferedReader rdr]
  (loop []
    (when-let [line (.readLine rdr)]
      (let [req (try (json/parse-string line) (catch Exception e {:parse-error (ex-message e)}))
            id  (get req "id")]
        (cond
          (:parse-error req) (respond-error nil -32700 (:parse-error req))
          :else
          (try
            (respond id (handle {:method (get req "method") :params (get req "params")}))
            (catch Exception e
              (respond-error id (or (:code (ex-data e)) -32603) (ex-message e)))))
        (when-not (= "shutdown" (get req "method"))
          (recur))))))

(defn -main [& _]
  (let [rdr (io/reader *in*)]
    ;; every response is one line, flushed as it is written
    (binding [*flush-on-newline* true]
      (serve rdr))
    (an/shutdown-all!)
    (shutdown-agents)))
