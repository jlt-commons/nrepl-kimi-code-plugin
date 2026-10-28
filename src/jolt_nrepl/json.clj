(ns jolt-nrepl.json
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private surrogate-pair-pattern
  #"\\u([dD][89aAbB][0-9a-fA-F]{2})\\u([dD][cCdDeEfF][0-9a-fA-F]{2})")

(defn- surrogate-pair->codepoint [[_ high low]]
  (let [high (Integer/parseInt high 16)
        low (Integer/parseInt low 16)]
    (str (char (+ 0x10000
                  (* (- high 0xd800) 0x400)
                  (- low 0xdc00))))))

(defn read-str [input & options]
  (apply json/read-str
         (str/replace input surrogate-pair-pattern surrogate-pair->codepoint)
         options))

(defn write-str [value & options]
  (apply json/write-str value :escape-unicode false options))
