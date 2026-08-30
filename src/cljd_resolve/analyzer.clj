(ns cljd-resolve.analyzer
  "Owns the patched Dart analyzer helper (design.md §4 step 1).

   One long-running `analyzer.dart` subprocess per project root, plus a cache
   of its `elt` answers -- the same trick `mk-live-analyzer-info`
   (compiler.cljc:209) plays inside a cljd build, since resolving a class out
   of the Flutter SDK costs tens of milliseconds and a hover happens on every
   mouse move."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private this-file *file*)

(defn helper-path
  "Absolute path of the vendored, patched `analyzer.dart`."
  []
  (or (System/getenv "CLJD_RESOLVE_HELPER")
      (-> (io/file this-file)          ; src/cljd_resolve/analyzer.clj
          .getCanonicalFile
          .getParentFile .getParentFile .getParentFile
          (io/file "helper" "bin" "analyzer.dart")
          .getPath)))

(defn project-root
  "Walks up from `file` to the Dart project it belongs to -- the directory the
   analyzer needs so it can resolve `package:` URIs against a package config."
  [file]
  (loop [d (some-> file io/file .getCanonicalFile .getParentFile)]
    (when d
      (if (or (.isFile (io/file d "pubspec.yaml"))
              (.isFile (io/file d "pubspec.yml")))
        (.getPath d)
        (recur (.getParentFile d))))))

;; ------------------------------------------------------------------ process

(defn start!
  "Spawns an analyzer for `root` and reads its startup banner."
  [root]
  (let [proc (p/process ["dart" "run" (helper-path) root]
                        {:in :stream :out :stream :err :inherit})
        out  (java.io.PushbackReader. (io/reader (:out proc)))
        in   (io/writer (:in proc))]
    {:root   root
     :proc   proc
     :lock   (Object.)
     :in     in
     :out    out
     :banner (edn/read {:eof nil} out)
     :cache  (atom {})}))

(defn stop! [a]
  (when a
    (try (.close ^java.io.Writer (:in a)) (catch Exception _ nil))
    (try (p/destroy (:proc a)) (catch Exception _ nil))))

(defn alive? [a]
  (boolean (and a (p/alive? (:proc a)))))

(defn- ask!
  "Sends one command and reads back the single EDN *form* it answers with --
   responses are newline-formatted, so they are forms, not lines."
  [a cmd]
  (locking (:lock a)
    (let [^java.io.Writer w (:in a)]
      (.write w (str cmd "\n"))
      (.flush w))
    (edn/read {:eof nil} (:out a))))

;; -------------------------------------------------------------------- cache

(defn- cached [a k f]
  (let [cache (:cache a)]
    (if-some [e (find @cache k)]
      (val e)
      (let [v (f)]
        (swap! cache assoc k v)
        v))))

(defn library?
  "True when `lib` (a Dart library URI) resolves in this project."
  [a lib]
  (cached a [:lib lib] #(true? (ask! a (str "lib " lib)))))

(defn element
  "The analyzer map for `name` in `lib`, or nil. For a class this is the map of
   its members keyed by name, carrying `:doc`/`:file`/`:offset`/`:length`."
  [a lib name]
  (cached a [:elt lib name] #(ask! a (str "elt " lib " " name))))

(defn clear-cache! [a] (reset! (:cache a) {}))

;; ----------------------------------------------------------------- registry

(defonce ^:private analyzers (atom {}))

(defn for-root
  "The analyzer for `root`, started on first use and restarted if it died."
  [root]
  (or (let [a (get @analyzers root)] (when (alive? a) a))
      (let [a (start! root)]
        (swap! analyzers assoc root a)
        a)))

(defn for-file
  "The analyzer owning `file`, or nil when the file is in no Dart project."
  [file]
  (some-> (project-root file) for-root))

(defn shutdown-all! []
  (run! stop! (vals @analyzers))
  (reset! analyzers {}))

(defn clear-all-caches! []
  (run! clear-cache! (vals @analyzers)))
