(ns psi.ai.model-selection-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [psi.ai.model-registry :as registry]
   [psi.ai.model-selection :as sut]))

(use-fixtures :each
  (fn [f]
    (try
      (f)
      (finally
        (registry/init! {})))))

(defn- write-temp-models! [config]
  (let [tmp (java.io.File/createTempFile "psi-test-models" ".edn")]
    (spit tmp (pr-str config))
    (.getAbsolutePath tmp)))

(def local-provider-config
  {:version   1
   :providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :auth     {:api-key "test-key" :auth-header? false}
                :models   [{:id   "test-model"
                            :name "Test Model"
                            :context-window 32768}]}}})

(def no-auth-provider-config
  {:version   1
   :providers {"public"
               {:base-url "https://example.com/v1"
                :api      :openai-completions
                :models   [{:id "public-model"}]}}})

(def local-helper-provider-config
  {:version   1
   :providers {"local-helper"
               {:base-url "http://localhost:11434/v1"
                :api      :openai-completions
                :auth     {:auth-header? false}
                :models   [{:id "fast-free"
                            :name "Fast Free Local"
                            :supports-text true
                            :locality :local
                            :latency-tier :low
                            :cost-tier :zero
                            :input-cost 0.0
                            :output-cost 0.0}]}}})

(deftest catalog-view-built-ins-test
  (registry/init! {})
  (let [catalog (:candidates (sut/catalog-view))
        candidate (sut/find-candidate (sut/catalog-view) :anthropic "claude-sonnet-4-6")]
    (testing "catalog view exposes deterministic candidates"
      (is (seq catalog))
      (is (= catalog (sort-by (juxt :provider :id) catalog))))

    (testing "built-in candidate exposes facts, estimates, and configured reference"
      (is (some? candidate))
      (is (= :anthropic (:provider candidate)))
      (is (= "claude-sonnet-4-6" (:id candidate)))
      (is (= :anthropic-messages (:api candidate)))
      (is (= true (get-in candidate [:facts :supports-text])))
      (is (= true (get-in candidate [:facts :supports-reasoning])))
      (is (= :cloud (get-in candidate [:facts :locality])))
      (is (= :low (get-in candidate [:estimates :latency-tier])))
      (is (= :medium (get-in candidate [:estimates :cost-tier])))
      (is (pos-int? (get-in candidate [:facts :context-window])))
      (is (number? (get-in candidate [:estimates :input-cost])))
      (is (true? (get-in candidate [:reference :configured?]))))))

