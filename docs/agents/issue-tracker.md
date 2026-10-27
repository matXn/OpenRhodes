# Issue 跟踪器：GitHub

本仓库的 issue 和规格说明保存在 GitHub issues 中。所有操作都使用 `gh` CLI。

## 约定

- **创建 issue**：`gh issue create --title "..." --body "..."`。多行正文使用 heredoc。
- **读取 issue**：运行 `gh issue view <number> --comments`，使用 `jq` 过滤评论，并同时获取标签。
- **列出 issue**：运行 `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，并设置适当的 `--label` 和 `--state` 过滤条件。
- **评论 issue**：`gh issue comment <number> --body "..."`
- **添加 / 移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭 issue**：`gh issue close <number> --comment "..."`

通过 `git remote -v` 判断仓库。`gh` 在克隆目录中运行时会自动识别。

## 将 Pull request 用作分诊入口

**将 PR 作为需求入口：否。** _（如果本仓库会把外部 PR 当作功能请求，则改为 `yes`；`/triage` 会读取此标记。）_

设为 `yes` 后，PR 与 issue 使用相同的标签和状态，并通过对应的 `gh pr` 命令操作：

- **读取 PR**：运行 `gh pr view <number> --comments`，并使用 `gh pr diff <number>` 查看差异。
- **列出待分诊的外部 PR**：运行 `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`，只保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的条目（排除 `OWNER`/`MEMBER`/`COLLABORATOR`）。
- **评论 / 添加标签 / 关闭**：使用 `gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 的 issue 和 PR 共用编号，因此单独的 `#42` 可能指其中任意一种。先运行 `gh pr view 42`，如果不匹配，再尝试 `gh issue view 42`。

## 技能要求“发布到 issue 跟踪器”时

创建 GitHub issue。

## 技能要求“获取相关 ticket”时

运行 `gh issue view <number> --comments`。

## Wayfinding 操作

供 `/wayfinder` 使用。**地图（map）**是一个 issue，其中的**子 issue（child）**代表各个 ticket。

- **地图**：一个带有 `wayfinder:map` 标签的 issue，正文保存 Notes / Decisions-so-far / Fog。使用 `gh issue create --label wayfinder:map` 创建。
- **子 ticket**：通过 GitHub sub-issue 关系将 issue 链接到地图（使用 `gh api` 的 sub-issues 端点）。如果未启用 sub-issue，就把子项加入地图正文的任务列表，并在子项正文顶部写 `Part of #<map>`。标签使用 `wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。ticket 被领取后，指派给负责开发者。
- **阻塞关系**：使用 GitHub 的**原生 issue 依赖**，这是标准且在界面中可见的表示方式。通过 `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>` 添加依赖，其中 `<blocker-db-id>` 是阻塞项的数字 **database id**（使用 `gh api repos/<owner>/<repo>/issues/<n> --jq .id` 获取，_不要_使用 `#number` 或 `node_id`）。GitHub 通过 `issue_dependencies_summary.blocked_by` 返回尚未关闭的阻塞项，即当前有效的门槛。如果无法使用依赖关系，就在子项正文顶部添加 `Blocked by: #<n>, #<n>` 行。所有阻塞项都关闭后，ticket 才解除阻塞。
- **查询前沿**：列出地图下仍打开的子项（运行 `gh issue list --state open`，限定在该地图的 sub-issue 或任务列表中），排除有未关闭阻塞项（`issue_dependencies_summary.blocked_by > 0`，或 `Blocked by` 行中存在尚未关闭的 issue）或已指派负责人的条目；优先选择地图顺序最靠前的项。
- **领取**：运行 `gh issue edit <n> --add-assignee @me`，这是本会话的首次写操作。
- **解决**：运行 `gh issue comment <n> --body "<answer>"`，然后运行 `gh issue close <n>`，最后在地图的 Decisions-so-far 中追加上下文指针（要点摘要和链接）。
