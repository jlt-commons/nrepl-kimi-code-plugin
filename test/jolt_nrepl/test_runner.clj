(ns jolt-nrepl.test-runner
  (:require [clojure.test :as t]
            [jolt-nrepl.connection-test]
            [jolt-nrepl.delimiter-repair-test]
            [jolt-nrepl.integration-test]
            [jolt-nrepl.lifecycle-test]
            [jolt-nrepl.mcp-stdio-test]
            [jolt-nrepl.mcp-test]
            [jolt-nrepl.nrepl-client-test]
            [jolt-nrepl.server-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'jolt-nrepl.delimiter-repair-test
                                         'jolt-nrepl.mcp-test
                                         'jolt-nrepl.nrepl-client-test
                                         'jolt-nrepl.connection-test
                                         'jolt-nrepl.integration-test
                                         'jolt-nrepl.lifecycle-test
                                         'jolt-nrepl.server-test
                                         'jolt-nrepl.mcp-stdio-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
