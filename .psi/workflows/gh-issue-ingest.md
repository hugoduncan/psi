---
name: gh-issue-ingest
description: Find labeled GitHub enhancement-ingest requests, triage them, reply on GitHub, and advance labels
---
{:steps [{:name "run"
          :type :session
          :tools ["read" "bash"]
          :skills ["issue-feature-triage"]
          :thinking-level :high
          :contributions [{:type :template
                           :text "You are executing a focused GitHub enhancement-triage workflow in this repository.\n\nGoal:\n- Find open GitHub requests marked for ingest and enhancement triage.\n- Analyze the request with the `issue-feature-triage` skill.\n- Post a structured triage reply to the GitHub issue.\n- Remove the `triage` label and add a `waiting` label.\n\nUse the `issue-feature-triage` skill when shaping the analysis.\n\nPrimary selection rule:\n- Look for open GitHub issues carrying both the `triage` label and the `enhancement` label.\n- If there are no matching issues, stop and report that there is nothing to process.\n- If multiple matching issues exist, process them in ascending issue-number order unless the input narrows the target.\n\nInput expectations:\n- `{{input}}` is optional.\n- If provided, treat it as an optional narrowing hint such as an issue number, repo-qualified issue reference, full issue URL, or a short instruction that identifies a specific matching issue.\n- If `{{input}}` is absent, discover candidate issues from labels.\n\nRequired procedure:\n1. Discover candidate issues.\n2. Read the selected issue.\n3. Analyze the request into intent, problem statement, scope, and acceptance criteria.\n4. Compose and post a concise structured GitHub reply.\n5. Remove `triage` and add `waiting`.\n\nExecution constraints:\n- Do not create a Munera task.\n- Do not edit repository files as part of the issue-processing flow.\n- Do not invent requirements beyond what the issue supports.\n- Prefer one issue per run unless the input explicitly asks for batch processing.\n\nFinal response requirements:\n- Report the issue that was processed.\n- Summarize the posted triage briefly.\n- State whether the GitHub comment was posted.\n- State whether labels were updated from `triage` to `waiting`.\n- If nothing matched, say so clearly.\n\nInput:\n{{input}}"
                           :vars {"input" {:from :workflow-input}}}]}]}
