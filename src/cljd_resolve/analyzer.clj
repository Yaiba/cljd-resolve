(ns cljd-resolve.analyzer
  "Owns the patched Dart analyzer helper (design.md §4 step 1).

   One long-running `analyzer.dart` subprocess per project root, plus a cache
   of its `elt` answers -- the same trick `mk-live-analyzer-info`
   (compiler.cljc:209) plays inside a cljd build, since resolving a class out
   of the Flutter SDK costs tens of milliseconds and a hover happens on every
   mouse move.

   The registry supervises those children: a helper that never announces
   itself is a failed start, a helper that dies is stopped before it is
   replaced, and replacement is single-flight and backed off -- a `dart run`
   costs seconds, so one broken `dart` must not mean one compile per hover."
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

(defn- close-quietly [x]
  (when x (try (.close ^java.io.Closeable x) (catch Exception _ nil))))

(defn- kill!
  "Tears down a half-built analyzer: the streams first, so a read blocked on
   the child unwinds, then the child itself."
  [proc in out]
  (close-quietly in)
  (close-quietly out)
  (when proc (try (p/destroy-tree proc) (catch Exception _ nil))))

(defn start!
  "Spawns an analyzer for `root` and reads its startup banner.

   Throws when the helper does not come up. A child that died during
   `dart run` -- no `dart` on PATH, a helper that will not compile, a root
   with no package config -- answers the banner read with EOF, and a record
   built on that is a broken analyzer that nothing downstream would notice."
  [root]
  (let [proc (p/process ["dart" "run" (helper-path) root]
                        {:in :stream :out :stream :err :inherit})
        out  (java.io.PushbackReader. (io/reader (:out proc)))
        in   (io/writer (:in proc))
        fail (fn [msg cause]
               (kill! proc in out)
               (throw (ex-info msg {:root root :helper (helper-path)} cause)))
        banner (try (edn/read {:eof nil} out)
                    (catch Exception e
                      (fail (str "the analyzer for " root
                                 " wrote an unreadable startup banner")
                            e)))]
    (when-not (map? banner)
      (fail (str "the analyzer for " root " died before it announced itself"
                 " -- check that `dart` is on PATH and that `dart run "
                 (helper-path) " " root "` runs")
            nil))
    {:root       root
     :proc       proc
     :lock       (Object.)
     :in         in
     :out        out
     :banner     banner
     :started-at (System/currentTimeMillis)
     :cache      (atom {})}))

(defn stop! [a]
  (when a
    (kill! (:proc a) (:in a) (:out a))))

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
;;
;; root -> {:delay      a delay over (start! root) -- forcing it is the only
;;                      place a process is spawned, so concurrent callers
;;                      share one `dart run` instead of racing to spawn
;;          :failures   consecutive bad starts, driving the backoff
;;          :created-at when this attempt was registered}

(defonce ^:private analyzers (atom {}))

(def ^:private retry-base-ms 1000)
(def ^:private retry-max-ms 60000)

;; A helper that served this long before dying was healthy, not a bad start,
;; so it earns an immediate replacement rather than a backed-off one.
(def ^:private healthy-uptime-ms 30000)

(defn- retry-delay-ms
  "How long a root waits between start attempts: 0, 1s, 2s, 4s ... capped."
  [failures]
  (if (pos? failures)
    (min retry-max-ms (bit-shift-left retry-base-ms (min 6 (dec failures))))
    0))

(defn- entry-analyzer
  "The analyzer an already-forced entry produced, or nil when it has not been
   forced yet or its start threw. Never blocks."
  [e]
  (when (and e (realized? (:delay e)))
    (try @(:delay e) (catch Exception _ nil))))

(defn- spent?
  "True when `e` has been forced and what it produced is gone -- the start
   threw, or the helper it started is dead. An unforced entry is never spent:
   another thread is inside `dart run` and everyone waits on that one delay."
  [e]
  (and (realized? (:delay e))
       (not (alive? (entry-analyzer e)))))

(defn- new-entry [root failures now]
  {:delay (delay (start! root)) :failures failures :created-at now})

(defn- next-failures
  "A helper that ran a while and then died gets a free restart; one that never
   started, or died young, counts against the backoff."
  [e now]
  (let [a (entry-analyzer e)]
    (if (and a (>= (- now (:started-at a)) healthy-uptime-ms))
      0
      (inc (:failures e)))))

(defn- refresh
  "One root's registry step. Pure -- `swap!` retries it under contention, and
   spawning here (rather than in the delay) is exactly how that turns into
   duplicate Dart processes."
  [e root now]
  (cond
    (nil? e)   (new-entry root 0 now)
    (not (spent? e)) e
    ;; Spent, but too soon to try again: hand the spent entry back so the
    ;; caller sees the original failure instead of paying for another
    ;; `dart run` on every request.
    (< now (+ (:created-at e) (retry-delay-ms (:failures e)))) e
    :else (new-entry root (next-failures e now) now)))

(defn for-root
  "The analyzer for `root`, started on first use and restarted when it dies.

   Throws when the helper cannot be started, and keeps throwing that failure
   for the length of the backoff window rather than respawning per request."
  [root]
  (let [now       (System/currentTimeMillis)
        [old new] (swap-vals! analyzers update root refresh root now)
        e         (get new root)
        replaced  (get old root)]
    ;; Exactly one thread wins the CAS that installed `e`, so exactly one
    ;; stops the helper it displaced.
    (when-not (identical? replaced e)
      (stop! (entry-analyzer replaced)))
    (let [a @(:delay e)]                 ; the single-flight `dart run`
      (if (alive? a)
        a
        (throw (ex-info (str "the analyzer for " root " is no longer running")
                        {:root root}))))))

(defn for-file
  "The analyzer owning `file`, or nil when the file is in no Dart project."
  [file]
  (some-> (project-root file) for-root))

(defn shutdown-all! []
  (let [[old _] (reset-vals! analyzers {})]
    (run! #(stop! (entry-analyzer %)) (vals old))))

(defn clear-all-caches! []
  (run! clear-cache! (keep entry-analyzer (vals @analyzers))))
