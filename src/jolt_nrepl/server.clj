(ns jolt-nrepl.server
  (:require [clojure.string :as str]
            [jolt-nrepl.client :as client]
            [jolt-nrepl.json :as json]
            [jolt-nrepl.mcp :as mcp]))

(def connection-state (client/create-state))

(def ^:private cwd-schema
  {:type "string" :description "Absolute path to the Jolt project root"})

(def ^:private timeout-schema
  {:type "integer" :minimum 1 :maximum 300000 :default 120000})

(def tool-definitions
  [{:name "nrepl_connect"
    :description "Connect to a Jolt nREPL server, using .nrepl-port when port is omitted"
    :inputSchema {:type "object"
                  :properties {:cwd cwd-schema
                               :host {:type "string" :default "127.0.0.1"}
                               :port {:type "integer" :minimum 1 :maximum 65535}}
                  :required ["cwd"]}}
   {:name "nrepl_disconnect"
    :description "Close the current nREPL session and TCP connection"
    :inputSchema {:type "object" :properties {}}}
   {:name "nrepl_status"
    :description "Show the current nREPL connection and interrupt capability"
    :inputSchema {:type "object" :properties {}}}
   {:name "nrepl_eval"
    :description "Evaluate Clojure code in the persistent Jolt nREPL session"
    :inputSchema {:type "object"
                  :properties {:cwd cwd-schema
                               :code {:type "string" :description "Clojure code to evaluate"}
                               :timeoutMs timeout-schema}
                  :required ["cwd" "code"]}}
   {:name "nrepl_load_file"
    :description "Load a source file from the Jolt project into the persistent nREPL session"
    :inputSchema {:type "object"
                  :properties {:cwd cwd-schema
                               :file {:type "string" :description "File path relative to the project root"}
                               :timeoutMs timeout-schema}
                  :required ["cwd" "file"]}}
   {:name "nrepl_interrupt"
    :description "Interrupt the active eval when the server advertises interrupt support"
    :inputSchema {:type "object" :properties {}}}])

(defn unsupported-tool-result []
  (mcp/tool-result {:error "nREPL tools are not configured"} :error? true))

(defn- argument [args key]
  (if (contains? args key) (get args key) (get args (name key))))

(defn- evaluation-text [result]
  (let [{:keys [result out err ns repaired]} result
        sections (cond-> []
                   (not (str/blank? out)) (conj (str "stdout:\n" out))
                   (not (str/blank? err)) (conj (str "stderr:\n" err))
                   (not (str/blank? result)) (conj (str "=> " result))
                   (not (str/blank? ns)) (conj (str "current ns: " ns))
                   repaired (conj (str "auto-repaired delimiters:\n" repaired)))]
    (if (seq sections) (str/join "\n" sections) "nil")))

(defn- data-result [data]
  (mcp/tool-result data
                   :error? (boolean (:error? data))
                   :text (if (contains? data :result)
                           (evaluation-text data)
                           (json/write-str data))))

(defn- exception-result [e]
  (let [data (merge {:error (.getMessage e)} (ex-data e))
        text (if (= :timeout (:type data))
               (str (.getMessage e)
                    "\ninterrupt requested: " (boolean (:interrupted? data))
                    "\nserver-side termination certain: " (boolean (:certain? data)))
               (.getMessage e))]
    (mcp/tool-result data :error? true :text text)))

(defn- call-tool [tool-name args]
  (try
    (let [cwd (argument args :cwd)
          timeout-ms (argument args :timeoutMs)
          result (case tool-name
                   "nrepl_connect" (client/connect! connection-state
                                                    {:cwd cwd
                                                     :host (argument args :host)
                                                     :port (argument args :port)})
                   "nrepl_disconnect" (client/disconnect! connection-state)
                   "nrepl_status" (client/status connection-state)
                   "nrepl_eval" (client/evaluate! connection-state
                                                  {:cwd cwd
                                                   :code (argument args :code)
                                                   :timeout-ms timeout-ms})
                   "nrepl_load_file" (client/load-file! connection-state
                                                        {:cwd cwd
                                                         :file (argument args :file)
                                                         :timeout-ms timeout-ms})
                   "nrepl_interrupt" (client/interrupt! connection-state))]
      (data-result result))
    (catch clojure.lang.ExceptionInfo e
      (exception-result e))))

(defn run-stdio
  ([tools call-tool cleanup!]
   (run-stdio tools call-tool cleanup! (atom [])))
  ([tools call-tool cleanup! pending]
   (let [write-lock (Object.)]
     (try
       (loop []
         (when-let [line (read-line)]
           (let [task (future
                        (when-let [response (mcp/handle-message line tools call-tool)]
                          (locking write-lock
                            (println (json/write-str response))
                            (flush))))]
             (swap! pending #(conj (vec (remove realized? %)) task)))
           (recur)))
       (swap! pending #(vec (remove realized? %)))
       (doseq [task @pending]
         (deref task))
       (swap! pending #(vec (remove realized? %)))
       (finally
         (cleanup!))))))

(defn -main [& _]
  (try
    (run-stdio tool-definitions call-tool #(client/disconnect! connection-state))
    (finally
      (shutdown-agents))))
