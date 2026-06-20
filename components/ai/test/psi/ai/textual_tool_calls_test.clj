(ns psi.ai.textual-tool-calls-test
  (:require
   [clojure.test :refer [are deftest is testing]]
   [psi.ai.schemas :as schemas]
   [psi.ai.textual-tool-calls :as textual-tool-calls]
   [psi.ai.user-models :as user-models]))

(def ^:private minimal-config
  {:providers {"local"
               {:base-url "http://localhost:8080/v1"
                :api      :openai-completions
                :models   [{:id "local-tool-model"}]}}})

(deftest textual-tool-call-capability-schema-test
  ;; Tests that textual tool-call capability is an explicit opt-in model capability.
  (testing "custom model definitions can opt into XML textual tool calls"
    (let [result (user-models/parse-models-config
                  (assoc-in minimal-config
                            [:providers "local" :models]
                            [{:id "local-tool-model"
                              :capabilities {:textual-tool-calls #{:xml}}}]))
          model  (-> result :models first)]
      (is (nil? (:error result)))
      (is (schemas/valid? schemas/Model model))
      (is (textual-tool-calls/supports-format? model :xml))))

  (testing "omitted and empty capability stay disabled"
    (let [omitted-model (-> (user-models/parse-models-config minimal-config) :models first)
          empty-model   (assoc-in omitted-model [:capabilities :textual-tool-calls] #{})]
      (is (false? (textual-tool-calls/supports-format? omitted-model :xml)))
      (is (false? (textual-tool-calls/supports-format? empty-model :xml))))))

(deftest parse-xml-tool-calls-test
  ;; Tests the narrow XML-like parser without executing any tools.
  (testing "parses nominal bash call and trims parameter boundary whitespace"
    (is (= [{:span [0 112]
             :source "<tool_call>\n<function=bash>\n<parameter=command>\ncd /tmp && git diff --stat\n</parameter>\n</function>\n</tool_call>"
             :name "bash"
             :arguments {"command" "cd /tmp && git diff --stat"}}]
           (textual-tool-calls/parse-xml-tool-calls
            "<tool_call>\n<function=bash>\n<parameter=command>\ncd /tmp && git diff --stat\n</parameter>\n</function>\n</tool_call>"))))

  (testing "parses multiple calls in response order with multiple parameters"
    (let [text "before <tool_call><function=first-tool><parameter=a>one</parameter><parameter=b>two\n2</parameter></function></tool_call> middle <tool_call><function=second_tool><parameter=x>z</parameter></function></tool_call> after"]
      (is (= [{:span [7 120]
               :source "<tool_call><function=first-tool><parameter=a>one</parameter><parameter=b>two\n2</parameter></function></tool_call>"
               :name "first-tool"
               :arguments {"a" "one" "b" "two\n2"}}
              {:span [128 210]
               :source "<tool_call><function=second_tool><parameter=x>z</parameter></function></tool_call>"
               :name "second_tool"
               :arguments {"x" "z"}}]
             (textual-tool-calls/parse-xml-tool-calls text)))))

  (testing "duplicate parameters and malformed cardinality are block-level no-ops"
    (are [text] (empty? (textual-tool-calls/parse-xml-tool-calls text))
      "<tool_call><function=bash><parameter=command>one</parameter><parameter=command>two</parameter></function></tool_call>"
      "<tool_call></tool_call>"
      "<tool_call><function=a><parameter=x>1</parameter></function><function=b><parameter=y>2</parameter></function></tool_call>"
      "<tool_call><function=bash></function></tool_call>"
      "<tool_call><parameter=x>1</parameter><function=bash><parameter=y>2</parameter></function></tool_call>"))

  (testing "unsupported tag/name variants are left unparsed"
    (are [text] (empty? (textual-tool-calls/parse-xml-tool-calls text))
      "<TOOL_CALL><function=bash><parameter=command>x</parameter></function></TOOL_CALL>"
      "<tool_call><function=bash.shell><parameter=command>x</parameter></function></tool_call>"
      "<tool_call><function= bash><parameter=command>x</parameter></function></tool_call>"
      "<tool_call><function=bash><parameter=command value>x</parameter></function></tool_call>")))
