(ns psi.launcher.extensions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.launcher.extensions :as ext]))

(defn- ex-data-for
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest read-manifest-file-test
  (testing "missing manifest yields empty deps"
    (is (= {:deps {}}
           (ext/read-manifest-file "/tmp/psi-launcher-does-not-exist.edn"))))
  (testing "reads valid manifest"
    (let [f (doto (java.io.File/createTempFile "psi-launcher" ".edn")
              (.deleteOnExit))]
      (spit f "{:deps {foo/bar {:mvn/version \"1.0.0\"}}}")
      (is (= '{:deps {foo/bar {:mvn/version "1.0.0"}}}
             (ext/read-manifest-file f)))))
  (testing "malformed manifest fails clearly"
    (let [f (doto (java.io.File/createTempFile "psi-launcher" ".edn")
              (.deleteOnExit))]
      (spit f "[:not-a-map]")
      (is (= {:stage :manifest-read
              :file (.getAbsolutePath f)
              :problem :manifest-not-map}
             (ex-data-for #(ext/read-manifest-file f)))))))

(deftest merge-manifests-test
  (is (= '{:deps {foo/user {:mvn/version "1.0.0"}
                  shared/lib {:mvn/version "2.0.0"}
                  bar/project {:mvn/version "3.0.0"}}}
         (ext/merge-manifests
          '{:deps {foo/user {:mvn/version "1.0.0"}
                   shared/lib {:mvn/version "1.0.0"}}}
          '{:deps {shared/lib {:mvn/version "2.0.0"}
                   bar/project {:mvn/version "3.0.0"}}}))))

(deftest psi-owned-catalog-test
  (testing "recognized libs are catalogued explicitly"
    (is (ext/recognized-psi-owned-lib? 'psi/mementum))
    (is (ext/recognized-psi-owned-lib? 'psi/workflow-loader))
    (is (not (ext/recognized-psi-owned-lib? 'third-party/ext))))
  (testing "catalog entry contains explicit init and installed policy"
    (is (= 'extensions.mementum/init
           (get-in ext/psi-owned-extension-catalog ['psi/mementum :psi/init])))
    (is (= "extensions/mementum"
           (get-in ext/psi-owned-extension-catalog ['psi/mementum :source-policies :installed :local/root]))))
  (testing "catalog entry contains jar policy with release-version placeholder"
    (is (= :psi/release-version
           (get-in ext/psi-owned-extension-catalog ['psi/mementum :source-policies :jar :mvn/version])))
    (is (= :psi/release-version
           (get-in ext/psi-owned-extension-catalog ['psi/workflow-loader :source-policies :jar :mvn/version])))))

(deftest expand-entry-test
  (testing "recognized psi-owned entry expands from minimal syntax"
    (is (= '{:local/root "extensions/mementum"
             :psi/init extensions.mementum/init}
           (ext/expand-entry 'psi/mementum {}))))
  (testing "explicit fields override catalog defaults field-by-field"
    (is (= '{:local/root "override-root"
             :psi/init extensions.mementum/init}
           (ext/expand-entry 'psi/mementum {:local/root "override-root"}))))
  (testing "explicit init overrides inferred init"
    (is (= 'custom.mementum/init
           (:psi/init (ext/expand-entry 'psi/mementum {:local/root "override-root"
                                                       :psi/init 'custom.mementum/init})))))
  (testing "jar policy expands to release-version placeholder"
    (is (= {:mvn/version :psi/release-version
            :psi/init 'extensions.mementum/init}
           (ext/expand-entry 'psi/mementum {} {:policy :jar}))))
  (testing "unrecognized libs do not receive psi-owned defaults"
    (is (= '{:mvn/version "1.2.3"}
           (ext/expand-entry 'third-party/ext {:mvn/version "1.2.3"}))))
  (testing "unrecognized empty minimal syntax fails clearly"
    (is (= {:stage :validation
            :lib 'third-party/ext
            :dep {}}
           (ex-data-for #(ext/expand-entry 'third-party/ext {})))))
  (testing "conflicting coordinate families fail clearly"
    (is (= {:stage :validation
            :lib 'third-party/ext
            :dep {:mvn/version "1.2.3"
                  :git/url "https://example.com/ext.git"
                  :git/tag "v1"}}
           (ex-data-for #(ext/expand-entry 'third-party/ext {:mvn/version "1.2.3"
                                                             :git/url "https://example.com/ext.git"
                                                             :git/tag "v1"}))))))

(deftest expand-manifest-test
  (is (= '{:deps {psi/mementum {:local/root "extensions/mementum"
                                :psi/init extensions.mementum/init}
                  third-party/ext {:mvn/version "1.0.0"}}}
         (ext/expand-manifest
          '{:deps {psi/mementum {}
                   third-party/ext {:mvn/version "1.0.0"}}}))))

(deftest manifest-expansion-report-test
  (testing "minimal psi-owned entry reports defaults and inferred init"
    (is (= {:expanded-manifest '{:deps {psi/mementum {:local/root "extensions/mementum"
                                                      :psi/init extensions.mementum/init}}}
            :defaulted-libs ['psi/mementum]
            :inferred-init-libs ['psi/mementum]}
           (select-keys
            (ext/manifest-expansion-report '{:deps {psi/mementum {}}})
            [:expanded-manifest :defaulted-libs :inferred-init-libs]))))
  (testing "partially explicit psi-owned entry still reports defaults and inferred init"
    (is (= {:expanded-manifest '{:deps {psi/mementum {:local/root "override-root"
                                                      :psi/init extensions.mementum/init}}}
            :defaulted-libs []
            :inferred-init-libs ['psi/mementum]}
           (select-keys
            (ext/manifest-expansion-report '{:deps {psi/mementum {:local/root "override-root"}}})
            [:expanded-manifest :defaulted-libs :inferred-init-libs]))))
  (testing "explicit init suppresses inferred-init reporting"
    (is (= []
           (:inferred-init-libs
            (ext/manifest-expansion-report
             '{:deps {psi/mementum {:local/root "override-root"
                                    :psi/init custom.mementum/init}}})))))
  (testing "jar policy expands psi-owned entry to release-version placeholder"
    (is (= {:expanded-manifest '{:deps {psi/mementum {:mvn/version :psi/release-version
                                                      :psi/init extensions.mementum/init}}}
            :defaulted-libs ['psi/mementum]
            :inferred-init-libs ['psi/mementum]}
           (select-keys
            (ext/manifest-expansion-report '{:deps {psi/mementum {}}} {:policy :jar})
            [:expanded-manifest :defaulted-libs :inferred-init-libs])))))