(deftest catalog-view-custom-models-test
  (let [path (write-temp-models! local-provider-config)]
    (try
      (registry/init! {:user-models-path path})
      (let [catalog (sut/catalog-view)
            candidate (sut/find-candidate catalog :local "test-model")]
        (testing "custom candidate is present"
          (is (some? candidate))
          (is (= "Test Model" (:name candidate)))
          (is (= :openai-completions (:api candidate)))
          (is (= 32768 (get-in candidate [:facts :context-window]))))

        (testing "custom provider auth contributes to reference viability"
          (is (true? (get-in candidate [:reference :configured?]))))

        (testing "custom model defaults include local helper-friendly metadata"
          (is (= :local (get-in candidate [:facts :locality])))
          (is (= :low (get-in candidate [:estimates :latency-tier])))
          (is (= :zero (get-in candidate [:estimates :cost-tier])))))
      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest catalog-view-unconfigured-provider-test
  (let [path (write-temp-models! no-auth-provider-config)]
    (try
      (registry/init! {:user-models-path path})
      (let [catalog (sut/catalog-view)
            candidate (sut/find-candidate catalog :public "public-model")]
        (testing "provider without auth remains visible"
          (is (some? candidate))
          (is (= "public-model" (:id candidate))))

        (testing "missing auth marks reference as not configured"
          (is (false? (get-in candidate [:reference :configured?])))))
      (finally
        (java.io.File/.delete (java.io.File. path))))))

(deftest role-defaults-test
  (testing "known roles expose default bundles"
    (is (= :auto-session-name (:role (sut/role-defaults :auto-session-name))))
    (is (= [{:criterion :supports-text :match :true}]
           (:required (sut/role-defaults :auto-session-name))))
    (is (= [{:criterion :same-provider-as-session :prefer :context-match}]
           (:weak-preferences (sut/role-defaults :auto-session-name)))))

  (testing "unknown roles return nil"
    (is (nil? (sut/role-defaults :does-not-exist)))))

(deftest criterion-identity-test
  (testing "explicit identity wins"
    (is (= :cheapness
           (sut/criterion-identity {:identity :cheapness
                                    :criterion :input-cost
                                    :prefer :lower}))))

  (testing "criterion key is used when present"
    (is (= :input-cost
           (sut/criterion-identity {:criterion :input-cost
                                    :prefer :lower}))))

  (testing "attribute is fallback identity"
    (is (= :provider-family
           (sut/criterion-identity {:attribute :provider-family
                                    :prefer :equals-context})))))

(deftest normalize-request-test
  (testing "defaults to resolve/interactive with normalized collections"
    (let [request (sut/normalize-request {})]
      (is (= :resolve (:mode request)))
      (is (= :interactive (:role request)))
      (is (= [] (:required request)))
      (is (= [] (:strong-preferences request)))
      (is (= [] (:weak-preferences request)))
      (is (= {} (:context request)))))

  (testing "role defaults are merged underneath caller request"
    (let [request (sut/normalize-request {:role :auto-session-name})]
      (is (= :auto-session-name (:role request)))
      (is (= [{:criterion :supports-text :match :true}]
             (:required request)))
      (is (= [{:criterion :input-cost :prefer :lower}
              {:criterion :output-cost :prefer :lower}]
             (:strong-preferences request)))
      (is (= [{:criterion :same-provider-as-session :prefer :context-match}]
             (:weak-preferences request)))))

  (testing "caller-supplied criteria replace role defaults for the same fields in v1"
    (let [request (sut/normalize-request {:role :auto-session-name
                                          :required [{:criterion :supports-reasoning
                                                      :match :true}]
                                          :weak-preferences [{:criterion :same-model-as-session
                                                              :prefer :context-match}]
                                          :context {:session-provider :anthropic}})]
      (is (= [{:criterion :supports-reasoning :match :true}]
             (:required request)))
      (is (= [{:criterion :same-model-as-session :prefer :context-match}]
             (:weak-preferences request)))
      (is (= {:session-provider :anthropic}
             (:context request)))))

  (testing "explicit model request normalizes provider to keyword"
    (let [request (sut/normalize-request {:mode :explicit
                                          :model {:provider "openai"
                                                  :id "gpt-5-mini"}})]
      (is (= :explicit (:mode request)))
      (is (= {:provider :openai :id "gpt-5-mini"}
             (:model request))))))

(deftest compose-effective-request-test
  (testing "role defaults seed the effective request"
    (let [request (sut/compose-effective-request {:request {:role :auto-session-name}})]
      (is (= :resolve (:mode request)))
      (is (= :auto-session-name (:role request)))
      (is (= [{:criterion :supports-text :match :true}]
             (:required request)))
      (is (= [{:criterion :input-cost :prefer :lower}
              {:criterion :output-cost :prefer :lower}]
             (:strong-preferences request)))))

  (testing "required constraints union across policy layers in precedence order"
    (let [request (sut/compose-effective-request
                   {:system  {:required [{:criterion :supports-text :match :true}]}
                    :project {:required [{:criterion :configured? :match :true}]}
                    :request {:role :interactive
                              :required [{:criterion :context-window :at-least 64000}]}})]
      (is (= [{:criterion :supports-text :match :true}
              {:criterion :configured? :match :true}
              {:criterion :context-window :at-least 64000}]
             (:required request)))))

  (testing "preference tiers replace same-identity criteria with higher-precedence instances"
    (let [request (sut/compose-effective-request
                   {:system  {:strong-preferences [{:criterion :input-cost :prefer :lower}
                                                   {:criterion :output-cost :prefer :lower}]}
                    :project {:strong-preferences [{:criterion :input-cost :prefer :higher}]}
                    :request {:role :interactive
                              :strong-preferences [{:criterion :context-window :prefer :higher}]}})]
      (is (= [{:criterion :input-cost :prefer :higher}
              {:criterion :output-cost :prefer :lower}
              {:criterion :context-window :prefer :higher}]
             (:strong-preferences request)))))

  (testing "context maps merge shallowly by precedence"
    (let [request (sut/compose-effective-request
                   {:system  {:context {:session-provider :anthropic
                                        :region :global}}
                    :project {:context {:region :eu}}
                    :request {:role :interactive
                              :context {:session-model-id "claude-sonnet-4-6"}}})]
      (is (= {:session-provider :anthropic
              :region :eu
              :session-model-id "claude-sonnet-4-6"}
             (:context request)))))

  (testing "highest-precedence explicit model wins"
    (let [request (sut/compose-effective-request
                   {:system  {:model {:provider :anthropic :id "claude-sonnet-4-6"}}
                    :project {:model {:provider :openai :id "gpt-5-mini"}}
                    :request {:mode :explicit
                              :model {:provider "openai" :id "gpt-5"}}})]
      (is (= :explicit (:mode request)))
      (is (= {:provider :openai :id "gpt-5"}
             (:model request))))))

(deftest filter-candidates-test
  (registry/init! {:user-models-path (write-temp-models! no-auth-provider-config)})
  (let [catalog (sut/catalog-view)]
    (testing "resolve mode filters across full catalog by required constraints"
      (let [request (sut/compose-effective-request
                     {:request {:role :interactive
                                :required [{:criterion :configured? :match :true}
                                           {:criterion :context-window :at-least 200000}]}})
            result  (sut/filter-candidates catalog request)]
        (is (seq (:pool result)))
        (is (seq (:survivors result)))
        (is (every? #(true? (get-in % [:reference :configured?]))
                    (:survivors result)))
        (is (every? #(>= (get-in % [:facts :context-window]) 200000)
                    (:survivors result)))
        (is (some #(= "public-model" (get-in % [:candidate :id]))
                  (:eliminated result)))))

    (testing "explicit mode restricts pool to the requested model before filtering"
      (let [request (sut/compose-effective-request
                     {:request {:mode :explicit
                                :model {:provider :anthropic :id "claude-sonnet-4-6"}
                                :required [{:criterion :supports-reasoning :match :true}]}})
            result  (sut/filter-candidates catalog request)]
        (is (= 1 (count (:pool result))))
        (is (= ["claude-sonnet-4-6"] (mapv :id (:survivors result))))))

    (testing "explicit mode yields empty pool when reference does not exist"
      (let [request (sut/compose-effective-request
                     {:request {:mode :explicit
                                :model {:provider :openai :id "does-not-exist"}}})
            result  (sut/filter-candidates catalog request)]
        (is (empty? (:pool result)))
        (is (empty? (:survivors result)))
        (is (empty? (:eliminated result)))))

    (testing "inherit-session mode restricts pool to session-model context"
      (let [request (sut/compose-effective-request
                     {:request {:mode :inherit-session
                                :context {:session-model {:provider :anthropic
                                                          :id "claude-sonnet-4-6"}}
                                :required [{:criterion :supports-reasoning :match :true}]}})
            result  (sut/filter-candidates catalog request)]
        (is (= 1 (count (:pool result))))
        (is (= ["claude-sonnet-4-6"] (mapv :id (:survivors result))))))

    (testing "failed required constraints are attached as elimination reasons"
      (let [request (sut/compose-effective-request
                     {:request {:mode :explicit
                                :model {:provider :public :id "public-model"}
                                :required [{:criterion :configured? :match :true}
                                           {:criterion :context-window :at-least 200000}]}})
            result  (sut/filter-candidates catalog request)
            eliminated (first (:eliminated result))]
        (is (empty? (:survivors result)))
        (is (= "public-model" (get-in eliminated [:candidate :id])))
        (is (= [{:criterion :configured? :match :true}
                {:criterion :context-window :at-least 200000}]
               (:reasons eliminated)))))

    (testing "required constraints support locality and tier membership"
      (let [request (sut/compose-effective-request
                     {:request {:mode :resolve
                                :required [{:criterion :locality :equals :local}
                                           {:criterion :latency-tier :equals :low}
                                           {:criterion :cost-tier :one-of [:zero :low]}]}})
            result  (sut/filter-candidates catalog request)]
        (is (seq (:survivors result)))
        (is (every? #(= :local (get-in % [:facts :locality]))
                    (:survivors result)))
        (is (every? #(= :low (get-in % [:estimates :latency-tier]))
                    (:survivors result)))
        (is (every? #(contains? #{:zero :low} (get-in % [:estimates :cost-tier]))
                    (:survivors result)))))))

(deftest rank-candidates-test
  (registry/init! {:user-models-path (write-temp-models! no-auth-provider-config)})
  (let [catalog (sut/catalog-view)]
    (testing "strong preferences dominate weak preferences"
      (let [request (sut/compose-effective-request
                     {:request {:role :interactive
                                :strong-preferences [{:criterion :input-cost :prefer :lower}]
                                :weak-preferences [{:criterion :same-provider-as-session
                                                    :prefer :context-match}]
                                :context {:session-provider :anthropic}}})
            survivors [(sut/find-candidate catalog :anthropic "claude-3-5-haiku-20241022")
                       (sut/find-candidate catalog :openai "gpt-5.3-codex-spark")]
            ranked   (sut/rank-candidates request survivors)]
        (is (= ["claude-3-5-haiku-20241022" "gpt-5.3-codex-spark"]
               (mapv :id (:ranked ranked))))
        (is (false? (:ambiguous? ranked)))))

    (testing "weak context-match preference breaks ties when strong preferences tie"
      (let [request (sut/compose-effective-request
                     {:request {:role :interactive
                                :strong-preferences [{:criterion :input-cost :prefer :lower}]
                                :weak-preferences [{:criterion :same-provider-as-session
                                                    :prefer :context-match}]
                                :context {:session-provider :openai}}})
            survivors [(sut/find-candidate catalog :anthropic "claude-3-5-haiku-20241022")
                       (sut/find-candidate catalog :openai "gpt-5.3-codex-spark")]
            ranked    (sut/rank-candidates request survivors)]
        (is (= ["claude-3-5-haiku-20241022" "gpt-5.3-codex-spark"]
               (mapv :id (:ranked ranked))))
        (is (false? (:ambiguous? ranked)))))

    (testing "canonical provider/id tie-break is deterministic when preferences fully tie"
      (let [request (sut/compose-effective-request {:request {:role :interactive}})
            survivors [(sut/find-candidate catalog :anthropic "claude-sonnet-4-6")
                       (sut/find-candidate catalog :openai "gpt-5")]
            ranked    (sut/rank-candidates request survivors)]
        (is (= ["claude-sonnet-4-6" "gpt-5"]
               (mapv :id (:ranked ranked))))
        (is (true? (:ambiguous? ranked)))))))

(deftest resolve-selection-test
  (registry/init! {:user-models-path (write-temp-models! no-auth-provider-config)})
  (let [catalog (sut/catalog-view)]
    (testing "ok outcome returns the selected candidate and ambiguity flag"
      (let [result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :explicit
                               :model {:provider :anthropic :id "claude-sonnet-4-6"}}})]
        (is (= :ok (:outcome result)))
        (is (= "claude-sonnet-4-6" (get-in result [:candidate :id])))
        (is (false? (:ambiguous? result)))))

    (testing "explicit missing reference yields no-winner/reference-not-found"
      (let [result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :explicit
                               :model {:provider :openai :id "does-not-exist"}}})]
        (is (= :no-winner (:outcome result)))
        (is (= :reference-not-found (:reason result)))
        (is (empty? (get-in result [:filtering :pool])))))

    (testing "required filtering failure yields no-winner/required-constraints-unsatisfied"
      (let [result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :explicit
                               :model {:provider :public :id "public-model"}
                               :required [{:criterion :configured? :match :true}]}})]
        (is (= :no-winner (:outcome result)))
        (is (= :required-constraints-unsatisfied (:reason result)))
        (is (empty? (get-in result [:filtering :survivors])))))

    (testing "fully tied top candidates surface ambiguity on ok outcomes"
      (let [result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :resolve
                               :strong-preferences []
                               :weak-preferences []}})]
        (is (= :ok (:outcome result)))
        (is (boolean? (:ambiguous? result)))))))

