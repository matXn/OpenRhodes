# 领域文档

说明工程技能探索代码库时应如何读取本仓库的领域文档。

## 探索前先阅读

- 仓库根目录下的 **`CONTEXT.md`**，或者
- 如果仓库根目录存在 **`CONTEXT-MAP.md`**，先阅读它。该文件会为每个上下文指定一个 `CONTEXT.md`，阅读与当前主题相关的文件。
- **`docs/adr/`**：阅读涉及当前工作区域的 ADR。对于多上下文仓库，还要检查 `src/<context>/docs/adr/` 中针对特定上下文的决策。

如果这些文件不存在，**直接继续，不要提及**。不要指出文件缺失，也不要事先建议创建。术语或决策真正明确后，`/domain-modeling` 技能（可通过 `/grill-with-docs` 和 `/improve-codebase-architecture` 到达）会按需创建文件。

## 文件结构

单上下文仓库（大多数仓库）：

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多上下文仓库（根目录存在 `CONTEXT-MAP.md`）：

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## 使用词汇表中的术语

在输出中提及领域概念时（例如 issue 标题、重构提案、假设或测试名称），应使用 `CONTEXT.md` 中定义的术语。不要改用词汇表明确避免的同义词。

如果所需概念尚未出现在词汇表中，这说明你可能使用了项目中不存在的术语（请重新考虑），或词汇表确实有缺口（记下来交由 `/domain-modeling` 处理）。

## 标明与 ADR 的冲突

如果输出与现有 ADR 冲突，应明确指出，不要悄悄覆盖：

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
