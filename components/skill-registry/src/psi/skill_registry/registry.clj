(ns psi.skill-registry.registry
  "Pure registered-skill collection ownership: validation, registration, and queries."
  (:require
   [clojure.string :as str]))

(defn valid-skill-name?
  "Return true when `skill-name` is a non-blank string."
  [skill-name]
  (and (string? skill-name)
       (not (str/blank? skill-name))))

(defn- ensure-valid-skill!
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
  "Return the registered skills as a vector, preserving registration order."
  [skills]
  (vec (or skills [])))

(defn find-skill
  "Return the registered skill named `skill-name`, or nil when absent."
  [skills skill-name]
  (first (filter #(= (:name %) skill-name) (all-skills skills))))

(defn skill-names
  "Return the registered skill names in registration order."
  [skills]
  (mapv :name (all-skills skills)))

(defn skill-count
  "Return the registered skill count."
  [skills]
  (count (all-skills skills)))

(defn register-skill
  "Register `skill` into the ordered registered-skill vector `skills`.

   Canonical first-cut policy:
   - `:name` must be a non-blank string
   - first registration per name wins
   - duplicate names are ignored
   - first-registration order is preserved

   Returns {:skills [...] :skill skill-map :added? boolean :changed? boolean :count n}."
  [skills skill]
  (let [skills     (all-skills skills)
        skill      (ensure-valid-skill! skill)
        existing   (find-skill skills (:name skill))
        added?     (nil? existing)
        next-skills (if added?
                      (conj skills skill)
                      skills)]
    {:skills next-skills
     :skill (or existing skill)
     :added? added?
     :changed? added?
     :count (count next-skills)}))
