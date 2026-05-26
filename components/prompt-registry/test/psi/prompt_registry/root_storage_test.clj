(ns psi.prompt-registry.root-storage-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.prompt-registry.root-storage :as root-storage]))

(defn- base-root-state
  []
  {:agent-session {:sessions {"s1" {:data {:session-id "s1"
                                           :prompt-contribution-ids []}}}}})

(deftest register-update-unregister-through-root-registry-test
  (testing "register stores canonical entries in root-registry and session membership by id"
    (let [result (root-storage/register-contribution-in-root-state
                  (base-root-state) "s1" "/ext/a" "c1" {:content "A" :priority 20})
          root-state (:root-state result)]
      (is (= ["c1"] (get-in root-state [:agent-session :sessions "s1" :data :prompt-contribution-ids])))
      (is (= "A"
             (get-in root-state [:root-registries :prompt-contributions :entries-by-id "c1" :value :content])))
      (is (= "/ext/a"
             (get-in root-state [:root-registries :prompt-contributions :entries-by-id "c1" :extension-id])))
      (is (true? (:registered? result)))
      (is (false? (:replaced? result)))))

  (testing "update preserves created-at and writes back through root-registry"
    (let [registered (root-storage/register-contribution-in-root-state
                      (base-root-state) "s1" "/ext/a" "c1" {:content "A"})
          before     (get-in (:root-state registered)
                             [:root-registries :prompt-contributions :entries-by-id "c1" :value])
          updated    (root-storage/update-contribution-in-root-state
                      (:root-state registered) "s1" "/ext/a" "c1" {:content "B" :created-at "ignored"})
          after      (get-in (:root-state updated)
                             [:root-registries :prompt-contributions :entries-by-id "c1" :value])]
      (is (true? (:updated? updated)))
      (is (= "B" (:content after)))
      (is (= (:created-at before) (:created-at after)))
      (is (not= (:updated-at before) (:updated-at after)))))

  (testing "unregister removes root-registry entry and session membership"
    (let [registered (root-storage/register-contribution-in-root-state
                      (base-root-state) "s1" "/ext/a" "c1" {:content "A"})
          removed    (root-storage/unregister-contribution-in-root-state
                      (:root-state registered) "s1" "/ext/a" "c1")
          root-state (:root-state removed)]
      (is (true? (:removed? removed)))
      (is (= [] (get-in root-state [:agent-session :sessions "s1" :data :prompt-contribution-ids])))
      (is (nil? (get-in root-state [:root-registries :prompt-contributions :entries-by-id "c1"]))))))

(deftest list-contributions-reads-root-authority-not-session-vector-test
  (let [registered (root-storage/register-contribution-in-root-state
                    (base-root-state) "s1" "/ext/a" "b" {:content "B" :priority 20})
        registered (root-storage/register-contribution-in-root-state
                    (:root-state registered) "s1" "/ext/a" "a" {:content "A" :priority 10})
        stale-sd    {:session-id "s1"
                     :prompt-contribution-ids ["b" "a"]}
        listed      (root-storage/list-contributions (:root-state registered) stale-sd)]
    (is (= ["a" "b"] (mapv :id listed)))
    (is (= ["A" "B"] (mapv :content listed)))))
