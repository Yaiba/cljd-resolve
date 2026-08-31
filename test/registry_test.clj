#!/usr/bin/env bb
;; The analyzer registry's supervision rules (cljd-resolve-1sf.2).
;;
;;   bb test/registry_test.clj
;;
;; No Dart and no Flutter project: `start!` / `alive?` / `stop!` are stubbed,
;; so what is under test is purely the registry -- one spawn per root under
;; concurrency, a dead helper stopped before it is replaced, a broken helper
;; backed off instead of recompiled on every request, and a helper that is
;; alive but never answers timed out and replaced (cljd-resolve-1sf.3).
;;
;; Two things below are not registry logic and say so where they start: the
;; `elt` cache's freshness rule (cljd-resolve-1sf.8), and the two places a
;; real child is needed -- a start whose banner never comes
;; (cljd-resolve-1sf.12) and a teardown that has to unwind a blocked read.

(require '[babashka.process :as p]
         '[cljd-resolve.analyzer :as an]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def failures (atom 0))

(defn check [ok? label & [extra]]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label (if extra (str "-- " (pr-str extra)) "")))))

;; ------------------------------------------------------------------- stubs

(def started (atom []))                       ; every fake helper ever spawned
(def stopped (atom []))

(defn fake
  "A stand-in analyzer, shaped like the record `start!` builds. `:up` is
   flipped to fake the child dying; `:healthy` is what the registry itself
   flips when it gives up on a helper that stopped answering."
  [root & {:keys [age-ms] :or {age-ms 0}}]
  {:root       root
   :up         (atom true)
   :healthy    (atom true)
   :lock       (Object.)
   :cache      (atom {})
   :started-at (- (System/currentTimeMillis) age-ms)})

(defn- spawning
  "A `start!` stub: records the call and returns whatever `f` makes of it."
  [f]
  (fn [root]
    (swap! started conj root)
    (f root)))

(defn- alive-stub [a] (boolean (and a @(:up a))))

(defn- stop-stub
  "Models `stop!` honestly: it marks the generation unusable and issues the
   teardown, but it does NOT make the child vanish. Destroying a process is
   not instantaneous, so a stopped helper stays `alive?` for a while --
   tests that want it reaped flip `:up` themselves."
  [a]
  (when a
    (reset! (:healthy a) false)
    (swap! stopped conj a)))

(defn reset-registry! []
  (with-redefs [an/stop! stop-stub] (an/shutdown-all!))
  (reset! started [])
  (reset! stopped []))

