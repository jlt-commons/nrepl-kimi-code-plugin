(ns jolt-nrepl.server-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt-nrepl.server :as server]))

(deftest stdio-emits-only-responses-to-requests
  (let [input (str (json/write-str {:jsonrpc "2.0" :id 7 :method "ping"}) "\n"
                   (json/write-str {:jsonrpc "2.0" :method "notifications/initialized"}) "\n")
        output (with-out-str
                 (with-in-str input
                   (server/run-stdio [] (fn [_ _] (server/unsupported-tool-result)) (fn [] nil))))
        lines (remove str/blank? (str/split-lines output))]
    (is (= 1 (count lines)))
    (is (= 7 (:id (json/read-str (first lines) :key-fn keyword))))))

(deftest stdio-prunes-completed-request-futures
  (let [pending (atom [])
        input (str (apply str (repeat 20 (str (json/write-str {:jsonrpc "2.0" :method "notifications/initialized"}) "\n")))
                   (json/write-str {:jsonrpc "2.0" :id 8 :method "ping"}) "\n")]
    (with-out-str
      (with-in-str input
        (server/run-stdio [] (fn [_ _] (server/unsupported-tool-result)) (fn [] nil) pending)))
    (is (empty? @pending))))
