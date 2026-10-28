(ns jolt-nrepl.client
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [jolt-nrepl.delimiter-repair :as repair]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport]))

(def ^:private default-timeout-ms 120000)
(def ^:private request-counter (atom 0))

(defn create-state []
  (atom {:connected? false :busy? false}))

(defn- fail [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

(defn- canonical-cwd [cwd]
  (when-not (and (string? cwd) (fs/absolute? cwd))
    (fail :invalid-cwd "cwd must be an absolute project directory" {:cwd cwd}))
  (try
    (let [path (fs/real-path cwd)]
      (when-not (fs/directory? path)
        (fail :invalid-cwd "cwd must be a directory" {:cwd cwd}))
      (str path))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch :default e
      (fail :invalid-cwd (.getMessage e) {:cwd cwd}))))

(defn- timeout [value]
  (let [timeout-ms (or value default-timeout-ms)]
    (when-not (and (integer? timeout-ms) (<= 1 timeout-ms 300000))
      (fail :invalid-timeout "timeoutMs must be between 1 and 300000" {:timeout-ms value}))
    timeout-ms))

(defn- valid-port [port]
  (when-not (and (integer? port) (<= 1 port 65535))
    (fail :invalid-port "nREPL port must be an integer between 1 and 65535" {:port port}))
  port)

(defn- discover-port [cwd]
  (let [port-file (str cwd "/.nrepl-port")]
    (when-not (fs/exists? port-file)
      (fail :missing-port (str "No .nrepl-port in " cwd) {:cwd cwd}))
    (valid-port (parse-long (str/trim (slurp port-file))))))

(defn- status* [state]
  (let [{:keys [connected? busy? cwd host port session ops]} @state]
    {:connected? (boolean connected?)
     :busy? (boolean busy?)
     :cwd cwd
     :host host
     :port port
     :session session
     :interrupt? (contains? ops :interrupt)}))

(defn status [state]
  (status* state))

(defn- acquire! [state]
  (locking state
    (when (:busy? @state)
      (fail :busy "An nREPL eval or load-file is already active"))
    (swap! state assoc :busy? true)))

(defn- release! [state]
  (locking state
    (swap! state assoc :busy? false :active-id nil)))

(defn- join-task! [task]
  (try
    (deref task 1000 ::timeout)
    (catch :default _ nil)))

(defn- close-transport! [state]
  (let [{:keys [transport session]}
        (locking state
          (let [transport (:transport @state)
                session (:session @state)
                busy? (:busy? @state)]
            (reset! state {:connected? false :busy? busy?})
            {:transport transport :session session}))]
    (when transport
      (try
        (when session
          (transport/send transport {:op "close" :session session}))
        (catch :default _ nil))
      (try
        (nrepl/close transport)
        (catch :default _ nil)))))

(defn disconnect! [state]
  (let [connected? (:connected? @state)]
    (close-transport! state)
    (assoc (status* state) :disconnected? connected?)))

(defn- connect-inner! [state {:keys [cwd host port timeout-ms]}]
  (let [transport (nrepl/connect host port)
        timeout-ms (or timeout-ms 10000)]
    (swap! state assoc
           :connected? false
           :cwd cwd
           :host host
           :port port
           :transport transport
           :session nil
           :ops nil)
    (try
      (let [task (future
                   (let [session (nrepl/new-session transport)
                         description (first (nrepl/message transport {:op "describe"}))]
                     [session (set (map keyword (keys (:ops description))))]))
            handshake (deref task timeout-ms ::timeout)]
        (when (= ::timeout handshake)
          (future-cancel task)
          (close-transport! state)
          (join-task! task)
          (fail :connection-timeout
                (str "nREPL handshake timed out after " timeout-ms "ms")))
        (when-not (identical? transport (:transport @state))
          (fail :connection-closed "nREPL connection closed during handshake"))
        (let [[session ops] handshake]
          (swap! state assoc :connected? true :session session :ops ops)
          (status* state)))
      (catch :default e
        (close-transport! state)
        (throw e)))))

(defn connect! [state {:keys [cwd host port timeout-ms]}]
  (let [cwd (canonical-cwd cwd)
        host (or host "127.0.0.1")
        port (if (nil? port) (discover-port cwd) (valid-port port))]
    (acquire! state)
    (try
      (close-transport! state)
      (connect-inner! state {:cwd cwd :host host :port port :timeout-ms timeout-ms})
      (finally
        (release! state)))))

(defn- ensure-connected! [state cwd]
  (when (and (:connected? @state) (not= cwd (:cwd @state)))
    (fail :project-mismatch
          "The nREPL connection belongs to a different project; call nrepl_connect first"
          {:connected-cwd (:cwd @state) :requested-cwd cwd}))
  (when-not (:connected? @state)
    (connect-inner! state {:cwd cwd :host "127.0.0.1" :port (discover-port cwd)})))

(defn- next-id []
  (str "jolt-nrepl-" (System/nanoTime) "-" (swap! request-counter inc)))

(declare interrupt!)

(defn- request! [state message timeout-ms]
  (let [{:keys [transport session]} @state
        id (next-id)]
    (swap! state assoc :active-id id)
    (try
      (let [task (future (nrepl/message transport (assoc message :id id :session session)))
            responses (deref task timeout-ms ::timeout)]
        (if (= ::timeout responses)
          (let [interrupt-result (try (interrupt! state) (catch :default _ nil))
                interrupted? (= :interrupt-requested (:type interrupt-result))
                final-response (when interrupted? (join-task! task))
                certain? (boolean
                          (and interrupted?
                               (not= ::timeout final-response)
                               (some #(contains? (set (:status %)) "done") final-response)))]
            (when-not certain?
              (future-cancel task))
            (close-transport! state)
            (join-task! task)
            (fail :timeout
                  (str "nREPL request timed out after " timeout-ms "ms")
                  {:interrupted? interrupted? :certain? certain?}))
          (do
            (when-not (some #(contains? (set (:status %)) "done") responses)
              (fail :connection-closed "nREPL closed before completing the request"))
            (let [combined (nrepl/combine-responses responses)
                  values (vec (:value combined))
                  statuses (vec (sort (:status combined)))]
              {:values values
               :result (str/join "\n" values)
               :out (or (:out combined) "")
               :err (or (:err combined) "")
               :ns (or (:ns combined) "")
               :status statuses
               :error? (boolean (some #{"error" "eval-error" "interrupted"} statuses))}))))
      (catch :default e
        (close-transport! state)
        (throw e)))))

(defn evaluate! [state {:keys [cwd code timeout-ms]}]
  (let [cwd (canonical-cwd cwd)
        timeout-ms (timeout timeout-ms)]
    (when-not (and (string? code) (not (str/blank? code)))
      (fail :invalid-code "code must be a nonempty string"))
    (acquire! state)
    (try
      (ensure-connected! state cwd)
      (let [{:keys [code repaired?]} (repair/repair code)
            result (request! state {:op "eval" :code code} timeout-ms)]
        (if repaired? (assoc result :repaired code) result))
      (finally
        (release! state)))))

(defn- project-file [cwd file]
  (when-not (and (string? file) (not (str/blank? file)))
    (fail :invalid-file "file must be a nonempty path"))
  (let [path (try
               (fs/real-path (fs/path cwd file))
               (catch :default e
                 (fail :missing-file (.getMessage e) {:file file})))]
    (when-not (.startsWith path (fs/path cwd))
      (fail :path-escape "load-file path escapes the project root" {:file file}))
    (when-not (fs/regular-file? path)
      (fail :invalid-file "load-file requires a regular file" {:file file}))
    path))

(defn load-file! [state {:keys [cwd file timeout-ms]}]
  (let [cwd (canonical-cwd cwd)
        timeout-ms (timeout timeout-ms)
        path (project-file cwd file)]
    (acquire! state)
    (try
      (ensure-connected! state cwd)
      (request! state {:op "load-file"
                       :file (slurp (str path))
                       :file-path (str path)
                       :file-name (str (fs/file-name path))}
                      timeout-ms)
      (finally
        (release! state)))))

(defn interrupt! [state]
  (let [{:keys [connected? ops transport session active-id]} @state]
    (cond
      (not connected?) {:type :not-connected :interrupted? false}
      (not (contains? ops :interrupt)) {:type :interrupt-unsupported :interrupted? false}
      (nil? active-id) {:type :no-active-eval :interrupted? false}
      :else (do
              (transport/send transport {:op "interrupt"
                                         :interrupt-id active-id
                                         :session session})
              {:type :interrupt-requested :interrupted? true}))))
