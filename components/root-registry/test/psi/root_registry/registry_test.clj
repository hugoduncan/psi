(ns psi.root-registry.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.root-registry.registry :as registry]))

(defn- sample-entry
  ([id extension-id value]
   {:id id
    :extension-id extension-id
    :value value})
  ([id extension-id value provenance]
   {:id id
    :extension-id extension-id
    :value value
    :provenance provenance}))

(defn- register-entry
  [root-state registry-id entry]
  (:root-state (registry/register root-state registry-id entry)))

(deftest declaration-test
  ;; Tests explicit registry declaration semantics and canonical empty state.
  (testing "declares one registry with canonical empty state"
    (let [root-state (registry/declare-registry (registry/empty-root-state) :tools)]
      (is (= {:entries-by-id {}
              :ids-by-extension {}}
             (registry/registry-state root-state :tools)))))

  (testing "declaration is idempotent for an already declared registry"
    (let [root-state (-> (registry/empty-root-state)
                         (registry/declare-registry :tools)
                         (registry/declare-registry :tools))]
      (is (= [:tools]
             (vec (registry/list-registry-ids root-state))))))

  (testing "declared-root-state declares multiple registries"
    (let [root-state (registry/declared-root-state [:tools :commands])]
      (is (registry/declared-registry? root-state :tools))
      (is (registry/declared-registry? root-state :commands))))

  (testing "mutations and list operations do not implicitly declare registries"
    (let [root-state (registry/empty-root-state)]
      (is (= false
             (registry/declared-registry? (:root-state (registry/register root-state :tools (sample-entry :read :ext/read {:name "read"})))
                                          :tools)))
      (is (= false
             (registry/declared-registry? (:root-state (registry/unregister root-state :tools :read))
                                          :tools)))
      (is (= false
             (registry/declared-registry? (:root-state (registry/clear-by-extension root-state :tools :ext/read))
                                          :tools)))
      (is (= false
             (registry/declared-registry? (:root-state (registry/clear-registry root-state :tools))
                                          :tools)))
      (is (= {:ok? false
              :status :failed
              :operation :list-entries
              :registry-id :tools
              :failure-kind :unknown-registry
              :message "Unknown registry: :tools"}
             (:result (registry/list-entries root-state :tools)))))))

(deftest lookup-test
  ;; Tests hit/miss semantics and the special unknown-registry lookup behavior.
  (testing "returns stored entry on hit"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read"})))
          result (:result (registry/lookup root-state :tools :read))]
      (is (= true (:ok? result)))
      (is (= :hit (:change result)))
      (is (= {:id :read :extension-id :ext/read :value {:name "read"}}
             (:value result)))))

  (testing "returns nil value on miss in declared registry"
    (let [result (:result (registry/lookup (registry/declared-root-state [:tools]) :tools :missing))]
      (is (= true (:ok? result)))
      (is (= :miss (:change result)))
      (is (nil? (:value result)))))

  (testing "returns nil value for unknown registry lookup"
    (let [result (:result (registry/lookup (registry/empty-root-state) :tools :missing))]
      (is (= true (:ok? result)))
      (is (= :miss (:change result)))
      (is (nil? (:value result))))))

(deftest list-entries-test
  ;; Tests unordered list semantics and explicit unknown-registry failure.
  (testing "lists entries for a known registry"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read"}))
                         (register-entry :tools (sample-entry :write :ext/write {:name "write"})))
          result (:result (registry/list-entries root-state :tools))]
      (is (= true (:ok? result)))
      (is (= 2 (:count result)))
      (is (= #{:read :write}
             (set (map :id (:entries result)))))))

  (testing "list-entries exposes unordered membership/count only"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :write :ext/write {:name "write"}))
                         (register-entry :tools (sample-entry :read :ext/read {:name "read"}))
                         (register-entry :tools (sample-entry :list :ext/list {:name "list"})))
          result (:result (registry/list-entries root-state :tools))
          entries (:entries result)]
      (is (= true (:ok? result)))
      (is (= 3 (:count result)))
      (is (= 3 (count entries)))
      (is (= #{:write :read :list}
             (set (map :id entries))))
      (is (= entries (:value result)))
      (is (not (contains? result :order)))
      (is (not (contains? result :sorted?)))
      (is (not (contains? result :storage-order)))
      (is (sequential? entries)
          "Callers may observe a concrete sequential collection, but the contract is unordered membership/count only.")))

  (testing "listing an unknown registry fails explicitly"
    (let [result (:result (registry/list-entries (registry/empty-root-state) :tools))]
      (is (= false (:ok? result)))
      (is (= :unknown-registry (:failure-kind result))))))

