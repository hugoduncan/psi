---
name: prompt-build
description: Build a new prompt
---
{:steps [{:name "compile-1"
          :workflow "prompt-compiler"
          :session {:input {:from :workflow-input}}
          :prompt "compile the prompt: $INPUT"}
         {:name "decompile"
          :workflow "prompt-decompiler"
          :session {:input {:from {:step "compile-1" :kind :accepted-result}}}
          :prompt "decompile the EDN prompt: $INPUT"}
         {:name "compile-2"
          :workflow "prompt-compiler"
          :session {:input {:from {:step "decompile" :kind :accepted-result}}}
          :prompt "compile the prompt: $INPUT"}]}

Iteratively compile and refine a prompt through compilation/decompilation cycles using explicit session-first step wiring.
