---
name: lambda-compiler
description: Compiles to a lambda expression
---
{:steps [{:name "compile"
          :type :session
          :tools ["read" "bash"]
          :skills ["lambda-compiler"]
          :contributions [{:type :template
                           :text "Use the lambda-compiler skill.\nCompile the specified prose to a lambda.\n\nRequirements:\n- Return lambda\n- No prose\n- Keep output minimal and structurally valid\n\nInput:\n{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}]}
