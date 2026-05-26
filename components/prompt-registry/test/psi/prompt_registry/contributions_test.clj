(ns psi.prompt-registry.contributions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.prompt-registry.contributions :as contributions]))

(deftest normalize-identity-test
  (testing "canonical identity is string-coerced id alone"
    (is (= {:id "c1"}
           (contributions/normalize-identity "/ext/a" "c1")))
    (is (= {:id "c1"}
           (contributions/normalize-identity "/ext/b" "c1")))
    (is (= {:id ""}
           (contributions/normalize-identity nil nil)))
    (is (= {:id ""}
           (contributions/normalize-identity "" "")))))

(deftest normalize-contribution-test
  (let [c (contributions/normalize-contribution "/ext/a" "c1"
                                                {:section :capabilities
                                                 :content 42
                                                 :priority nil})]
    (is (= "c1" (:id c)))
    (is (= "/ext/a" (:ext-path c)))
    (is (= ":capabilities" (:section c)))
    (is (= "42" (:content c)))
    (is (= 1000 (:priority c)))
    (is (true? (:enabled c)))
    (is (inst? (:created-at c)))
    (is (inst? (:updated-at c)))
    (is (= (:created-at c) (:updated-at c)))))

(deftest merge-contribution-patch-test
  (let [created (java.time.Instant/parse "2026-05-07T12:00:00Z")
        updated (java.time.Instant/parse "2026-05-07T12:10:00Z")
        existing {:id "c1" :ext-path "/ext/a" :section "One" :content "A"
                  :priority 10 :enabled true :created-at created :updated-at updated}
        patched (contributions/merge-contribution-patch existing
                                                        {:section :two
                                                         :content 99
                                                         :priority nil
                                                         :enabled false
                                                         :id "ignored"
                                                         :ext-path "/ignored"
                                                         :created-at "ignored"
                                                         :extra :ignored})]
    (is (= "c1" (:id patched)))
    (is (= "/ext/a" (:ext-path patched)))
    (is (= ":two" (:section patched)))
    (is (= "99" (:content patched)))
    (is (= 1000 (:priority patched)))
    (is (false? (:enabled patched)))
    (is (= created (:created-at patched)))
    (is (inst? (:updated-at patched)))
    (is (not= updated (:updated-at patched)))
    (is (nil? (:extra patched)))))

(deftest query-helpers-test
  (let [xs [{:id "b" :ext-path "/ext/b" :priority 20}
            {:id "a" :ext-path "/ext/a" :priority 20}
            {:id "z" :ext-path "/ext/a" :priority 10}
            :skip-me]]
    (is (= 3 (contributions/contribution-count xs)))
    (is (= [{:id "b" :ext-path "/ext/b" :priority 20}
            {:id "a" :ext-path "/ext/a" :priority 20}
            {:id "z" :ext-path "/ext/a" :priority 10}]
           (contributions/all-contributions xs)))
    (is (= {:id "a" :ext-path "/ext/a" :priority 20}
           (contributions/find-contribution xs "/ext/a" "a")))
    (is (= {:id "a" :ext-path "/ext/a" :priority 20}
           (contributions/find-contribution xs "/ext/missing" "a")))
    (is (nil? (contributions/find-contribution xs "/ext/missing" "missing")))
    (is (= ["z" "a" "b"]
           (mapv :id (contributions/sort-contributions xs))))))