(defmacro with-helper
  "Runs `body` with the process layer stubbed out by `start-fn`."
  [start-fn & body]
  `(with-redefs [an/start! (spawning ~start-fn)
                 an/alive? alive-stub
                 an/stop!  stop-stub]
     ~@body))

(defn- caught
  "The message of whatever `f` throws, or nil when it returns."
  [f]
  (try (f) nil (catch Exception e (ex-message e))))

;; ------------------------------------------------ a healthy helper is reused

(println "\na live helper is started once and reused")
(reset-registry!)
(with-helper fake
  (let [a (an/for-root "/p")
        b (an/for-root "/p")]
    (check (identical? a b) "the same analyzer comes back")
    (check (= ["/p"] @started) "one spawn for two requests" @started)
    (check (empty? @stopped) "nothing was stopped" @stopped))
  (an/for-root "/q")
  (check (= ["/p" "/q"] @started) "a second root gets its own helper" @started))

;; ------------------------------------------------------------ single-flight

(println "\nconcurrent first use spawns one helper, not one per caller")
(reset-registry!)
(with-helper (fn [root] (Thread/sleep 150) (fake root))
  (let [threads (mapv (fn [_] (future (an/for-root "/p"))) (range 8))
        got     (mapv deref threads)]
    (check (= 1 (count @started)) "exactly one `dart run`" @started)
    (check (apply = got) "every caller got the same analyzer")))

;; ------------------------------------------------------ a dead helper is reaped

(println "\na helper that dies is stopped, then replaced once")
(reset-registry!)
(with-helper fake
  (let [a (an/for-root "/p")]
    (reset! (:up a) false)                    ; the child exited
    (let [b (an/for-root "/p")
          c (an/for-root "/p")]
      (check (not (identical? a b)) "a fresh analyzer replaced the dead one")
      (check (identical? b c) "and the replacement is then reused")
      (check (= 2 (count @started)) "two spawns in total" @started)
      (check (= [a] @stopped) "the dead analyzer was stop!ed before replacement"
             (mapv :root @stopped)))))

;; ------------------------------------------------------- a start that fails

(println "\na helper that never announces itself surfaces an error, then backs off")
(reset-registry!)
(with-helper (fn [root] (throw (ex-info (str "no dart for " root) {})))
  (let [msgs (mapv (fn [_] (caught #(an/for-root "/p"))) (range 25))]
    (check (every? #(some-> % (str/includes? "no dart")) msgs)
           "every request reports the start failure" (first msgs))
    (check (= 2 (count @started))
           "25 requests cost 2 start attempts, not 25" (count @started))))

(println "\nthe backoff window opens again once it has elapsed")
(reset-registry!)
(with-redefs [an/start! (spawning (fn [root] (throw (ex-info "boom" {}))))
              an/alive? alive-stub
              an/stop!  stop-stub]
  (dotimes [_ 5] (caught #(an/for-root "/p")))
  (let [n @started]
    (Thread/sleep 1100)                       ; the failures=1 window is 1s
    (caught #(an/for-root "/p"))
    (check (= (inc (count n)) (count @started))
           "one more attempt after the window" [(count n) (count @started)])))

;; --------------------------------------- a helper that dies young also backs off

(println "\na helper that starts then dies immediately is backed off too")
(reset-registry!)
(with-helper (fn [root] (let [a (fake root)] (reset! (:up a) false) a))
  (let [msgs (mapv (fn [_] (caught #(an/for-root "/p"))) (range 25))]
    (check (every? #(some-> % (str/includes? "no longer usable")) msgs)
           "every request reports the dead helper" (first msgs))
    (check (= 2 (count @started)) "a crash loop does not respawn per request"
           (count @started))))

(println "\na helper that served a while gets an immediate replacement")
(reset-registry!)
(with-helper (fn [root] (fake root :age-ms 60000))
  (let [a (an/for-root "/p")]
    (reset! (:up a) false)
    (caught #(an/for-root "/p"))
    (check (= 2 (count @started)) "no backoff after a long-lived helper died"
           (count @started))))

;; ------------------------------------------- a helper that stops answering
;;
;; The deadline path (cljd-resolve-1sf.3). `stop!` is stubbed here and so
;; never closes the fake's stream, which is not a shortcut -- it is the
;; window under test: destroying a child is not instantaneous, so a request
;; that has given up stays parked in its read for a while afterwards, and
;; what it does when it finally unwinds has to be safe.

(defn- never-answers
  "A reader that blocks until it is closed -- a helper that is alive, holds
   its pipe open, and simply never replies. `reached` is delivered once a
   request is really blocked inside it, which is how a test queues a second
   request behind the first without racing the clock."
  [gate reached]
  (proxy [java.io.Reader] []
    (read ([]      (deliver reached true) @gate -1)
          ([_]     -1)
          ([_ _ _] -1))
    (close [] (deliver gate true))))

(defn- wedged-fake [root & opts]
  (let [gate (promise) reached (promise)]
    (assoc (apply fake root opts)
           :reached reached
           :in  (java.io.StringWriter.)
           :out (java.io.PushbackReader. (never-answers gate reached)))))

(defn- answering-fake
  "A fake with one canned `true` waiting on its stream."
  [root & opts]
  (assoc (apply fake root opts)
         :in  (java.io.StringWriter.)
         :out (java.io.PushbackReader. (java.io.StringReader. "true "))))

(defn- in-turn
  "A `start!` body that hands back each of `fs` in turn, sticking on the last."
  [& fs]
  (let [n (atom -1)]
    (fn [root] ((nth fs (min (swap! n inc) (dec (count fs)))) root))))

(defn- deadlines
  "One deadline per `ask!`, in order -- so a test can make the second request
   outlive the first by construction rather than by timing."
  [& ms]
  (let [n (atom -1)]
    (fn [] (nth ms (min (swap! n inc) (dec (count ms)))))))

(println "\na helper that stops answering fails one request, not every request")
(reset-registry!)
(with-redefs [an/start! (spawning (in-turn wedged-fake answering-fake))
              an/alive? alive-stub
              an/stop!  stop-stub
              an/request-timeout-ms (constantly 200)]
  (let [a   (an/for-root "/p")
        msg (caught #(an/library? a "package:p/p.dart"))]
    (check (some-> msg (str/includes? "did not answer"))
           "the request fails on its own deadline" msg)
    (check (false? @(:healthy a)) "the wedged generation is marked unhealthy")
    (check (= [a] @stopped) "and its child is torn down" (count @stopped))
    ;; A caller still holding the old handle must be told to retry, not read
    ;; EOF off the dead child and quietly report "no such library".
    (check (some-> (caught #(an/library? a "package:p/other.dart"))
                   (str/includes? "was restarted"))
           "a stale handle fails fast instead of answering emptily")
    (let [b (an/for-root "/p")]
      (check (not (identical? a b)) "the NEXT request gets a fresh analyzer")
      (check (true? (an/library? b "package:p/p.dart")) "which answers normally")
      (check (= 2 (count @started)) "one replacement, not one per request"
             @started))))

(println "\na request that gives up late cannot evict the helper that replaced it")
(reset-registry!)
(with-redefs [an/start! (spawning (in-turn wedged-fake
                                                 #(answering-fake % :age-ms 60000)))
              an/alive? alive-stub
              an/stop!  stop-stub
              ;; The queued request gives up long after the replacement is in
              ;; the registry -- the whole point being that it must not take
              ;; the replacement down with it.
              an/request-timeout-ms (deadlines 200 1500)]
  (let [a  (an/for-root "/p")
        r1 (future (caught #(an/library? a "package:p/one.dart")))
        _  @(:reached a)                        ; r1 holds :lock and is reading
        r2 (future (caught #(an/library? a "package:p/two.dart")))
        _  @r1                                  ; r1 timed out and evicted `a`
        b  (an/for-root "/p")]                  ; the replacement
    (check (not (identical? a b)) "a fresh analyzer replaced the wedged one")
    (check (some-> @r2 (str/includes? "did not answer"))
           "the queued request gives up on its own deadline too" @r2)
    (check (identical? b (an/for-root "/p"))
           "and its late teardown leaves the replacement alone")
    (check (true? (an/library? b "package:p/two.dart"))
           "which is still answering")
    (check (= 2 (count @started)) "two spawns in total" @started)
    ;; What the guard really protects is the replacement's supervision
    ;; record. A registry entry stamped by someone else's timeout would
    ;; count this helper's whole healthy life against it, so retire `b` the
    ;; way a long-lived helper retires and check it still earns the
    ;; immediate restart rather than a backed-off one.
    (reset! (:up b) false)
    (caught #(an/for-root "/p"))
    (check (= 3 (count @started))
           "and leaves it supervised as the healthy helper it is"
           (count @started))))

;; Uptime is the trap here. These helpers have served for a minute before
;; wedging, so the credit that earns a *dead* long-lived helper an immediate
;; replacement would, unchecked, earn a wedging one a free restart every
;; time -- and each cycle costs a whole deadline of a blocked daemon.
(println "\na helper that keeps wedging is backed off, not respawned per request")
(reset-registry!)
(with-redefs [an/start! (spawning #(wedged-fake % :age-ms 60000))
              an/alive? alive-stub
              an/stop!  stop-stub
              an/request-timeout-ms (constantly 50)]
  (let [msgs (mapv (fn [_] (caught #(an/library? (an/for-root "/p") "package:p/p.dart")))
                   (range 6))]
    (check (every? some? msgs) "every request fails" msgs)
    (check (= 2 (count @started))
           "6 wedged requests cost 2 start attempts, not 6" (count @started))
    (check (some-> (last msgs) (str/includes? "no longer usable"))
           "and the ones inside the backoff window say so" (last msgs))))

;; ---------------------------------------------------------- registry upkeep

(println "\nshutdown and cache clearing tolerate failed entries")
(reset-registry!)
(with-helper fake
  (an/for-root "/p")
  (with-redefs [an/start! (spawning (fn [_] (throw (ex-info "boom" {}))))]
    (caught #(an/for-root "/q")))             ; /q's entry holds a failure
  (check (nil? (caught an/clear-all-caches!)) "clear-all-caches! skips the failure")
  (check (nil? (caught an/shutdown-all!)) "shutdown-all! skips the failure")
  (check (= 1 (count @stopped)) "the one live helper was stopped" (count @stopped))
  (an/for-root "/p")
  (check (= 3 (count @started)) "the registry was emptied by shutdown" @started))

;; ------------------------------------------- a locally edited Dart source
;;
;; The cache's freshness rule (cljd-resolve-1sf.8). The helper re-reads the
;; project's own libraries on every `elt` and marks those answers
;; `:local-lib`; the cache here was the only thing still handing back the old
;; one. So a local answer is re-asked once one of its files moves, and an SDK
;; answer -- no `:local-lib` -- is not, however much its file is touched.
;;
;; Each fake's stdout carries its answers in order, so which one comes back is
;; how these tell a cache hit from a re-ask.

(defn- canned-fake
  "A fake with `answers` queued on its stream, one per `ask!`."
  [root & answers]
  (assoc (fake root)
         :in  (java.io.StringWriter.)
         :out (java.io.PushbackReader.
               (java.io.StringReader. (str/join " " (map pr-str answers))))))

(defn- tmp-dart
  "A real file to hang a modification time on."
  [at]
  (doto (java.io.File/createTempFile "cljd-resolve" ".dart")
    .deleteOnExit
    (.setLastModified at)))

(def ^:private t0 1000000000000)                ; any two distinct mtimes
(def ^:private t1 1000000060000)

(println "\nan edited local source is re-asked, an SDK one is not")
(let [f     (tmp-dart t0)
      path  (.getPath f)
      local (fn [doc] {:local-lib true :file path :doc doc})
      ;; the same file, minus the helper's local marker: an SDK or pub answer
      ;; is session-cached even if something does touch what it came from
      sdk   (fn [doc] {:file path :doc doc})
      a     (canned-fake "/p" (local "old") (sdk "sdk") (local "new"))
      doc   (fn [lib name] (:doc (an/element a lib name)))]
  (check (= "old" (doc "package:p/p.dart" "Label")) "the first hover asks")
  (check (= "old" (doc "package:p/p.dart" "Label")) "the second is a cache hit")
  (check (= "sdk" (doc "dart:core" "String")) "and an SDK answer is cached too")
  (.setLastModified f t1)                       ; the user saved the file
  (check (= "new" (doc "package:p/p.dart" "Label"))
         "the edited local source is re-asked")
  (check (= "sdk" (doc "dart:core" "String"))
         "while the SDK entry rides out the same edit"))

;; The narrow case the issue singles out: `find-in-class` hands back the
;; MEMBER, and a member declared in a part file carries a `:file` of its own.
;; Stamping only the class's own file would miss an edit to the part.
(println "\na member declared in a part file is stamped too")
(let [main (tmp-dart t0)
      part (tmp-dart t0)
      cls  (fn [doc] {:local-lib true :file (.getPath main) :kind :class
                      "build" {:kind :method :file (.getPath part) :doc doc}})
      a    (canned-fake "/p" (cls "old") (cls "new"))
      doc  (fn [] (get-in (an/element a "package:p/p.dart" "Panel") ["build" :doc]))]
  (check (= "old" (doc)) "the class map comes back")
  (.setLastModified part t1)                    ; only the part file changed
  (check (= "new" (doc)) "editing the part file alone still invalidates it"))

;; A missing file reads as mtime 0. Taken as a change it is a change that
;; never ends -- every hover would re-ask for a file no answer can come from.
(println "\na deleted local source is not re-asked on every hover")
(let [f   (tmp-dart t0)
      a   (canned-fake "/p" {:local-lib true :file (.getPath f) :doc "old"}
                            {:local-lib true :file (.getPath f) :doc "re-asked"})
      doc (fn [] (:doc (an/element a "package:p/p.dart" "Label")))]
  (check (= "old" (doc)) "the answer is cached")
  (.delete f)
  (check (= "old" (doc)) "a gone file is not a change")
  (check (= "old" (doc)) "and still is not on the next hover"))

;; ------------------------------------------ the real process layer
;;
;; The last two checks are the parts of the deadline path that are NOT
;; registry logic, so the stubs above cannot cover them: what `start!` and
;; `stop!` do to a real child. Any spawnable long-lived process will do; no
;; Dart involved.

(def have-sh?
  (try (zero? (:exit @(p/process ["sh" "-c" "exit 0"]))) (catch Exception _ false)))

;; ---------------------------------- a helper that never announces itself
;;
;; The banner read (cljd-resolve-1sf.12). A child that DIES answers the
;; banner read with EOF and was already covered; one that stays alive and
;; simply never speaks -- a wedged `dart run`, a pub solve waiting on the
;; network -- used to park `start!` forever, and since the registry entry is
;; an unforced delay every caller for that root parked behind it. So the
;; start has to fail on its own deadline, and once it has, the next requests
;; have to be backed off exactly like any other bad start.

(def ^:private real-process p/process)

(defn- silent-child
  "A `p/process` stand-in: whatever it is asked to run, it spawns a child
   that holds its pipes open and never writes a banner."
  [spawns]
  (fn [_cmd opts]
    (swap! spawns inc)
    (real-process ["sh" "-c" "while true; do sleep 1; done"] opts)))

(if-not have-sh?
  (println "\nno `sh`; skipping the banner-deadline check")
  (do
    (println "\na helper that never announces itself fails the start on a deadline")
    (reset-registry!)
    (let [spawns (atom 0)]
      (with-redefs [p/process (silent-child spawns)
                    an/request-timeout-ms (constantly 400)]
        (let [t0   (System/currentTimeMillis)
              msg  (deref (future (caught #(an/for-root "/p"))) 10000 ::hung)
              took (- (System/currentTimeMillis) t0)]
          (check (not= ::hung msg) "the start returns instead of hanging the caller")
          (check (and (string? msg) (str/includes? msg "did not announce itself"))
                 "and says the banner never came" msg)
          (check (< took 5000) "on the deadline, not on `forever`" took)
          ;; The child is destroyed by the failing start, which is also what
          ;; unwinds the read still parked on its stdout.
          (check (= 1 @spawns) "one `dart run` for the first request" @spawns)
          (let [msgs (mapv (fn [_] (caught #(an/for-root "/p"))) (range 6))]
            (check (every? #(some-> % (str/includes? "did not announce itself")) msgs)
                   "every later request repeats the failure" (first msgs))
            ;; failures=1 opens a 1s window, so only the request that turned
            ;; the first failure into a backoff pays for another spawn.
            (check (= 2 @spawns)
                   "7 requests cost 2 start attempts, not 7" @spawns)))))))

;; ------------------------------------------- tearing down a blocked read
;;
;; `stop!` has to return even while a request is parked in `edn/read` on the
;; helper's stdout -- and it only does because it destroys the child BEFORE
;; closing that reader. `PushbackReader.read` holds the reader's own monitor
;; and `.close` wants the same one, so closing first deadlocks the closer
;; instead of freeing the reader, and the daemon thread that was trying to
;; recover hangs alongside the one it was recovering. Destroying the child is
;; what unwinds the read.

(if-not have-sh?
  (println "\nno `sh`; skipping the teardown-unwinds-a-blocked-read check")
  (do
    (println "\nstop! returns while a request is still blocked reading the child")
    (let [proc (p/process ["sh" "-c" "while true; do sleep 1; done"]
                          {:in :stream :out :stream :err :inherit})
          ;; shaped by hand rather than by `start!`: this child speaks no
          ;; banner, which is the point -- it never says anything at all
          a    {:root       "/p"
                :proc       proc
                :lock       (Object.)
                :healthy    (atom true)
                :cache      (atom {})
                :started-at (System/currentTimeMillis)
                :in         (io/writer (:in proc))
                :out        (java.io.PushbackReader. (io/reader (:out proc)))}
          pending (future (edn/read {:eof :eof} (:out a)))]
      (check (= ::blocked (deref pending 300 ::blocked))
             "the request really is blocked in the read")
      (let [torn (future (an/stop! a) :done)]
        (check (= :done (deref torn 5000 ::hung))
               "stop! returns instead of deadlocking on the reader's monitor")
        (check (= :eof (deref pending 5000 ::still-blocked))
               "and the blocked read unwound")))))

;; ----------------------------------------------------------------

(println)
(if (zero? @failures)
  (println "all checks passed")
  (println @failures "check(s) FAILED"))
(System/exit (if (zero? @failures) 0 1))
