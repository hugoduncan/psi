---
name: gh-bug-discover-and-read
description: Discover and read one GitHub bug-triage issue, then emit a structured handoff brief
advertise: false
---
{:terminal-contract {:handoff {:type :markdown-handoff-data}}
 :steps [{:name      "discover"
          :type      :invoke
          :operation "github/find-issue"
          :args      {:labels ["bug" "triage"]
                      :input  {:from :workflow-input :path [:input]}}
          :outputs   {:summary {:source :invoke/summary}}
          :yields    {:type :text :text :summary}}
         {:name "read"
          :type :session
          :tools ["read" "bash"]
          :contributions [{:type :source
                           :from {:step "discover" :yield :text}}]}]}

You are the issue-reading phase of a GitHub bug-triage workflow. The upstream discovery step has already selected the issue — its details appear in the context above.

Goal:
- Read the selected issue carefully.
- Emit a structured handoff brief for downstream workflow steps.

Required procedure:
1. Run `gh issue view` for the selected issue with JSON output including at least: `number,title,body,labels,author,assignees,state,url`.
2. Read enough repo-local context to interpret the request if useful, but do not over-explore.
3. If reading fails, report the failure clearly instead of inventing details.

Output requirements:
- Output a compact structured brief in Markdown.
- Include these headings exactly:
  - `## Selected Issue`
  - `## Triage Summary`
  - `## Reproduction Targets`
  - `## Handoff Data`
- Under `## Handoff Data`, include machine-friendly bullet lines for:
  - `issue_number:`
  - `issue_title:`
  - `issue_url:`
  - `repo:`
  - `selection_basis:`
  - `suggested_worktree_description:`
- Keep the brief faithful to the issue text and available evidence.
- Do not attempt reproduction yet.
