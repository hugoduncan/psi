(ns psi.memory.graph-history
  "Graph snapshot/delta retention constants and helpers.")

(def snapshot-retention-limit
  "Step 10 fixed-window snapshot cap."
  200)

(def delta-retention-limit
  "Step 10 fixed-window delta cap."
  1000)

(defn trim-window
  "Keep only the latest `limit` entries from `entries`."
  [entries limit]
  (let [c (count entries)]
    (if (<= c limit)
      (vec entries)
      (vec (drop (- c limit) entries)))))
