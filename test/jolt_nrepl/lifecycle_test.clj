(ns jolt-nrepl.lifecycle-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt-nrepl.client :as sut]))

(def nrepl-dep
  "jolt-lang/nrepl {:git/url \"https://github.com/jolt-lang/nrepl\" :git/tag \"v0.4.0\" :git/sha \"16558b920864e5302a5c1433a78f977bbb64a9d1\"}")

(defn wait-for-port [root]
  (let [port-file (str root "/.nrepl-port")
        deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (cond
        (fs/exists? port-file) (str/trim (slurp port-file))
        (< deadline (System/currentTimeMillis)) (throw (ex-info "nREPL server did not write its port" {}))
        :else (do (Thread/sleep 50) (recur))))))

(defn start-server [root rich? & [port]]
  (spit (str root "/deps.edn")
        (if rich?
          (str "{:paths [\"src\"] :deps {" nrepl-dep "} :nrepl/middleware [nrepl.middleware/default-middleware]}")
          "{:paths [\"src\"]}"))
  (let [proc (p/process ["jolt" "nrepl-server" (or port "0")]
                        {:dir (str root) :out :string :err :string :shutdown p/destroy-tree})]
    (try
      [proc (wait-for-port root)]
      (catch :default e
        (p/destroy-tree proc)
        (throw e)))))

(defn exception-data [f]
  (try
    (let [result (f)]
      (when-not (keyword? result) result))
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))
    (catch java.util.concurrent.ExecutionException e
      (ex-data (.getCause e)))))

(defn wait-active [state]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (:active-id @state) (:active-id @state)
        (< deadline (System/currentTimeMillis)) (throw (ex-info "eval did not become active" {}))
        :else (do (Thread/sleep 10) (recur))))))

(deftest rich-middleware-interrupts-compute-bound-eval
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-rich-interrupt"})
        state (sut/create-state)
        proc (atom nil)]
    (try
      (reset! proc (first (start-server root true)))
      (sut/connect! state {:cwd (str root)})
      (let [task (future (sut/evaluate! state {:cwd (str root) :code "(loop [] (recur))"}))]
        (wait-active state)
        (is (= :interrupt-requested (:type (sut/interrupt! state))))
        (let [result (deref task 5000 ::timeout)]
          (is (not= ::timeout result))
          (is (true? (:error? result))))
        (is (= "42" (:result (sut/evaluate! state {:cwd (str root) :code "42"})))))
      (finally
        (sut/disconnect! state)
        (when @proc (p/destroy-tree @proc))
        (fs/delete-tree root {:force true})))))

(deftest base-timeout-is-uncertain-and-never-replays
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-base-timeout"})
        state (sut/create-state)
        proc (atom nil)]
    (try
      (reset! proc (first (start-server root false)))
      (sut/evaluate! state {:cwd (str root) :code "(def attempts (atom 0))"})
      (let [data (exception-data
                  #(sut/evaluate! state {:cwd (str root)
                                         :code "(do (swap! attempts inc) (Thread/sleep 500) 99)"
                                         :timeout-ms 50}))]
        (is (= :timeout (:type data)))
        (is (false? (:interrupted? data)))
        (is (false? (:certain? data))))
      (is (false? (:connected? (sut/status state))))
      (is (= "1" (:result (sut/evaluate! state {:cwd (str root) :code "@attempts"}))))
      (finally
        (sut/disconnect! state)
        (when @proc (p/destroy-tree @proc))
        (fs/delete-tree root {:force true})))))

(deftest rich-timeout-interrupts-and-cleans-connection
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-rich-timeout"})
        state (sut/create-state)
        proc (atom nil)]
    (try
      (reset! proc (first (start-server root true)))
      (let [data (exception-data
                  #(sut/evaluate! state {:cwd (str root)
                                         :code "(loop [] (recur))"
                                         :timeout-ms 50}))]
        (is (= :timeout (:type data)))
        (is (true? (:interrupted? data)))
        (is (true? (:certain? data))))
      (is (false? (:connected? (sut/status state))))
      (is (= "42" (:result (sut/evaluate! state {:cwd (str root) :code "42"}))))
      (finally
        (sut/disconnect! state)
        (when @proc (p/destroy-tree @proc))
        (fs/delete-tree root {:force true})))))

(deftest rich-foreign-call-timeout-remains-uncertain
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-rich-foreign"})
        state (sut/create-state)
        proc (atom nil)]
    (try
      (reset! proc (first (start-server root true)))
      (let [data (exception-data
                  #(sut/evaluate! state {:cwd (str root)
                                         :code "(Thread/sleep 10000)"
                                         :timeout-ms 50}))]
        (is (= :timeout (:type data)))
        (is (true? (:interrupted? data)))
        (is (false? (:certain? data))))
      (is (false? (:connected? (sut/status state))))
      (finally
        (sut/disconnect! state)
        (when @proc (p/destroy-tree @proc))
        (fs/delete-tree root {:force true})))))

(deftest busy-calls-and-disconnect-do-not-corrupt-later-sessions
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-disconnect"})
        state (sut/create-state)
        proc (atom nil)]
    (try
      (reset! proc (first (start-server root true)))
      (sut/connect! state {:cwd (str root)})
      (let [task (future (sut/evaluate! state {:cwd (str root) :code "(loop [] (recur))"}))]
        (wait-active state)
        (is (= :busy (:type (exception-data #(sut/evaluate! state {:cwd (str root) :code "1"})))))
        (is (= :busy (:type (exception-data #(sut/connect! state {:cwd (str root)})))))
        (sut/disconnect! state)
        (is (some? (exception-data #(deref task 5000 ::timeout)))))
      (is (= "42" (:result (sut/evaluate! state {:cwd (str root) :code "42"}))))
      (finally
        (sut/disconnect! state)
        (when @proc (p/destroy-tree @proc))
        (fs/delete-tree root {:force true})))))

(deftest server-restart-does-not-replay-failed-form
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-restart"})
        state (sut/create-state)
        proc (atom nil)]
    (try
      (let [[server port] (start-server root false)]
        (reset! proc server)
        (let [task (future
                     (sut/evaluate! state {:cwd (str root)
                                           :code "(do (spit \"attempts.txt\" \"x\" :append true) (Thread/sleep 1000) 99)"}))]
          (wait-active state)
          (p/destroy-tree @proc)
          (is (some? (exception-data #(deref task 5000 ::timeout))))
          (fs/delete-if-exists (str root "/.nrepl-port"))
          (reset! proc (first (start-server root false port)))
          (is (= "1" (:result (sut/evaluate! state {:cwd (str root) :code "(count (slurp \"attempts.txt\"))"}))))))
      (finally
        (sut/disconnect! state)
        (when @proc (p/destroy-tree @proc))
        (fs/delete-tree root {:force true})))))

(deftest failed-startup-wait-destroys-spawned-server
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-startup-cleanup"})
        spawned (atom nil)
        original-process p/process]
    (try
      (with-redefs [p/process (fn [& args]
                                (let [proc (apply original-process args)]
                                  (reset! spawned proc)
                                  proc))
                    wait-for-port (fn [_] (throw (ex-info "simulated startup wait failure" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (start-server root false))))
      (is (some? @spawned))
      (is (not= ::timeout (deref @spawned 5000 ::timeout)))
      (is (false? (p/alive? @spawned)))
      (finally
        (when (and @spawned (p/alive? @spawned))
          (p/destroy-tree @spawned))
        (fs/delete-tree root {:force true})))))