(deftest insert-test
  ;; Tests duplicate-rejecting insertion semantics and validation.
  (testing "inserts a new entry into a declared registry"
    (let [{:keys [root-state result]}
          (registry/insert (registry/declared-root-state [:tools])
                           :tools
                           (sample-entry :read :ext/read {:name "read"}))]
      (is (= true (:ok? result)))
      (is (= :insert (:change result)))
      (is (= :insert (:operation result)))
      (is (= #{:read}
             (get-in root-state [:root-registries :tools :ids-by-extension :ext/read])))))

  (testing "duplicate insert with same owner fails with duplicate-id"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read" :version 1})))
          {:keys [root-state result]}
          (registry/insert root-state :tools (sample-entry :read :ext/read {:name "read" :version 2}))]
      (is (= false (:ok? result)))
      (is (= :duplicate-id (:failure-kind result)))
      (is (= :duplicate-id (:change result)))
      (is (= {:name "read" :version 1}
             (get-in root-state [:root-registries :tools :entries-by-id :read :value])))))

  (testing "duplicate insert with different owner also fails with duplicate-id"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/one {:name "read"})))
          {:keys [root-state result]}
          (registry/insert root-state :tools (sample-entry :read :ext/two {:name "read"}))]
      (is (= false (:ok? result)))
      (is (= :duplicate-id (:failure-kind result)))
      (is (= :ext/one
             (get-in root-state [:root-registries :tools :entries-by-id :read :extension-id])))))

  (testing "insert into unknown registry fails explicitly"
    (let [{:keys [root-state result]}
          (registry/insert (registry/empty-root-state)
                           :tools
                           (sample-entry :read :ext/read {:name "read"}))]
      (is (= false (:ok? result)))
      (is (= :unknown-registry (:failure-kind result)))
      (is (= {}
             (registry/registry-area root-state)))))

  (testing "invalid entries fail shared validation without mutating state"
    (doseq [entry [{:extension-id :ext/read :value {:name "read"}}
                   {:id :read :value {:name "read"}}
                   {:id :read :extension-id :ext/read}]]
      (let [{:keys [root-state result]}
            (registry/insert (registry/declared-root-state [:tools]) :tools entry)]
        (is (= false (:ok? result)))
        (is (= :invalid-entry (:failure-kind result)))
        (is (= {:entries-by-id {}
                :ids-by-extension {}}
               (registry/registry-state root-state :tools)))))))

(deftest register-test
  ;; Tests replace-capable registration, owner-conflict rejection, and validation.
  (testing "register inserts a new entry into a declared registry"
    (let [{:keys [root-state result]}
          (registry/register (registry/declared-root-state [:tools])
                             :tools
                             (sample-entry :read :ext/read {:name "read"}))]
      (is (= true (:ok? result)))
      (is (= :insert (:change result)))
      (is (= #{:read}
             (get-in root-state [:root-registries :tools :ids-by-extension :ext/read])))))

  (testing "re-register with same owner replaces prior entry"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read" :version 1})))
          {:keys [root-state result]}
          (registry/register root-state :tools (sample-entry :read :ext/read {:name "read" :version 2}))]
      (is (= true (:ok? result)))
      (is (= :replace (:change result)))
      (is (= {:id :read :extension-id :ext/read :value {:name "read" :version 1}}
             (:previous-entry result)))
      (is (= {:name "read" :version 2}
             (get-in root-state [:root-registries :tools :entries-by-id :read :value])))))

  (testing "re-register with different owner fails with ownership conflict"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/one {:name "read"})))
          {:keys [root-state result]}
          (registry/register root-state :tools (sample-entry :read :ext/two {:name "read"}))]
      (is (= false (:ok? result)))
      (is (= :ownership-conflict (:failure-kind result)))
      (is (= :ext/one
             (get-in root-state [:root-registries :tools :entries-by-id :read :extension-id])))))

  (testing "built-ins use artificial extension id"
    (let [{:keys [result]} (registry/register (registry/declared-root-state [:tools])
                                              :tools
                                              (sample-entry :delegate :built-in {:name "delegate"}))]
      (is (= true (:ok? result)))
      (is (= :built-in (:extension-id result)))))

  (testing "register into unknown registry fails explicitly"
    (let [{:keys [root-state result]}
          (registry/register (registry/empty-root-state)
                             :tools
                             (sample-entry :read :ext/read {:name "read"}))]
      (is (= false (:ok? result)))
      (is (= :unknown-registry (:failure-kind result)))
      (is (= {}
             (registry/registry-area root-state)))))

  (testing "invalid entries fail shared validation without mutating state"
    (doseq [entry [{:extension-id :ext/read :value {:name "read"}}
                   {:id :read :value {:name "read"}}
                   {:id :read :extension-id :ext/read}]]
      (let [{:keys [root-state result]}
            (registry/register (registry/declared-root-state [:tools]) :tools entry)]
        (is (= false (:ok? result)))
        (is (= :invalid-entry (:failure-kind result)))
        (is (= {:entries-by-id {}
                :ids-by-extension {}}
               (registry/registry-state root-state :tools)))))))

