(ns psi.tui.app.autocomplete
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [psi.tui.app.shared :as shared]
   [psi.tui.app.support :as support]))

(defn whitespace-char?
  [^Character c]
  (Character/isWhitespace c))

(defn token-context-at-cursor
  [state]
  (let [text        (shared/input-value state)
        pos         (shared/input-pos state)
        before      (subs text 0 pos)
        after       (subs text pos)
        token-start (or (some->> (keep-indexed (fn [i ch]
                                                 (when (whitespace-char? ch) i))
                                               before)
                                 last
                                 inc)
                        0)
        token       (subs before token-start)
        context     (cond
                      (and (= token-start 0)
                           (str/starts-with? token "/"))
                      :slash_command

                      (str/starts-with? token "@")
                      :file_reference

                      :else
                      :file_path)]
    {:text text
     :pos pos
     :before before
     :after after
     :token token
     :token-start token-start
     :token-end pos
     :context context
     :prefix token}))

(defn as-slash-command
  [x]
  (let [s (some-> x str str/trim)]
    (when (seq s)
      (if (str/starts-with? s "/")
        s
        (str "/" s)))))

(defn slash-candidates
  [state prefix]
  (let [templates  (mapv (fn [{:keys [name]}] (str "/" name)) (:prompt-templates state))
        skills     (mapv (fn [{:keys [name]}] (str "/skill:" name)) (:skills state))
        ext-cmds   (vec (keep as-slash-command (:extension-command-names state)))
        all        (->> (concat shared/builtin-slash-commands templates skills ext-cmds)
                        (remove str/blank?)
                        distinct
                        sort)
        pfx        (or prefix "/")
        lowered    (str/lower-case pfx)]
    (->> all
         (filter #(str/starts-with? (str/lower-case %) lowered))
         (mapv (fn [cmd]
                 {:value cmd
                  :label cmd
                  :description nil
                  :kind :slash_command
                  :is-directory false})))))

(defn rel-path
  [cwd ^java.io.File f]
  (let [cwd-path (.toPath (io/file cwd))
        file-path (.toPath f)]
    (-> (.relativize cwd-path file-path)
        (.normalize)
        (.toString))))

(defn hidden-or-git-path?
  [rel]
  (or (= ".git" rel)
      (str/starts-with? rel ".git/")
      (str/includes? rel "/.git/")
      (str/ends-with? rel "/.git")))

(defn quote-if-needed [s]
  (if (str/includes? s " ")
    (str "\"" s "\"")
    s))

(defn file-reference-candidates
  [state prefix]
  (let [cwd        (:cwd state)
        token      (or prefix "@")
        typed      (subs token (min 1 (count token)))
        typed      (str/replace typed #"^\"" "")
        query      (str/lower-case typed)
        root       (io/file cwd)]
    (->> (file-seq root)
         (remove #(.equals root %))
         (map (fn [^java.io.File f]
                (let [rel (rel-path cwd f)]
                  {:file f :rel rel :dir? (.isDirectory f)})))
         (remove #(hidden-or-git-path? (:rel %)))
         (filter (fn [{:keys [rel]}]
                   (or (str/blank? query)
                       (str/includes? (str/lower-case rel) query))))
         (sort-by (juxt (fn [{:keys [dir?]}] (if dir? 0 1)) :rel))
         (take 100)
         (mapv (fn [{:keys [rel dir?]}]
                 (let [v (cond-> rel
                           dir? (str "/"))
                       v (quote-if-needed v)]
                   {:value v
                    :label v
                    :description nil
                    :kind :file_reference
                    :is-directory dir?}))))))

(defn path-completion-candidates
  [state prefix]
  (let [cwd          (:cwd state)
        token        (or prefix "")
        slash-idx    (str/last-index-of token "/")
        [dir-part name-part] (if slash-idx
                               [(subs token 0 (inc slash-idx))
                                (subs token (inc slash-idx))]
                               ["" token])
        base         (io/file cwd dir-part)
        dir-exists?  (.isDirectory base)]
    (if-not dir-exists?
      []
      (->> (.listFiles base)
           (filter some?)
           (map (fn [^java.io.File f]
                  (let [nm (.getName f)
                        rel (str dir-part nm)]
                    {:name nm
                     :rel rel
                     :dir? (.isDirectory f)})))
           (remove #(hidden-or-git-path? (:rel %)))
           (filter (fn [{:keys [name]}]
                     (str/starts-with? (str/lower-case name)
                                       (str/lower-case name-part))))
           (sort-by (juxt (fn [{:keys [dir?]}] (if dir? 0 1)) :rel))
           (mapv (fn [{:keys [rel dir?]}]
                   (let [v (cond-> rel
                             dir? (str "/"))]
                     {:value v
                      :label v
                      :description nil
                      :kind :file_path
                      :is-directory dir?})))))))

(defn clear-autocomplete
  [state]
  (assoc-in state [:prompt-input-state :autocomplete]
            {:prefix ""
             :candidates []
             :selected-index 0
             :context nil
             :trigger-mode nil}))

(defn open-autocomplete
  [state {:keys [prefix context trigger-mode token-start token-end]} candidates]
  (if (seq candidates)
    (assoc-in state [:prompt-input-state :autocomplete]
              {:prefix prefix
               :candidates candidates
               :selected-index 0
               :context context
               :trigger-mode trigger-mode
               :token-start token-start
               :token-end token-end})
    (clear-autocomplete state)))

(defn context-candidates
  [state context prefix]
  (case context
    :slash_command (slash-candidates state prefix)
    :file_reference (file-reference-candidates state prefix)
    :file_path (path-completion-candidates state prefix)
    []))

(defn refresh-autocomplete
  [state trigger-mode]
  (let [{:keys [context prefix token-start token-end]} (token-context-at-cursor state)
        candidates (context-candidates state context prefix)]
    (open-autocomplete state {:prefix prefix
                              :context context
                              :trigger-mode trigger-mode
                              :token-start token-start
                              :token-end token-end}
                       candidates)))

(defn autocomplete-open?
  [state]
  (seq (get-in state [:prompt-input-state :autocomplete :candidates])))

(defn move-autocomplete-selection
  [state delta]
  (let [cands (get-in state [:prompt-input-state :autocomplete :candidates])
        cnt   (count cands)]
    (if (zero? cnt)
      state
      (update-in state [:prompt-input-state :autocomplete :selected-index]
                 (fn [i]
                   (let [i (or i 0)]
                     (mod (+ i delta) cnt)))))))

(defn drop-duplicate-closing-quote-in-after
  [replacement after]
  (if (and (str/ends-with? replacement "\"")
           (str/starts-with? (or after "") "\""))
    (subs after 1)
    after))

(defn apply-selected-autocomplete
  [state]
  (let [ac         (get-in state [:prompt-input-state :autocomplete])
        idx        (or (:selected-index ac) 0)
        candidate  (nth (:candidates ac) idx nil)
        text       (shared/input-value state)
        start      (or (:token-start ac) (count text))
        end        (or (:token-end ac) (count text))
        before     (subs text 0 (min start (count text)))
        after      (subs text (min end (count text)))
        context    (:context ac)]
    (if-not candidate
      state
      (let [base-value (:value candidate)
            replacement (case context
                          :file_reference (str "@" base-value)
                          base-value)
            after       (drop-duplicate-closing-quote-in-after replacement after)
            replacement (if (and (= :file_reference context)
                                 (not (:is-directory candidate)))
                          (str replacement " ")
                          replacement)
            text'      (str before replacement after)]
        (-> state
            (shared/set-input-value text')
            clear-autocomplete)))))

(defn maybe-auto-open-autocomplete
  [state key-token]
  (let [ch (support/printable-key key-token)]
    (if-not (or (= ch "/") (= ch "@"))
      state
      (let [{:keys [context token]} (token-context-at-cursor state)]
        (cond
          (and (= ch "/") (= :slash_command context))
          (refresh-autocomplete state :auto)

          (and (= ch "@") (= :file_reference context)
               (str/starts-with? token "@"))
          (refresh-autocomplete state :auto)

          :else
          state)))))

(defn open-tab-autocomplete
  [state]
  (let [{:keys [token token-start token-end]} (token-context-at-cursor state)
        context    (if (and (= token-start 0) (str/starts-with? token "/"))
                     :slash_command
                     :file_path)
        candidates (context-candidates state context token)
        opened     (open-autocomplete state {:prefix token
                                             :context context
                                             :trigger-mode :tab
                                             :token-start token-start
                                             :token-end token-end}
                                      candidates)]
    (if (and (= :file_path context)
             (= 1 (count candidates)))
      (apply-selected-autocomplete opened)
      opened)))
