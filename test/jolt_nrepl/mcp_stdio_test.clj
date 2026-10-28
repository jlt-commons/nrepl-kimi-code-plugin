(ns jolt-nrepl.mcp-stdio-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [jolt-nrepl.json :as json]
            [jolt-nrepl.lifecycle-test :as helpers]))

(defn send-json [writer message]
  (binding [*out* writer]
    (println (json/write-str message))
    (flush)))

(defn read-json [reader]
  (let [line (deref (future (.readLine reader)) 10000 ::timeout)]
    (when (= ::timeout line)
      (throw (ex-info "timed out reading MCP response" {})))
    (when (nil? line)
      (throw (ex-info "MCP server closed stdout" {})))
    (json/read-str line :key-fn keyword)))

(defn request [writer reader id method params]
  (send-json writer (cond-> {:jsonrpc "2.0" :id id :method method}
                      params (assoc :params params)))
  (read-json reader))

(deftest real-stdio-server-exposes-nrepl-tools
  (let [base-root (fs/create-temp-dir {:prefix "jolt-nrepl-mcp-base"})
        rich-root (fs/create-temp-dir {:prefix "jolt-nrepl-mcp-rich"})
        base-proc (atom nil)
        rich-proc (atom nil)
        mcp-proc (atom nil)]
    (try
      (reset! base-proc (first (helpers/start-server base-root false)))
      (reset! mcp-proc
              (p/process ["jolt" "run" "-m" "jolt-nrepl.server"]
                         {:dir (str (fs/cwd)) :out :pipe :err :pipe :shutdown p/destroy-tree}))
      (let [writer (io/writer (:in @mcp-proc))
            reader (io/reader (:out @mcp-proc))
            initialized (request writer reader 1 "initialize" {:protocolVersion "2025-03-26"})]
        (is (= "2025-03-26" (get-in initialized [:result :protocolVersion])))
        (send-json writer {:jsonrpc "2.0" :method "notifications/initialized"})
        (let [listed (request writer reader "tools" "tools/list" nil)
              names (set (map :name (get-in listed [:result :tools])))]
          (is (= #{"nrepl_connect" "nrepl_disconnect" "nrepl_status"
                   "nrepl_eval" "nrepl_load_file" "nrepl_interrupt"}
                 names)))
        (let [invalid (request writer reader "invalid" "tools/call"
                               {:name "nrepl_eval" :arguments {:cwd (str base-root)}})]
          (is (= "invalid" (:id invalid)))
          (is (true? (get-in invalid [:result :isError]))))
        (let [evaluated (request writer reader "unicode" "tools/call"
                                 {:name "nrepl_eval"
                                  :arguments {:cwd (str base-root) :code "(str \"😀\" (+ 1 2))"}})]
          (is (= "unicode" (:id evaluated)))
          (is (= "\"😀3\"" (get-in evaluated [:result :structuredContent :result]))))
        (send-json writer {:jsonrpc "2.0" :id "slow" :method "tools/call"
                           :params {:name "nrepl_eval"
                                    :arguments {:cwd (str base-root) :code "(Thread/sleep 300)"}}})
        (send-json writer {:jsonrpc "2.0" :id "status" :method "tools/call"
                           :params {:name "nrepl_status" :arguments {}}})
        (let [responses [(read-json reader) (read-json reader)]]
          (is (= #{"slow" "status"} (set (map :id responses)))))
        (let [disconnected (request writer reader "disconnect" "tools/call"
                                    {:name "nrepl_disconnect" :arguments {}})]
          (is (true? (get-in disconnected [:result :structuredContent :disconnected?]))))
        (reset! rich-proc (first (helpers/start-server rich-root true)))
        (send-json writer {:jsonrpc "2.0" :id "rich-eval" :method "tools/call"
                           :params {:name "nrepl_eval"
                                    :arguments {:cwd (str rich-root) :code "(loop [] (recur))"}}})
        (Thread/sleep 100)
        (send-json writer {:jsonrpc "2.0" :id "rich-interrupt" :method "tools/call"
                           :params {:name "nrepl_interrupt" :arguments {}}})
        (let [responses [(read-json reader) (read-json reader)]
              interrupt-response (first (filter #(= "rich-interrupt" (:id %)) responses))]
          (is (= #{"rich-eval" "rich-interrupt"} (set (map :id responses))))
          (is (= "interrupt-requested"
                 (get-in interrupt-response [:result :structuredContent :type]))))
        (let [follow-up (request writer reader "follow-up" "tools/call"
                                 {:name "nrepl_eval" :arguments {:cwd (str rich-root) :code "42"}})]
          (is (= "42" (get-in follow-up [:result :structuredContent :result]))))
        (.close (:in @mcp-proc))
        (is (not= ::timeout (deref @mcp-proc 10000 ::timeout))))
      (finally
        (when @mcp-proc (p/destroy-tree @mcp-proc))
        (when @base-proc (p/destroy-tree @base-proc))
        (when @rich-proc (p/destroy-tree @rich-proc))
        (fs/delete-tree base-root {:force true})
        (fs/delete-tree rich-root {:force true})))))
