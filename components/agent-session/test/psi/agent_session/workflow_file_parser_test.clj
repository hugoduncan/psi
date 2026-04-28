(ns psi.agent-session.workflow-file-parser-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-file-parser :as parser]))

(deftest parse-workflow-file-test
  (testing "single-step with no config"
    ;; Simplest case: frontmatter + body text only
    (let [raw "---\nname: planner\ndescription: Plans tasks\n---\nYou are a planner."
          result (parser/parse-workflow-file raw)]
      (is (= "planner" (:name result)))
      (is (= "Plans tasks" (:description result)))
      (is (nil? (:config result)))
      (is (= "You are a planner." (:body result)))
      (is (nil? (:error result)))))

  (testing "single-step with EDN config"
    ;; EDN config block followed by body text
    (let [raw (str "---\nname: planner\ndescription: Plans tasks\n---\n"
                   "{:tools [\"read\" \"bash\"]\n :thinking-level :off}\n\n"
                   "You are a planner.")
          result (parser/parse-workflow-file raw)]
      (is (= "planner" (:name result)))
      (is (= "Plans tasks" (:description result)))
      (is (= {:tools ["read" "bash"] :thinking-level :off} (:config result)))
      (is (= "You are a planner." (:body result)))))

  (testing "single-step with EDN config only, no body"
    ;; EDN config but nothing after it
    (let [raw "---\nname: planner\ndescription: Plans tasks\n---\n{:tools [\"read\"]}"
          result (parser/parse-workflow-file raw)]
      (is (= "planner" (:name result)))
      (is (= {:tools ["read"]} (:config result)))
      (is (nil? (:body result)))))

  (testing "multi-step with steps in EDN config"
    ;; Multi-step workflow with steps array
    (let [raw (str "---\nname: plan-build-review\ndescription: Plan, build, and review\n---\n"
                   "{:steps [{:name \"plan\" :workflow \"planner\" :prompt \"$INPUT\"}\n"
                   "         {:name \"build\" :workflow \"builder\" :prompt \"Execute: $INPUT\"}]}\n\n"
                   "Coordinate a plan-build-review cycle.")
          result (parser/parse-workflow-file raw)]
      (is (= "plan-build-review" (:name result)))
      (is (= "Plan, build, and review" (:description result)))
      (is (= 2 (count (get-in result [:config :steps]))))
      (is (= "plan" (get-in result [:config :steps 0 :name])))
      (is (= "planner" (get-in result [:config :steps 0 :workflow])))
      (is (= "Coordinate a plan-build-review cycle." (:body result)))))

  (testing "body with leading whitespace before non-EDN content"
    ;; Body starts with whitespace then text, not EDN
    (let [raw "---\nname: test\ndescription: Test\n---\n  Hello world."
          result (parser/parse-workflow-file raw)]
      (is (nil? (:config result)))
      (is (= "Hello world." (:body result)))))

  (testing "empty body"
    ;; Frontmatter only, no body at all
    (let [raw "---\nname: test\ndescription: Test\n---\n"
          result (parser/parse-workflow-file raw)]
      (is (nil? (:config result)))
      (is (nil? (:body result)))))

  (testing "missing name returns error"
    (let [raw "---\ndescription: Plans tasks\n---\nBody"
          result (parser/parse-workflow-file raw)]
      (is (string? (:error result)))
      (is (re-find #"name" (:error result)))))

  (testing "missing description returns error"
    (let [raw "---\nname: planner\n---\nBody"
          result (parser/parse-workflow-file raw)]
      (is (string? (:error result)))
      (is (re-find #"description" (:error result)))))

  (testing "invalid EDN returns error"
    (let [raw "---\nname: test\ndescription: Test\n---\n{:bad"
          result (parser/parse-workflow-file raw)]
      (is (string? (:error result)))
      (is (re-find #"EDN" (:error result)))))

  (testing "EDN config with multiline body"
    ;; Verifies body extraction preserves multiple paragraphs
    (let [raw (str "---\nname: builder\ndescription: Builds code\n---\n"
                   "{:tools [\"read\" \"bash\" \"edit\" \"write\"]}\n\n"
                   "You are a builder agent.\n\n"
                   "## Guidelines\n\n"
                   "Follow the plan carefully.")
          result (parser/parse-workflow-file raw)]
      (is (= {:tools ["read" "bash" "edit" "write"]} (:config result)))
      (is (str/starts-with? (:body result) "You are a builder agent."))
      (is (str/includes? (:body result) "## Guidelines"))
      (is (str/ends-with? (:body result) "Follow the plan carefully."))))

  (testing "preserves existing agent profile format"
    ;; Current .psi/agents/*.md files should parse cleanly
    (let [raw (str "---\n"
                   "name: planner\n"
                   "description: Analyzes tasks, creates implementation plans\n"
                   "lambda: λtask. analyze(task)\n"
                   "tools: read,bash\n"
                   "---\n\n"
                   "You are a planning agent.")
          result (parser/parse-workflow-file raw)]
      (is (= "planner" (:name result)))
      (is (= "Analyzes tasks, creates implementation plans" (:description result)))
      ;; Extra frontmatter keys are ignored, body is preserved
      (is (nil? (:config result)))
      (is (= "You are a planning agent." (:body result))))))

(deftest parse-edn-prefix-edge-cases
  (testing "config with nested maps"
    (let [raw (str "---\nname: x\ndescription: X\n---\n"
                   "{:steps [{:name \"a-step\" :workflow \"a\" :prompt \"$INPUT\"}]}\n\nBody")
          result (parser/parse-workflow-file raw)]
      (is (= [{:name "a-step" :workflow "a" :prompt "$INPUT"}] (get-in result [:config :steps])))
      (is (= "Body" (:body result)))))

  (testing "config with keywords, sets, vectors"
    (let [raw (str "---\nname: x\ndescription: X\n---\n"
                   "{:thinking-level :high :tools [\"a\" \"b\"] :tags #{\"foo\"}}\n\nBody")
          result (parser/parse-workflow-file raw)]
      (is (= :high (get-in result [:config :thinking-level])))
      (is (= ["a" "b"] (get-in result [:config :tools])))
      (is (= #{"foo"} (get-in result [:config :tags])))))

  (testing "body that starts with a non-map curly brace character"
    ;; If body starts with `{` but reads as non-map, we get an error
    ;; because sets/vectors are not valid config
    (let [raw "---\nname: x\ndescription: X\n---\n#{:a :b}\nBody"
          result (parser/parse-workflow-file raw)]
      ;; A set starting with `{` will be read as EDN but isn't a map
      ;; so config extraction should fail gracefully
      (is (or (:error result)
              (nil? (:config result)))))))
