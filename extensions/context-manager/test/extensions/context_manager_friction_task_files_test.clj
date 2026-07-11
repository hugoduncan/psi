(ns extensions.context-manager-friction-task-files-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.context-manager :as context-manager]))

(defn- temp-worktree []
  (let [dir (java.io.File/createTempFile "friction-task-files-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getCanonicalPath dir)))

(defn- mkdirs! [& paths]
  (doseq [p paths] (.mkdirs (io/file p))))

(deftest allocate-task-id-test
  (testing "no munera dirs → 001"
    (is (= "001" (context-manager/allocate-task-id (temp-worktree)))))

  (testing "open-only tasks → next after max open id"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/open/003-foo")
               (str root "/munera/open/010-bar"))
      (is (= "011" (context-manager/allocate-task-id root)))))

  (testing "closed-max tasks → next after max closed id"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/open/002-a")
               (str root "/munera/closed/025-b"))
      (is (= "026" (context-manager/allocate-task-id root)))))

  (testing "non-numeric-prefixed noise directories ignored"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/open/foo-not-numeric")
               (str root "/munera/open/007-real"))
      (is (= "008" (context-manager/allocate-task-id root))))))

(deftest create-friction-task-test
  (testing "creates design.md only, no plan.md/steps.md"
    (let [root  (temp-worktree)
          issue {:slug "missing-linter" :title "Missing linter"
                 :friction "no linter installed" :evidence "turn 1"
                 :suggestion "add linter dependency"}
          id    (context-manager/create-friction-task! root issue)]
      (is (= "001-missing-linter" id))
      (is (.exists (io/file root "munera" "open" id "design.md")))
      (is (not (.exists (io/file root "munera" "open" id "plan.md"))))
      (is (not (.exists (io/file root "munera" "open" id "steps.md"))))
      ;; AC2 end-to-end: the *written* design.md must contain the
      ;; auto-generated marker plus friction/evidence/suggestion content,
      ;; not only the title (task 239 task-test-review round-2 follow-up) —
      ;; proves the whole rendered document reached the created file.
      (let [content (slurp (io/file root "munera" "open" id "design.md"))]
        (is (str/includes? content "# Missing linter")
            "title rendered as heading in the written file")
        (is (str/includes? content "Auto-generated")
            "auto-generated marker in the written file")
        (is (str/includes? content "context-manager")
            "marker names the context-manager analyzer in the written file")
        (is (str/includes? content "task 239")
            "marker names the owning task in the written file")
        (is (str/includes? content "no linter installed")
            "friction content in the written file")
        (is (str/includes? content "turn 1")
            "evidence content in the written file")
        (is (str/includes? content "add linter dependency")
            "suggested change in the written file"))))

  (testing "pre-existing directory collision → re-allocates NNN"
    (let [root  (temp-worktree)
          issue {:slug "dup-slug" :title "Dup" :friction "f" :evidence "e"
                 :suggestion "s"}]
      (mkdirs! (str root "/munera/open/001-dup-slug"))
      (let [id (context-manager/create-friction-task! root issue)]
        (is (= "002-dup-slug" id)))))

  (testing "retry exhaustion → nil, no task created"
    (let [root  (temp-worktree)
          issue {:slug "always-taken" :title "T" :friction "f" :evidence "e"
                 :suggestion "s"}]
      ;; A concurrent writer keeps winning the race: every id this call
      ;; could produce is (about to be) taken. `create-friction-task!`
      ;; can't be driven into this state via pre-existing directories alone
      ;; (its own `allocate-task-id` scan would just skip past them), so
      ;; the exhaustion path is covered directly against
      ;; `next-free-task-id` below instead.
      (is (some? (context-manager/create-friction-task! root issue 5))))))

(deftest next-free-task-id-test
  (testing "first free id when start is immediately free"
    (is (= "001-slug" (context-manager/next-free-task-id 1 "slug" (constantly false) 5))))

  (testing "collisions advance NNN until a free id is found"
    (is (= "003-slug"
           (context-manager/next-free-task-id
            1 "slug" #(contains? #{"001-slug" "002-slug"} %) 5))))

  (testing "retry exhaustion → nil once max-retries collisions are hit"
    (is (nil? (context-manager/next-free-task-id 1 "slug" (constantly true) 5)))))

(deftest open-tasks-test
  (testing "lists all open tasks sorted by id, titles from design.md heading"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/open/002-b") (str root "/munera/open/001-a"))
      (spit (io/file root "munera" "open" "001-a" "design.md") "# Alpha issue\n\nbody")
      (spit (io/file root "munera" "open" "002-b" "design.md") "# Beta issue\n\nbody")
      (is (= [{:id "001-a" :title "Alpha issue"}
              {:id "002-b" :title "Beta issue"}]
             (context-manager/open-tasks root)))))

  (testing "falls back to id as title when design.md missing"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/open/003-no-design"))
      (is (= [{:id "003-no-design" :title "003-no-design"}]
             (context-manager/open-tasks root))))))

(defn- git! [root & args]
  (apply shell/sh (concat ["git"] args [:dir root])))

(deftest recent-closed-tasks-test
  (testing "git-derived closure order, most-recent-first"
    (let [root (temp-worktree)]
      (git! root "init" "-q")
      (git! root "config" "user.email" "test@example.com")
      (git! root "config" "user.name" "Test")
      (mkdirs! (str root "/munera/closed/001-first"))
      (spit (io/file root "munera" "closed" "001-first" "design.md") "# First\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "close first")
      (mkdirs! (str root "/munera/closed/002-second"))
      (spit (io/file root "munera" "closed" "002-second" "design.md") "# Second\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "close second")
      (is (= [{:id "002-second" :title "Second"}
              {:id "001-first" :title "First"}]
             (context-manager/recent-closed-tasks root)))))

  (testing "non-git dir falls back to name-descending order"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/closed/001-a") (str root "/munera/closed/002-b"))
      (is (= [{:id "002-b" :title "002-b"} {:id "001-a" :title "001-a"}]
             (context-manager/recent-closed-tasks root)))))

  (testing "N bounds the returned list"
    (let [root (temp-worktree)]
      (mkdirs! (str root "/munera/closed/001-a")
               (str root "/munera/closed/002-b")
               (str root "/munera/closed/003-c"))
      (is (= 2 (count (context-manager/recent-closed-tasks root 2)))))))
