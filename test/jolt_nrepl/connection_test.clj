(ns jolt-nrepl.connection-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [jolt-nrepl.client :as sut]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport]))

(defn exception-data [f]
  (try
    (let [result (f)]
      (when-not (keyword? result) result))
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))
    (catch java.util.concurrent.ExecutionException e
      (ex-data (.getCause e)))))

(defn temp-root []
  (str (fs/real-path (fs/create-temp-dir {:prefix "jolt-nrepl-connection"}))))

(defn wait-active [state]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (:active-id @state) (:active-id @state)
        (< deadline (System/currentTimeMillis)) (throw (ex-info "eval did not become active" {}))
        :else (do (Thread/sleep 10) (recur))))))

(deftest failed-handshake-closes-the-new-transport
  (let [root (temp-root)
        state (sut/create-state)
        fake {:fd 42}
        closes (atom [])]
    (try
      (with-redefs [nrepl/connect (fn [_ _] fake)
                    nrepl/new-session (fn [_] (throw (ex-info "clone failed" {})))
                    nrepl/close (fn [transport] (swap! closes conj transport))]
        (is (some? (exception-data #(sut/connect! state {:cwd root :port 7888}))))
        (is (= [fake] @closes))
        (is (false? (:connected? (sut/status state)))))
      (finally (fs/delete-tree root {:force true})))))

(deftest handshake-has-a-timeout-and-cleans-up
  (let [root (temp-root)
        state (sut/create-state)
        fake {:fd 43}
        closes (atom [])]
    (try
      (with-redefs [nrepl/connect (fn [_ _] fake)
                    nrepl/new-session (fn [_] (Thread/sleep 500) "session")
                    nrepl/message (fn [_ _] [{:ops {}}])
                    nrepl/close (fn [transport] (swap! closes conj transport))]
        (let [started (System/currentTimeMillis)
              data (exception-data #(sut/connect! state {:cwd root :port 7888 :timeout-ms 20}))]
          (is (= :connection-timeout (:type data)))
          (is (< (- (System/currentTimeMillis) started) 400)))
        (is (= [fake] @closes))
        (is (false? (:connected? (sut/status state)))))
      (finally (fs/delete-tree root {:force true})))))

(deftest disconnect-during-connect-cannot-resurrect-connection
  (let [root (temp-root)
        state (sut/create-state)
        fake {:fd 44}
        entered (promise)
        finish (promise)
        closes (atom [])]
    (try
      (with-redefs [nrepl/connect (fn [_ _] fake)
                    nrepl/new-session (fn [_] (deliver entered true) (deref finish 1000 nil) "session")
                    nrepl/message (fn [_ _] [{:ops {}}])
                    nrepl/close (fn [transport] (swap! closes conj transport))]
        (let [task (future (sut/connect! state {:cwd root :port 7888}))]
          (deref entered 1000 nil)
          (sut/disconnect! state)
          (deliver finish true)
          (is (some? (exception-data #(deref task 2000 ::timeout))))
          (is (false? (:connected? (sut/status state))))
          (is (= [fake] @closes))))
      (finally (fs/delete-tree root {:force true})))))

(deftest eof-during-interrupt-grace-is-not-certain-termination
  (let [root (temp-root)
        fake {:fd 45}
        state (atom {:connected? true :busy? false :cwd root :host "127.0.0.1"
                     :port 7888 :transport fake :session "session" :ops #{:interrupt}})
        finish (promise)
        closes (atom [])]
    (try
      (with-redefs [nrepl/message (fn [_ _] (deref finish 1000 nil) [])
                    transport/send (fn [_ message]
                                     (when (= "interrupt" (:op message))
                                       (deliver finish true)))
                    nrepl/close (fn [transport] (swap! closes conj transport))]
        (let [data (exception-data #(sut/evaluate! state {:cwd root :code "1" :timeout-ms 20}))]
          (is (= :timeout (:type data)))
          (is (true? (:interrupted? data)))
          (is (false? (:certain? data))))
        (is (= [fake] @closes)))
      (finally (fs/delete-tree root {:force true})))))

(deftest concurrent-timeout-and-disconnect-close-transport-once
  (let [root (temp-root)
        fake {:fd 46}
        state (atom {:connected? true :busy? false :cwd root :host "127.0.0.1"
                     :port 7888 :transport fake :session "session" :ops #{:interrupt}})
        never (promise)
        closes (atom [])]
    (try
      (with-redefs [nrepl/message (fn [_ _] @never)
                    transport/send (fn [_ _] nil)
                    nrepl/close (fn [transport]
                                  (swap! closes conj transport)
                                  (Thread/sleep 100))]
        (let [task (future (sut/evaluate! state {:cwd root :code "1" :timeout-ms 50}))]
          (wait-active state)
          (Thread/sleep 25)
          (let [disconnect-task (future (sut/disconnect! state))]
            (exception-data #(deref task 2000 ::timeout))
            (deref disconnect-task 2000 ::timeout)))
        (is (= [fake] @closes)))
      (finally (fs/delete-tree root {:force true})))))
