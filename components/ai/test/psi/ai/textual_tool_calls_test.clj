(ns psi.ai.textual-tool-calls-test
  (:require
   [cheshire.core :as json]
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
      "<tool_call><function=bash><parameter=command value>x</parameter></function></tool_call>"))

  (testing "literal function tags inside parameter values remain parameter text"
    (is (= [{:span [0 129]
             :source "<tool_call><function=bash><parameter=command>printf '<function=literal>' && echo '</function>'</parameter></function></tool_call>"
             :name "bash"
             :arguments {"command" "printf '<function=literal>' && echo '</function>'"}}]
           (textual-tool-calls/parse-xml-tool-calls
            "<tool_call><function=bash><parameter=command>printf '<function=literal>' && echo '</function>'</parameter></function></tool_call>"))))

  (testing "literal tool-call tags inside parameter values remain parameter text"
    (let [text "<tool_call><function=bash><parameter=command>printf '<tool_call>' && echo '</tool_call>'</parameter></function></tool_call>"]
      (is (= [{:span [0 123]
               :source text
               :name "bash"
               :arguments {"command" "printf '<tool_call>' && echo '</tool_call>'"}}]
             (textual-tool-calls/parse-xml-tool-calls text)))))

  (testing "later valid blocks recover after an earlier malformed overlapping prefix"
    (let [text "broken <tool_call><function=bad><parameter=x>1 <tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call> tail"]
      (is (= [{:span [47 130]
               :source "<tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"
               :name "bash"
               :arguments {"command" "pwd"}}]
             (textual-tool-calls/parse-xml-tool-calls text))))))

(deftest normalize-assistant-message-test
  ;; Tests canonical recovery without invoking the tool execution machinery.
  (let [enabled-model  {:capabilities {:textual-tool-calls #{:xml}}}
        disabled-model {:capabilities {}}]
    (testing "disabled models preserve textual markup unchanged"
      (let [assistant {:role "assistant"
                       :content [{:type :text
                                  :text "<tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"}]}]
        (is (= assistant
               (textual-tool-calls/normalize-assistant-message "turn-1" disabled-model assistant)))))

    (testing "enabled models convert parsed calls to canonical tool-call blocks with JSON arguments"
      (let [assistant {:role "assistant"
                       :content [{:type :text
                                  :text "Before <tool_call><function=bash><parameter=command>pwd && echo hi</parameter></function></tool_call> after"}]}
            content   (:content (textual-tool-calls/normalize-assistant-message "turn-1" enabled-model assistant))]
        (is (= [{:type :text :text "Before "}
                {:type :tool-call
                 :id "turn-1/toolcall/1"
                 :name "bash"
                 :arguments "{\"command\":\"pwd && echo hi\"}"}
                {:type :text :text " after"}]
               content))
        (is (= {"command" "pwd && echo hi"}
               (json/parse-string (:arguments (second content)))))))

    (testing "multiple calls and mixed malformed markup preserve response order"
      (let [assistant {:role "assistant"
                       :content [{:type :text :content-index 0 :text "A "}
                                 {:type :tool-call :content-index 1 :id "provider-call" :name "read" :arguments "{}"}
                                 {:type :text
                                  :content-index 2
                                  :text (str " B <tool_call><function=first><parameter=x>1</parameter></function></tool_call>"
                                             " C <tool_call><function=bad><parameter=x>1</parameter><parameter=x>2</parameter></function></tool_call>"
                                             " D <tool_call><function=second><parameter=y>2</parameter></function></tool_call> E")}]}]
        (is (= [{:type :text :text "A "}
                {:type :tool-call :id "provider-call" :name "read" :arguments "{}"}
                {:type :text :text " B "}
                {:type :tool-call :id "turn-2/toolcall/5" :name "first" :arguments "{\"x\":\"1\"}"}
                {:type :text
                 :text (str " C <tool_call><function=bad><parameter=x>1</parameter>"
                            "<parameter=x>2</parameter></function></tool_call> D ")}
                {:type :tool-call :id "turn-2/toolcall/7" :name "second" :arguments "{\"y\":\"2\"}"}
                {:type :text :text " E"}]
               (:content (textual-tool-calls/normalize-assistant-message "turn-2" enabled-model assistant))))))

    (testing "generated ids use source index when a text block is fully replaced by one recovered call"
      (let [assistant {:role "assistant"
                       :content [{:type :tool-call :content-index 1 :id "provider-call" :name "provider" :arguments "{}"}
                                 {:type :text :content-index 2 :text "between"}
                                 {:type :text
                                  :content-index 3
                                  :text "<tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"}]}]
        (is (= "turn-3/toolcall/3"
               (get-in (textual-tool-calls/normalize-assistant-message "turn-3" enabled-model assistant)
                       [:content 2 :id])))))

    (testing "generated ids skip later provider indexes and existing per-turn canonical ids"
      (let [assistant {:role "assistant"
                       :content [{:type :tool-call :content-index 7 :id "provider-call" :name "provider" :arguments "{}"}
                                 {:type :tool-call :id "turn-3/toolcall/0" :name "generated-provider" :arguments "{}"}
                                 {:type :text
                                  :text "<tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"}]}]
        (is (= "turn-3/toolcall/8"
               (get-in (textual-tool-calls/normalize-assistant-message "turn-3" enabled-model assistant)
                       [:content 2 :id])))))

    (testing "generated ids skip a source text index when residual text keeps that index"
      (let [assistant {:role "assistant"
                       :content [{:type :tool-call :content-index 1 :id "provider-call" :name "provider" :arguments "{}"}
                                 {:type :text
                                  :content-index 2
                                  :text "before <tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call>"}]}]
        (is (= [{:type :tool-call :id "provider-call" :name "provider" :arguments "{}"}
                {:type :text :text "before "}
                {:type :tool-call :id "turn-3/toolcall/4" :name "bash" :arguments "{\"command\":\"pwd\"}"}]
               (:content (textual-tool-calls/normalize-assistant-message "turn-3" enabled-model assistant))))))

    (testing "overlapping malformed prefix does not block later valid normalization"
      (let [assistant {:role "assistant"
                       :content [{:type :text
                                  :text "broken <tool_call><function=bad><parameter=x>1 <tool_call><function=bash><parameter=command>pwd</parameter></function></tool_call> tail"}]}]
        (is (= [{:type :text
                 :text "broken <tool_call><function=bad><parameter=x>1 "}
                {:type :tool-call
                 :id "turn-4/toolcall/1"
                 :name "bash"
                 :arguments "{\"command\":\"pwd\"}"}
                {:type :text :text " tail"}]
               (:content (textual-tool-calls/normalize-assistant-message "turn-4" enabled-model assistant))))))))
