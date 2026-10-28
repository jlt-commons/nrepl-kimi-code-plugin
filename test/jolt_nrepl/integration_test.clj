(ns jolt-nrepl.integration-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt-nrepl.client :as sut]))

(defn wait-for-port [root]
  (let [port-file (str root "/.nrepl-port")
        deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (cond
        (fs/exists? port-file) (str/trim (slurp port-file))
        (< deadline (System/currentTimeMillis)) (throw (ex-info "nREPL server did not write its port" {}))
        :else (do (Thread/sleep 50) (recur))))))

(deftest built-in-server-supports-persistent-eval-and-load-file
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-base"})]
    (spit (str root "/deps.edn") "{:paths [\"src\"]}")
    (let [proc (p/process ["jolt" "nrepl-server" "0"]
                          {:dir (str root) :out :string :err :string :shutdown p/destroy-tree})
          state (sut/create-state)]
      (try
        (let [port (wait-for-port root)]
          (is (pos? (parse-long port)))
          (is (= "6" (:result (sut/evaluate! state {:cwd (str root) :code "(+ 1 2 3)"}))))
          (is (true? (:connected? (sut/status state))))
          (is (false? (:interrupt? (sut/status state))))
          (is (= "#'user/answer" (:result (sut/evaluate! state {:cwd (str root) :code "(def answer 42)"}))))
          (is (= "42" (:result (sut/evaluate! state {:cwd (str root) :code "answer"}))))
          (let [printed (sut/evaluate! state {:cwd (str root) :code "(do (println \"hello\") (+ 1 2))"})]
            (is (= "3" (:result printed)))
            (is (str/includes? (:out printed) "hello")))
          (let [printed-err (sut/evaluate! state {:cwd (str root) :code "(binding [*out* *err*] (println \"oops\"))"})]
            (is (str/includes? (:err printed-err) "oops")))
          (is (= "\"😀3\"" (:result (sut/evaluate! state {:cwd (str root) :code "(str \"😀\" (+ 1 2))"}))))
          (let [namespaced (sut/evaluate! state {:cwd (str root) :code "(ns demo.scratch)"})]
            (is (= "demo.scratch" (:ns namespaced))))
          (sut/evaluate! state {:cwd (str root) :code "(in-ns 'user)"})
          (let [repaired (sut/evaluate! state {:cwd (str root) :code "(+ 40 2"})]
            (is (= "42" (:result repaired)))
            (is (= "(+ 40 2)" (:repaired repaired))))
          (let [failed (sut/evaluate! state {:cwd (str root) :code "(throw (ex-info \"deliberate\" {}))"})]
            (is (true? (:error? failed)))
            (is (str/includes? (str failed) "deliberate")))
          (is (= "43" (:result (sut/evaluate! state {:cwd (str root) :code "(+ answer 1)"}))))
          (fs/create-dirs (str root "/src/demo"))
          (spit (str root "/src/demo/loaded.clj")
                "(ns demo.loaded)\n(defn twice [x] (* 2 x))\n")
          (sut/load-file! state {:cwd (str root) :file "src/demo/loaded.clj"})
          (is (= "42" (:result (sut/evaluate! state {:cwd (str root) :code "(demo.loaded/twice 21)"}))))
          (sut/disconnect! state)
          (is (false? (:connected? (sut/status state))))
          (is (true? (:connected? (sut/connect! state {:cwd (str root) :host "127.0.0.1" :port (parse-long port)}))))
          (is (= :interrupt-unsupported (:type (sut/interrupt! state)))))
        (finally
          (sut/disconnect! state)
          (p/destroy-tree proc)
          (fs/delete-tree root {:force true}))))))
