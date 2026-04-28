---
name: lambda-build
description: Build a lambda expression
---
{:steps [{:name "compile-1"
          :workflow "lambda-compiler"
          :session {:input {:from :workflow-input}}
          :prompt "compile a lambda for: $INPUT"}
         {:name "decompile"
          :workflow "lambda-decompiler"
          :session {:input {:from {:step "compile-1" :kind :accepted-result}}}
          :prompt "decompile the lambda expression: $INPUT"}
         {:name "compile-2"
          :workflow "lambda-compiler"
          :session {:input {:from {:step "decompile" :kind :accepted-result}}}
          :prompt "compile a lambda for: $INPUT"}]}

Iteratively compile and refine a lambda expression through compilation/decompilation cycles using explicit session-first step wiring.
