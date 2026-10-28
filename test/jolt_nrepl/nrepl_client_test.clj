(ns jolt-nrepl.nrepl-client-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [jolt-nrepl.client :as sut]))

(defn exception-type [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:type (ex-data e)))))

(deftest validates-cwd-port-and-timeouts-before-connecting
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-validation"})]
    (try
      (is (= :invalid-cwd (exception-type #(sut/evaluate! (sut/create-state) {:cwd "relative" :code "1"}))))
      (is (= :missing-port (exception-type #(sut/connect! (sut/create-state) {:cwd (str root)}))))
      (spit (str root "/.nrepl-port") "not-a-number\n")
      (is (= :invalid-port (exception-type #(sut/connect! (sut/create-state) {:cwd (str root)}))))
      (fs/delete (str root "/.nrepl-port"))
      (doseq [timeout [0 -1 300001]]
        (is (= :invalid-timeout
               (exception-type #(sut/evaluate! (sut/create-state)
                                               {:cwd (str root) :code "1" :timeout-ms timeout})))))
      (finally
        (fs/delete-tree root {:force true})))))

(deftest rejects-project-mismatch-and-busy-connect
  (let [root-a (fs/create-temp-dir {:prefix "jolt-nrepl-a"})
        root-b (fs/create-temp-dir {:prefix "jolt-nrepl-b"})
        state (sut/create-state)]
    (try
      (reset! state {:connected? true :busy? false :cwd (str (fs/real-path root-b))})
      (is (= :project-mismatch
             (exception-type #(sut/evaluate! state {:cwd (str root-a) :code "1"}))))
      (reset! state {:connected? true :busy? true :cwd (str (fs/real-path root-b))})
      (is (= :busy (exception-type #(sut/connect! state {:cwd (str root-a) :port 7888}))))
      (finally
        (fs/delete-tree root-a {:force true})
        (fs/delete-tree root-b {:force true})))))

(deftest load-file-cannot-escape-real-project-root
  (let [root (fs/create-temp-dir {:prefix "jolt-nrepl-root"})
        outside (fs/create-temp-file {:prefix "jolt-nrepl-outside" :suffix ".clj"})]
    (try
      (spit (str root "/inside.clj") "(+ 1 2)")
      (fs/create-sym-link (str root "/escape.clj") (str outside))
      (is (= :path-escape
             (exception-type #(sut/load-file! (sut/create-state)
                                              {:cwd (str root)
                                               :file (str "../" (fs/file-name outside))}))))
      (is (= :path-escape
             (exception-type #(sut/load-file! (sut/create-state) {:cwd (str root) :file "escape.clj"}))))
      (is (= :missing-port
             (exception-type #(sut/load-file! (sut/create-state) {:cwd (str root) :file "inside.clj"}))))
      (finally
        (fs/delete-tree root {:force true})
        (fs/delete outside)))))
