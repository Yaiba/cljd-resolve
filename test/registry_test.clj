#!/usr/bin/env bb
;; The analyzer registry's supervision rules (cljd-resolve-1sf.2).
;;
;;   bb test/registry_test.clj
;;
;; No Dart and no Flutter project: `start!` / `alive?` / `stop!` are stubbed,
;; so what is under test is purely the registry -- one spawn per root under
;; concurrency, a dead helper stopped before it is replaced, and a broken
;; helper backed off instead of recompiled on every request.

(require '[cljd-resolve.analyzer :as an]
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
  "A stand-in analyzer. `:up` is flipped to fake a helper dying."
  [root & {:keys [age-ms] :or {age-ms 0}}]
  {:root root :up (atom true) :started-at (- (System/currentTimeMillis) age-ms)})

(defn- spawning
  "A `start!` stub: records the call and returns whatever `f` makes of it."
  [f]
  (fn [root]
    (swap! started conj root)
    (f root)))

(defn- alive-stub [a] (boolean (and a @(:up a))))
(defn- stop-stub  [a] (when a (reset! (:up a) false) (swap! stopped conj a)))

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
    (check (every? #(some-> % (str/includes? "no longer running")) msgs)
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

;; ----------------------------------------------------------------

(println)
(if (zero? @failures)
  (println "all checks passed")
  (println @failures "check(s) FAILED"))
(System/exit (if (zero? @failures) 0 1))
