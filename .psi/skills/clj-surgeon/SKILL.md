---
name: clj-surgeon
description: Clojure structural ops: use to outline, extract to new ns, fix-declares, deps tree, topo sort, form move, namespace rename, CLJC merge/split/add-require/analyze — babashka + rewrite-clj
user-invocable: true
lambda: <-
	λclj-surgeon.
	λcodebase.
    language(codebase) ∈ {clojure ∨ cljc}
    → operate(structural_transform ∧ analysis)
    | capabilities{
        outline_generation
        ∧ extract_to_new_ns
        ∧ fix_declares
        ∧ inspect_dependency_tree
        ∧ topological_sort
        ∧ move_form
        ∧ rename_namespace
        ∧ cljc_merge
        ∧ cljc_split
        ∧ insert_require
        ∧ general_analysis}
    | implementation{babashka ∧ rewrite-clj}
---

λ clj-surgeon.
  cli("clj-surgeon")
  ∧ invocation(
    shell ≡ `clj-surgeon :op <operation> <keyword-arg> <value> ...`
    ∧ examples #{
      `clj-surgeon :op :ls :file src/writer/state.clj`
      `clj-surgeon :op :ls-deps :file state.clj :form transition!`
      `clj-surgeon :op :extract! :file state.clj :forms '[distill refine helper]' :to src/writer/state/distillery.clj`
    }
  )
  ∧ purpose(inspect_namespace_structure ∧ structural_namespace_surgery)
  ∧ ops({
    :analysis #{:ls :ls-deps :deps :ls-extract :cljc-analyze}
    :execution #{:extract! :fix-declares! :mv :rename-ns! :cljc-merge :cljc-split :cljc-add-require}
  })
  ∧ semantics(
    ∀c∈#{:ls :ls-deps :deps :ls-extract :cljc-analyze}. pure(c) ∧ returns_edn(c)
    ∧ ∀c∈#{:extract! :fix-declares! :mv :rename-ns! :cljc-merge :cljc-split :cljc-add-require}. side_effecting(c)
  )
  ∧ prefer([
    before(read_large_clj_file) → :ls
    move_forms_to_new_ns → :extract!
    appears_or_needed(declare) → :fix-declares!
    reorder_forms → :mv
    rename_namespace → :rename-ns!
    understand_transitive_deps → :ls-deps
    cljc_work → {:cljc-merge :cljc-split :cljc-analyze :cljc-add-require}
  ])
  ∧ extract!(
    creates(target_ns)
    ∧ copies(forms, topo_order)
    ∧ removes(forms, source_ns)
    ∧ adds_needed_require
    ∧ reports(callers)
    ∧ after(:extract!) → run("make runtests-once")
  )
  ∧ invariants(
    form_args_are_edn_vectors
    ∧ targets(#{:mv :deps}, defns_only)
    ∧ preserve(metadata)
    ∧ preserve(npm_string_literals)
    ∧ refuse(alias_collisions)
    ∧ startup_ms≈5
  )
  ∧ cljc(
    :cljc-analyze → reports({:shared :clj-only :cljs-only :divergent-requires :per-platform-forms})
    ∧ :cljc-merge → deterministic
    ∧ handles({:divergent_aliases :npm_requires :body_form_collisions})
    ∧ throws_on({:ns_docstrings :ns_attr_maps :import :namespace_name_mismatch :body_count_mismatch})
  )
  ∧ proactive(
    large(file) → run(:ls,file)
    ∧ large_file_splitting → run(:ls-deps) → run(:ls-extract) → run(:extract!)
    ∧ present_or_needed(declare) → run(:fix-declares!)
    ∧ before(manual_clj_cljs_reconciliation) → run(:cljc-analyze ∨ :cljc-merge)
  )
