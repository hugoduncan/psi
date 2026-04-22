(ns psi.agent-session.service-protocol-stdio-jsonrpc-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.service-protocol :as protocol]
   [psi.agent-session.service-protocol-stdio-jsonrpc :as stdio-jsonrpc]
   [psi.agent-session.services :as services]
   [psi.agent-session.test-support :as test-support]))

(defn start-jsonrpc-echo-process []
  (let [script (str "python3 -u - <<'PY'\n"
                    "import sys, json\n"
                    "while True:\n"
                    "    headers = {}\n"
                    "    while True:\n"
                    "        line = sys.stdin.buffer.readline()\n"
                    "        if not line:\n"
                    "            sys.exit(0)\n"
                    "        if line in (b'\\r\\n', b'\\n'):\n"
                    "            break\n"
                    "        k, v = line.decode('utf-8').split(':', 1)\n"
                    "        headers[k.strip().lower()] = v.strip()\n"
                    "    n = int(headers.get('content-length', '0'))\n"
                    "    body = sys.stdin.buffer.read(n)\n"
                    "    msg = json.loads(body.decode('utf-8'))\n"
                    "    if 'id' in msg:\n"
                    "        out = {'jsonrpc': '2.0', 'id': msg['id'], 'result': {'echo': msg.get('method')}}\n"
                    "    else:\n"
                    "        out = {'jsonrpc': '2.0', 'method': 'observed', 'params': {'method': msg.get('method')}}\n"
                    "    payload = json.dumps(out).encode('utf-8')\n"
                    "    sys.stdout.buffer.write((f'Content-Length: {len(payload)}\\r\\n\\r\\n').encode('utf-8'))\n"
                    "    sys.stdout.buffer.write(payload)\n"
                    "    sys.stdout.buffer.flush()\n"
                    "PY")]
    (ProcessBuilder. ^java.util.List ["bash" "-lc" script])))

(deftest attach-jsonrpc-runtime-in-test
  (testing "stdio json-rpc runtime adapter exposes correlated runtime fns"
    (let [ctx {:service-registry (services/create-registry)}
          cwd (test-support/temp-cwd)
          _   (services/ensure-service-in!
               ctx
               {:key [:jsonrpc cwd]
                :type :subprocess
                :spec {:command ["bash" "-lc" "sleep 5"]
                       :cwd cwd
                       :transport :stdio}})
          _runtime (with-redefs [stdio-jsonrpc/create-jsonrpc-runtime
                                 (fn [_ _]
                                   {:send-fn (fn [_] nil)
                                    :await-response-fn (fn [{:keys [request-id]}]
                                                         {:payload {"result" {"echo" request-id}}})
                                    :await-response-sends? false})]
                     (stdio-jsonrpc/attach-jsonrpc-runtime-in! ctx [:jsonrpc cwd]))
          svc (services/service-in ctx [:jsonrpc cwd])]
      (try
        (is (fn? (:send-fn svc)))
        (is (fn? (:await-response-fn svc)))
        (let [result (protocol/jsonrpc-request!
                      ctx [:jsonrpc cwd]
                      {:id "1" :method "initialize" :params {}}
                      {:timeout-ms 1000})]
          (is (= {"echo" "1"}
                 (protocol/await-jsonrpc-result result))))
        (finally
          (services/stop-service-in! ctx [:jsonrpc cwd]))))))
