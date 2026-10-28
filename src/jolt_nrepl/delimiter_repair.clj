(ns jolt-nrepl.delimiter-repair)

(def ^:private closers {\( \) \[ \] \{ \}})
(def ^:private closing (set (vals closers)))

(defn repair [code]
  (loop [i 0
         stack []
         in-string? false
         escaped? false
         in-comment? false
         in-character? false]
    (if (>= i (count code))
      (if (or (empty? stack) in-string? in-character?)
        {:code code :repaired? false}
        {:code (str code
                    (when in-comment? "\n")
                    (apply str (map closers (reverse stack))))
         :repaired? true})
      (let [ch (nth code i)]
        (cond
          in-comment?
          (recur (inc i) stack in-string? false (not= ch \newline) false)

          in-character?
          (recur (inc i) stack in-string? false in-comment? false)

          escaped?
          (recur (inc i) stack in-string? false in-comment? false)

          in-string?
          (cond
            (= ch \\) (recur (inc i) stack true true in-comment? false)
            (= ch \") (recur (inc i) stack false false in-comment? false)
            :else (recur (inc i) stack true false in-comment? false))

          (= ch \;)
          (recur (inc i) stack in-string? false true false)

          (= ch \")
          (recur (inc i) stack true false in-comment? false)

          (= ch \\)
          (recur (inc i) stack in-string? false in-comment? true)

          (contains? closers ch)
          (recur (inc i) (conj stack ch) in-string? false in-comment? false)

          (contains? closing ch)
          (if (and (seq stack) (= ch (closers (peek stack))))
            (recur (inc i) (pop stack) in-string? false in-comment? false)
            {:code code :repaired? false})

          :else
          (recur (inc i) stack in-string? false in-comment? false))))))
