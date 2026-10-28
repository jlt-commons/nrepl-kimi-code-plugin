(ns jolt-nrepl.delimiter-repair-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt-nrepl.delimiter-repair :as sut]))

(deftest valid-code-is-unchanged
  (doseq [code ["(defn f [x] {:x x})"
                "(str \"😀\" \"λ\")"
                "(println \";\") ; ((("
                "(str \"quote: \\\"(\")"]]
    (is (= {:code code :repaired? false} (sut/repair code)) code)))

(deftest missing-closers-are-appended-in-stack-order
  (is (= {:code "(defn f [x] {:x [x]})" :repaired? true}
         (sut/repair "(defn f [x] {:x [x]")))
  (is (= {:code "(println \\()" :repaired? true}
         (sut/repair "(println \\(")))
  (is (= {:code "(println \\])" :repaired? true}
         (sut/repair "(println \\]")))
  (is (= {:code "(println 1 ; comment\n)" :repaired? true}
         (sut/repair "(println 1 ; comment"))))

(deftest invalid-closers-are-not-hidden
  (doseq [code ["(defn f [x)" "(println 1))" "(str \"unterminated"]]
    (is (= {:code code :repaired? false} (sut/repair code)) code)))