(deftest trace-projection-test
  (registry/init! {:user-models-path (write-temp-models! no-auth-provider-config)})
  (let [catalog (sut/catalog-view)]
    (testing "ok results expose both short and full traces"
      (let [result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :explicit
                               :model {:provider :anthropic :id "claude-sonnet-4-6"}}})
            short  (sut/short-trace result)
            full   (sut/full-trace result)]
        (is (= short (get-in result [:trace :short])))
        (is (= full (get-in result [:trace :full])))
        (is (= :ok (:outcome short)))
        (is (= {:provider :anthropic
                :id "claude-sonnet-4-6"
                :name "Claude Sonnet 4.6"}
               (:selected short)))
        (is (= :ok (:outcome full)))
        (is (= "claude-sonnet-4-6" (get-in full [:candidate :id])))))

    (testing "no-winner traces retain reason and filtering evidence"
      (let [result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :explicit
                               :model {:provider :openai :id "does-not-exist"}}})
            short  (sut/short-trace result)
            full   (sut/full-trace result)]
        (is (= :no-winner (:outcome short)))
        (is (= :reference-not-found (:reason short)))
        (is (= :no-winner (:outcome full)))
        (is (= :reference-not-found (:reason full)))
        (is (empty? (get-in full [:filtering :pool])))))))

(deftest local-helper-candidate-wins-under-local-first-low-latency-low-cost-policy-test
  (let [path (write-temp-models! local-helper-provider-config)]
    (try
      (registry/init! {:user-models-path path})
      (let [catalog (sut/catalog-view)
            result (sut/resolve-selection
                    {:catalog catalog
                     :request {:mode :resolve
                               :required [{:criterion :supports-text :match :true}
                                          {:criterion :latency-tier :equals :low}
                                          {:criterion :cost-tier :one-of [:zero :low]}]
                               :strong-preferences [{:criterion :locality
                                                     :prefer :equals
                                                     :equals :local}
                                                    {:criterion :input-cost
                                                     :prefer :lower}
                                                    {:criterion :output-cost
                                                     :prefer :lower}]
                               :weak-preferences [{:criterion :same-provider-as-session
                                                   :prefer :context-match}]
                               :context {:session-model {:provider :anthropic
                                                         :id "claude-sonnet-4-6"}}}})]
        (is (= :ok (:outcome result)))
        (is (= :local-helper (:provider (:candidate result))))
        (is (= "fast-free" (:id (:candidate result))))
        (is (= :local (get-in result [:candidate :facts :locality]))))
      (finally
        (java.io.File/.delete (java.io.File. path))))))
