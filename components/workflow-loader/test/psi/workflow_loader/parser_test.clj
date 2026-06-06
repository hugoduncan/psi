(ns psi.workflow-loader.parser-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.workflow-loader.parser :as parser]))

(deftest parse-markdown-workflow-file-test
  (testing "single-step markdown workflow parses required frontmatter and body"
    (let [raw "---\nname: planner\ndescription: Plans tasks\nmodel: gpt-5\ntools:\n  - read\nskills:\n  - clojure-coding-standards\nresponse-mode: :non-streaming\nthinking-level: :off\nsession-profile: :planning\nlogprobs: true\ntop-logprobs: 4\n---\nYou are a planner."
          result (parser/parse-workflow-file :md raw)]
      (is (= :single-step-markdown (:workflow-kind result)))
      (is (= "planner" (:name result)))
      (is (= "Plans tasks" (:description result)))
      (is (= "gpt-5" (get-in result [:session-config :model])))
      (is (= ["read"] (get-in result [:session-config :tools])))
      (is (= ["clojure-coding-standards"] (get-in result [:session-config :skills])))
      (is (= ":non-streaming" (get-in result [:session-config :response-mode])))
      (is (= ":off" (get-in result [:session-config :thinking-level])))
      (is (= :planning (get-in result [:session-config :session-profile])))
      (is (= "true" (get-in result [:session-config :logprobs])))
      (is (= "4" (get-in result [:session-config :top-logprobs])))
      (is (= "You are a planner." (:body result)))))

  (testing "markdown workflow rejects missing name"
    (let [result (parser/parse-workflow-file :md "---\ndescription: Plans tasks\n---\nBody")]
      (is (= "Missing required frontmatter key: name" (:error result)))))

  (testing "markdown workflow rejects missing description"
    (let [result (parser/parse-workflow-file :md "---\nname: planner\n---\nBody")]
      (is (= "Missing required frontmatter key: description" (:error result)))))

  (testing "markdown workflow rejects unsupported frontmatter keys"
    (let [result (parser/parse-workflow-file :md "---\nname: planner\ndescription: Plans\nlambda: nope\n---\nBody")]
      (is (re-find #"Unsupported markdown workflow frontmatter keys" (:error result)))))

  (testing "markdown workflow rejects prompt-mode frontmatter"
    (let [result (parser/parse-workflow-file :md "---\nname: planner\ndescription: Plans\nprompt-mode: :lambda\n---\nBody")]
      (is (= "Unsupported markdown workflow frontmatter key: :prompt-mode" (:error result)))))

  (testing "markdown workflow rejects empty body"
    (let [result (parser/parse-workflow-file :md "---\nname: planner\ndescription: Plans\n---\n")]
      (is (= "Standalone markdown workflow body must not be empty" (:error result)))))

  (testing "markdown workflow rejects EDN workflow definition block body"
    (let [result (parser/parse-workflow-file :md "---\nname: planner\ndescription: Plans\n---\n{:steps []}")]
      (is (= "Markdown workflow body must not begin with an EDN workflow definition block" (:error result)))))

  (testing "vars: EDN string parses to map, returned under :vars"
    (let [raw "---\nname: my-step\ndescription: A step\nvars: '{\"my-var\" {:from :workflow-input :path [:some-field]}}'\n---\nBody with {{my-var}}."
          result (parser/parse-workflow-file :md raw)]
      (is (nil? (:error result)))
      (is (= {"my-var" {:from :workflow-input :path [:some-field]}}
             (:vars result)))))

  (testing "missing vars: key returns :vars nil"
    (let [raw "---\nname: planner\ndescription: Plans tasks\n---\nBody text."
          result (parser/parse-workflow-file :md raw)]
      (is (nil? (:error result)))
      (is (nil? (:vars result)))))

  (testing "vars: with non-map EDN string returns error"
    (let [raw "---\nname: planner\ndescription: Plans\nvars: '[1 2 3]'\n---\nBody."
          result (parser/parse-workflow-file :md raw)]
      (is (re-find #"vars.*must be an EDN map" (:error result)))))

  (testing "vars: with invalid EDN string returns error"
    (let [raw "---\nname: planner\ndescription: Plans\nvars: '{bad edn'\n---\nBody."
          result (parser/parse-workflow-file :md raw)]
      (is (re-find #"Invalid `vars:`" (:error result)))))

  (testing "vars: with unsupported :from value returns error"
    (let [raw "---\nname: planner\ndescription: Plans\nvars: '{\"x\" {:from :workflow-runtime}}'\n---\nBody."
          result (parser/parse-workflow-file :md raw)]
      (is (re-find #"unsupported :from values" (:error result)))))

  (testing "vars: with :from :workflow-original is accepted"
    (let [raw "---\nname: my-step\ndescription: A step\nvars: '{\"x\" {:from :workflow-original}}'\n---\nBody with {{x}}."
          result (parser/parse-workflow-file :md raw)]
      (is (nil? (:error result)))
      (is (= {"x" {:from :workflow-original}}
             (:vars result)))))

  (testing "vars: with map-valued :from is rejected"
    (let [raw "---\nname: my-step\ndescription: A step\nvars: '{\"x\" {:from {:step \"y\" :yield :text}}}'\n---\nBody."
          result (parser/parse-workflow-file :md raw)]
      (is (re-find #"unsupported :from values" (:error result))))))

(deftest parse-edn-workflow-file-test
  (testing "edn workflow parses multi-step map"
    (let [result (parser/parse-workflow-file :edn "{:name \"plan-build\" :steps [{:name \"plan\" :type :session :contributions [{:type :template :text \"hi\" :vars {}}]}]}")]
      (is (= :multi-step-edn (:workflow-kind result)))
      (is (= "plan-build" (get-in result [:config :name])))
      (is (= :session (get-in result [:config :steps 0 :type])))))

  (testing "edn workflow rejects invalid edn"
    (let [result (parser/parse-workflow-file :edn "{:steps [")]
      (is (re-find #"Invalid EDN workflow definition" (:error result)))))

  (testing "edn workflow rejects non-map root"
    (let [result (parser/parse-workflow-file :edn "[:not-a-map]")]
      (is (= "Workflow EDN file must contain a map root" (:error result))))))