(deftest unregister-test
  ;; Tests targeted removal success, miss information, and index maintenance.
  (testing "unregister removes entry and keeps indexes in sync"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read"}))
                         (register-entry :tools (sample-entry :write :ext/read {:name "write"})))
          {:keys [root-state result]} (registry/unregister root-state :tools :read)]
      (is (= true (:ok? result)))
      (is (= :removed (:change result)))
      (is (nil? (get-in root-state [:root-registries :tools :entries-by-id :read])))
      (is (= #{:write}
             (get-in root-state [:root-registries :tools :ids-by-extension :ext/read])))))

  (testing "unregister miss returns failure info"
    (let [result (:result (registry/unregister (registry/declared-root-state [:tools]) :tools :missing))]
      (is (= false (:ok? result)))
      (is (= :not-found (:failure-kind result)))
      (is (= :miss (:change result)))))

  (testing "unregister against unknown registry fails explicitly"
    (let [result (:result (registry/unregister (registry/empty-root-state) :tools :read))]
      (is (= false (:ok? result)))
      (is (= :unknown-registry (:failure-kind result))))))

(deftest clear-by-extension-test
  ;; Tests bulk owner removal success and miss semantics.
  (testing "clear-by-extension removes all entries for one owner"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read"}))
                         (register-entry :tools (sample-entry :write :ext/read {:name "write"}))
                         (register-entry :tools (sample-entry :list :ext/list {:name "list"})))
          {:keys [root-state result]} (registry/clear-by-extension root-state :tools :ext/read)]
      (is (= true (:ok? result)))
      (is (= :removed (:change result)))
      (is (= 2 (:removed-count result)))
      (is (= #{:read :write}
             (set (:removed-ids result))))
      (is (= #{:list}
             (set (keys (get-in root-state [:root-registries :tools :entries-by-id])))))
      (is (nil? (get-in root-state [:root-registries :tools :ids-by-extension :ext/read])))))

  (testing "clear-by-extension with no matches returns explicit miss-style failure"
    (let [result (:result (registry/clear-by-extension (registry/declared-root-state [:tools])
                                                       :tools
                                                       :ext/read))]
      (is (= false (:ok? result)))
      (is (= :not-found (:failure-kind result)))
      (is (= 0 (:removed-count result)))))

  (testing "clear-by-extension against unknown registry fails explicitly"
    (let [result (:result (registry/clear-by-extension (registry/empty-root-state)
                                                       :tools
                                                       :ext/read))]
      (is (= false (:ok? result)))
      (is (= :unknown-registry (:failure-kind result))))))

(deftest clear-registry-test
  ;; Tests global clear removal and empty-registry no-op semantics.
  (testing "clear-registry removes all entries from a known registry"
    (let [root-state (-> (registry/declared-root-state [:tools])
                         (register-entry :tools (sample-entry :read :ext/read {:name "read"}))
                         (register-entry :tools (sample-entry :write :ext/write {:name "write"})))
          {:keys [root-state result]} (registry/clear-registry root-state :tools)]
      (is (= true (:ok? result)))
      (is (= :removed (:change result)))
      (is (= 2 (:removed-count result)))
      (is (= {:entries-by-id {}
              :ids-by-extension {}}
             (registry/registry-state root-state :tools)))))

  (testing "clear-registry on an already empty known registry is a successful no-op"
    (let [result (:result (registry/clear-registry (registry/declared-root-state [:tools]) :tools))]
      (is (= true (:ok? result)))
      (is (= :noop (:change result)))
      (is (= 0 (:removed-count result)))
      (is (= [] (:removed-ids result)))))

  (testing "clear-registry against unknown registry fails explicitly"
    (let [result (:result (registry/clear-registry (registry/empty-root-state) :tools))]
      (is (= false (:ok? result)))
      (is (= :unknown-registry (:failure-kind result))))))