(deftest register-contribution-test
  (testing "adds new contributions with explicit result details"
    (let [result (contributions/register-contribution [] "/ext/a" "c1"
                                                      {:section "Capabilities"
                                                       :content "tool: x"
                                                       :priority 10})]
      (is (true? (:registered? result)))
      (is (false? (:replaced? result)))
      (is (true? (:changed? result)))
      (is (= 1 (:count result)))
      (is (= 1 (count (:contributions result))))
      (is (= (:contribution result)
             (first (:contributions result))))))

  (testing "same-owner replacement resets created-at because canonical register rebuilds stored shape"
    (let [first-result (contributions/register-contribution [] "/ext/a" "c1" {:content "A"})
          original     (:contribution first-result)
          second-result (contributions/register-contribution (:contributions first-result)
                                                             "/ext/a" "c1" {:content "B"})
          replacement  (:contribution second-result)]
      (is (true? (:registered? second-result)))
      (is (true? (:replaced? second-result)))
      (is (= 1 (:count second-result)))
      (is (= "B" (:content replacement)))
      (is (= "/ext/a" (:ext-path replacement)))
      (is (not= (:created-at original) (:created-at replacement)))
      (is (= (:created-at replacement) (:updated-at replacement)))))

  (testing "cross-owner duplicate registration throws explicit ownership conflict"
    (let [registered (contributions/register-contribution [] "/ext/a" "c1" {:content "A"})]
      (try
        (contributions/register-contribution (:contributions registered) "/ext/b" "c1" {:content "B"})
        (is false "expected ownership conflict")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :prompt-contribution/ownership-conflict
                 (:kind (ex-data ex))))
          (is (= "c1" (:id (ex-data ex))))
          (is (= "/ext/a" (:owner (ex-data ex))))
          (is (= "/ext/b" (:requested-owner (ex-data ex))))))))

  (testing "current first-cut behavior preserves loose id coercion"
    (let [result (contributions/register-contribution [] nil nil {:content "A"})]
      (is (= "" (:ext-path (:contribution result))))
      (is (= "" (:id (:contribution result)))))))

(deftest update-contribution-test
  (testing "miss returns false with unchanged count and nil contribution"
    (let [result (contributions/update-contribution [] "/ext/a" "missing" {:content "B"})]
      (is (false? (:updated? result)))
      (is (false? (:changed? result)))
      (is (nil? (:contribution result)))
      (is (= 0 (:count result)))
      (is (= [] (:contributions result)))))

  (testing "owner mismatch does not update a contribution owned by another extension"
    (let [registered (contributions/register-contribution [] "/ext/a" "c1" {:content "A"})
          result     (contributions/update-contribution (:contributions registered)
                                                        "/ext/b" "c1"
                                                        {:content "B"})]
      (is (false? (:updated? result)))
      (is (false? (:changed? result)))
      (is (nil? (:contribution result)))
      (is (= 1 (:count result)))))

  (testing "hit updates only patchable fields and preserves created-at"
    (let [registered (contributions/register-contribution [] "/ext/a" "c1" {:content "A" :priority 10})
          before     (:contribution registered)
          result     (contributions/update-contribution (:contributions registered)
                                                        "/ext/a" "c1"
                                                        {:section :capabilities
                                                         :content "B"
                                                         :priority nil
                                                         :enabled false
                                                         :id "ignored"
                                                         :ext-path "/ignored"
                                                         :created-at "ignored"
                                                         :unknown :ignored})
          after      (:contribution result)]
      (is (true? (:updated? result)))
      (is (true? (:changed? result)))
      (is (= 1 (:count result)))
      (is (= "c1" (:id after)))
      (is (= "/ext/a" (:ext-path after)))
      (is (= ":capabilities" (:section after)))
      (is (= "B" (:content after)))
      (is (= 1000 (:priority after)))
      (is (false? (:enabled after)))
      (is (= (:created-at before) (:created-at after)))
      (is (not= (:updated-at before) (:updated-at after)))
      (is (nil? (:unknown after))))))

(deftest unregister-contribution-test
  (testing "miss returns false with unchanged count"
    (let [result (contributions/unregister-contribution [] "/ext/a" "missing")]
      (is (false? (:removed? result)))
      (is (false? (:changed? result)))
      (is (nil? (:contribution result)))
      (is (= 0 (:count result)))))

  (testing "owner mismatch does not remove a contribution owned by another extension"
    (let [registered (contributions/register-contribution [] "/ext/a" "c1" {:content "A"})
          result     (contributions/unregister-contribution (:contributions registered) "/ext/b" "c1")]
      (is (false? (:removed? result)))
      (is (false? (:changed? result)))
      (is (nil? (:contribution result)))
      (is (= 1 (:count result)))))

  (testing "hit removes the contribution and returns removed detail"
    (let [registered (contributions/register-contribution [] "/ext/a" "c1" {:content "A"})
          original   (:contribution registered)
          result     (contributions/unregister-contribution (:contributions registered) "/ext/a" "c1")]
      (is (true? (:removed? result)))
      (is (true? (:changed? result)))
      (is (= original (:contribution result)))
      (is (= 0 (:count result)))
      (is (= [] (:contributions result))))))