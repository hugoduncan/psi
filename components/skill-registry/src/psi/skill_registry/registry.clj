(ns psi.skill-registry.registry
  "Pure registered-skill collection ownership: validation, registration, and queries."
  (:require
   [clojure.string :as str]))

(defn valid-skill-name?
  "Return true when `skill-name` is a non-blank string."
  [skill-name]
  (and (string? skill-name)
       (not (str/blank? skill-name))))

(defn ensure-valid-skill!
  [skill]
  (let [skill-name (:name skill)]
    (when-not (valid-skill-name? skill-name)
      (throw (ex-info (str "Invalid skill name: " (pr-str skill-name)
                           ". Expected a non-blank string.")
                      {:skill skill
                       :skill-name skill-name
                       :reason :invalid-skill-name}))))
  skill)

(defn all-skills
  "Return the registered skills in canonical skill-name order."
  [skills]
  (->> (or skills [])
       (sort-by :name compare)
       vec))

(defn find-skill
  "Return the registered skill named `skill-name`, or nil when absent."
  [skills skill-name]
  (some #(when (= (:name %) skill-name) %) (or skills [])))

(defn prompt-hidden?
  "Return true when `skill` must be excluded from the system-context listing:
   either it disables model invocation, or it carries `advertise: false`.
   Absent `:advertise` (or any non-false value) keeps the skill advertised.

   Canonical system-context visibility predicate — all surfaces that report
   or render which skills the model sees should route through this so they
   cannot drift."
  [skill]
  (or (:disable-model-invocation skill)
      (false? (:advertise skill))))

(defn visible-skills
  "Return the registered skills that appear in the system context, in canonical
   skill-name order (those not `prompt-hidden?`)."
  [skills]
  (vec (remove prompt-hidden? (all-skills skills))))

(defn hidden-skills
  "Return the registered skills excluded from the system context, in canonical
   skill-name order (those `prompt-hidden?`)."
  [skills]
  (vec (filter prompt-hidden? (all-skills skills))))

(defn skill-names
  "Return the registered skill names in canonical skill-name order."
  [skills]
  (mapv :name (all-skills skills)))

(defn skill-count
  "Return the registered skill count."
  [skills]
  (count (all-skills skills)))

(defn register-skill
  "Register `skill` into the registered-skill collection `skills`.

   Policy:
   - `:name` must be a non-blank string
   - first registration per name wins
   - duplicate names are ignored
   - visible skill listing is canonical skill-name order

   Returns {:skills [...] :skill skill-map :added? boolean :changed? boolean :count n}."
  [skills skill]
  (let [skills         (vec (or skills []))
        ordered-skills (all-skills skills)
        skill          (ensure-valid-skill! skill)
        existing       (find-skill skills (:name skill))
        added?         (nil? existing)
        next-skills    (if added?
                         (all-skills (conj skills skill))
                         ordered-skills)]
    {:skills next-skills
     :skill (or existing skill)
     :added? added?
     :changed? added?
     :count (count next-skills)}))
