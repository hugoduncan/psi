(ns psi.ai.textual-tool-calls.index-allocator
  (:require
   [clojure.string :as str]))

(defn- digit?
  [ch]
  (<= (int \0) (int ch) (int \9)))

(defn- decimal-string->long
  [s]
  (when (and (seq s) (every? digit? s))
    (let [limit (quot Long/MAX_VALUE 10)
          final-limit (mod Long/MAX_VALUE 10)]
      (loop [digits (seq s)
             value  0]
        (if-let [digit (first digits)]
          (let [n (- (int digit) (int \0))]
            (when (or (< value limit)
                      (and (= value limit) (<= n final-limit)))
              (recur (next digits) (+ (* value 10) n))))
          value)))))

(defn generated-tool-call-index
  [turn-id tool-call-id]
  (let [prefix       (str turn-id "/toolcall/")
        tool-call-id (str tool-call-id)]
    (when (str/starts-with? tool-call-id prefix)
      (decimal-string->long (subs tool-call-id (count prefix))))))

(defn leading-recovered-text-block?
  [parsed-calls-key block]
  (when-let [parsed-calls (seq (get block parsed-calls-key))]
    (zero? (-> parsed-calls first :span first))))

(defn shadowed-source-indexes
  [turn-id parsed-calls-key content]
  (let [indexed-content (->> content
                             (map-indexed vector)
                             vec)]
    (->> indexed-content
         (filter (fn [[idx block]]
                   (let [source-index (:content-index block)]
                     (and (leading-recovered-text-block? parsed-calls-key block)
                          source-index
                          (not-any? (fn [[other-idx other-block]]
                                      (and (not= idx other-idx)
                                           (or (= source-index (:content-index other-block))
                                               (= source-index
                                                  (generated-tool-call-index
                                                   turn-id
                                                   (:id other-block))))))
                                    indexed-content)))))
         (keep (comp :content-index second))
         set)))

(defn initial-state
  [turn-id parsed-calls-key content]
  (let [source-indexes        (->> content
                                   (keep :content-index)
                                   set)
        generated-id-indexes  (->> content
                                   (keep #(generated-tool-call-index turn-id (:id %)))
                                   set)
        shadowed-source-index (shadowed-source-indexes turn-id parsed-calls-key content)]
    {:used                 (set (concat (remove shadowed-source-index source-indexes)
                                        generated-id-indexes))
     :position             -1
     :source-text-reserved #{}}))

(defn- reserve-content-index
  [state content-index]
  [(-> state
       (update :used conj content-index)
       (update :position max content-index))
   content-index])

(defn- allocate-content-index
  [state preferred]
  (loop [candidate preferred]
    (if (contains? (:used state) candidate)
      (recur (inc candidate))
      (reserve-content-index state candidate))))

(defn- source-text-index
  [state source-index _preferred]
  (if (contains? (:source-text-reserved state) source-index)
    (allocate-content-index state (max (inc (long source-index)) (inc (long (:position state)))))
    (reserve-content-index (update state :source-text-reserved conj source-index) source-index)))

(defn- leading-recovered-index
  [state preferred source-index]
  (let [state* (update state :source-text-reserved conj source-index)]
    (allocate-content-index state* preferred)))

(defn- fallback-index
  [state source-index preferred]
  (let [preferred* (if (contains? (:source-text-reserved state) source-index)
                     (max (inc (long source-index)) preferred)
                     preferred)]
    (allocate-content-index state preferred*)))

(defn assign-index
  "Assign the next final-order content index.

  Returns `[state index]`. `kind` is one of:
  - `:existing-content` for provider-emitted non-text content
  - `:source-text` for retained residual text from a source text block
  - `:recovered-tool-call` for a textual tool call recovered from source text"
  [turn-id parsed-calls-key state source-block kind]
  (let [position     (:position state)
        source-index (:content-index source-block)
        generated-id (generated-tool-call-index turn-id (:id source-block))
        leading?     (leading-recovered-text-block? parsed-calls-key source-block)
        reserved?    (contains? (:source-text-reserved state) source-index)
        preferred    (cond
                       (and (= :source-text kind) source-index)
                       (max (long source-index) (inc (long position)))

                       (and (= :recovered-tool-call kind) source-index leading? (not reserved?))
                       (long source-index)

                       (and (= :existing-content kind) source-index)
                       (long source-index)

                       (and (= :existing-content kind) generated-id)
                       generated-id

                       (= :existing-content kind)
                       (inc (long position))

                       source-index
                       (max (inc (long source-index)) (inc (long position)))

                       :else
                       (inc (long position)))]
    (cond
      (and (= :existing-content kind) source-index)
      (reserve-content-index state preferred)

      (and (= :existing-content kind) generated-id)
      (reserve-content-index state preferred)

      (and (= :source-text kind) source-index)
      (source-text-index state source-index preferred)

      (and (= :recovered-tool-call kind) source-index leading? (not reserved?))
      (leading-recovered-index state preferred source-index)

      :else
      (fallback-index state source-index preferred))))
