(ns psi.skill-registry.root-storage
  "Root-registry-backed skill definition storage plus session membership helpers."
  (:require
   [psi.root-registry.registry :as root-registry]
   [psi.session-state.state :as session-state]
   [psi.skill-registry.registry :as skill-registry]))

(def registry-id :skills)
(def extension-id :psi.skill-registry/definitions)
(def session-skill-ids-key :skill-ids)

(defn ensure-skill-registry
  [root-state]
  (root-registry/declare-registry root-state registry-id))

(defn ensure-skill-registry-in
  [ctx]
  (swap! (:state* ctx) ensure-skill-registry))

(defn- skill-entry
  [skill]
  {:id (:name skill)
   :extension-id extension-id
   :value skill})

(defn- lookup-definition
  [root-state skill-id]
  (some-> (root-registry/lookup root-state registry-id skill-id)
          :result
          :value
          :value))

(defn skill-ids
  [session-data]
  (vec (or (get session-data session-skill-ids-key) [])))

(defn skill-ids-in
  [ctx session-id]
  (skill-ids (session-state/get-session-data-in ctx session-id)))

(defn all-skills
  [root-state session-data]
  (->> (skill-ids session-data)
       (keep #(lookup-definition root-state %))
       skill-registry/all-skills))

(defn all-skills-in
  [ctx session-id]
  (all-skills @(:state* ctx) (session-state/get-session-data-in ctx session-id)))

(defn find-skill
  [root-state _session-data skill-id]
  (some->> skill-id
           (lookup-definition root-state)
           skill-registry/ensure-valid-skill!))

(defn find-skill-in
  [ctx session-id skill-name]
  (find-skill @(:state* ctx) (session-state/get-session-data-in ctx session-id) skill-name))

(defn skill-names
  [root-state session-data]
  (skill-registry/skill-names (all-skills root-state session-data)))

(defn skill-names-in
  [ctx session-id]
  (skill-names @(:state* ctx) (session-state/get-session-data-in ctx session-id)))

(defn skill-count
  [_root-state session-data]
  (count (skill-ids session-data)))

(defn skill-count-in
  [ctx session-id]
  (skill-count @(:state* ctx) (session-state/get-session-data-in ctx session-id)))

(defn register-skill-in-root-state
  [root-state session-id skill]
  (let [skill          (skill-registry/ensure-valid-skill! skill)
        root-state*    (ensure-skill-registry root-state)
        current-sd     (get-in root-state* (session-state/session-data-path session-id))
        current-ids    (skill-ids current-sd)
        skill-id       (:name skill)
        inserted?      (nil? (lookup-definition root-state* skill-id))
        insert-result  (root-registry/insert root-state* registry-id (skill-entry skill))
        root-state*    (:root-state insert-result)
        existing-skill (or (lookup-definition root-state* skill-id) skill)
        already-member? (some #(= skill-id %) current-ids)
        next-ids       (if already-member? current-ids (conj current-ids skill-id))
        next-state     (if (= next-ids current-ids)
                         root-state*
                         (assoc-in root-state* (conj (session-state/session-data-path session-id) session-skill-ids-key) next-ids))
        projected      (all-skills next-state (get-in next-state (session-state/session-data-path session-id)))]
    {:root-state next-state
     :skills projected
     :skill existing-skill
     :skill-ids next-ids
     :added? (not already-member?)
     :changed? (not already-member?)
     :count (count next-ids)
     :definition-inserted? inserted?}))

(defn set-skills-in-root-state
  [root-state session-id skills]
  (let [root-state (ensure-skill-registry root-state)
        skills     (vec (or skills []))
        ensured    (reduce (fn [{:keys [root-state skill-ids projected-by-id]} skill]
                             (let [skill       (skill-registry/ensure-valid-skill! skill)
                                   skill-id    (:name skill)
                                   insert-out  (root-registry/insert root-state registry-id (skill-entry skill))
                                   root-state' (:root-state insert-out)
                                   stored      (or (lookup-definition root-state' skill-id) skill)]
                               {:root-state root-state'
                                :skill-ids (conj skill-ids skill-id)
                                :projected-by-id (assoc projected-by-id skill-id stored)}))
                           {:root-state root-state
                            :skill-ids []
                            :projected-by-id {}}
                           skills)
        unique-ids  (vec (distinct (:skill-ids ensured)))
        root-state' (assoc-in (:root-state ensured)
                              (conj (session-state/session-data-path session-id) session-skill-ids-key)
                              unique-ids)
        session-data (get-in root-state' (session-state/session-data-path session-id))]
    {:root-state root-state'
     :skills (all-skills root-state' session-data)
     :skill-ids unique-ids
     :count (count unique-ids)
     :changed? true}))
