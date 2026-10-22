# CampusOverflow 团队章程 v1.0

> 生效日期：2026-10-22 ｜ 适用范围：H-Canopy/campus-overflow 全组成员

## 1. 团队成员与角色

| 编号 | GitHub | 角色 | 主要职责 |
|---|---|---|---|
| ① | huangjiasheng567 | PO + Dev | 产品优先级、Question/MarkdownBody/Reputation 领域与应用层 |
| ② | H-Canopy | SM + Dev | 站会、看板、阻塞消除、Answer/Comment/Tags/Bounty/Badge |
| ③ | xing-ding | Dev + QA | Vote/Comment/BountyService/覆盖率门禁/review-checklist |
| ④ | Twh743 | Dev + 前端 | 前端脚手架、页面、Postman、Notification/Discovery |
| ⑤ | 1311948 | Dev | AnswerCommand/Notification/SearchIndex Projector |

## 2. 分支与提交纪律

- 开工前先 `git pull origin main`。
- 一个分支只做一件事，做完立刻提 PR。
- 分支命名：`feature/<github-id>-<模块>`。
- 提交信息格式：`feat(Sx): <一句话说明>`。
- **不允许自己批自己的 PR**，按环评审：① → ② → ③ → ④ → ⑤ → ①。

## 3. 评审纪律

- 评审必须读 Files changed，跑通相关测试再 Approve。
- 不许修改既有测试断言；缺测试时先要求作者补，再 Approve。
- 后端 CI 红时要判断是不是本 PR 引入；环境/他人依赖导致的红，在评审里注明。

## 4. 质量门槛

- 新代码必须补单元测试。
- 合并前：`mvn verify`（覆盖率门槛 0.70，由 ③ 配置）。
- 前端：`npm run dev`、`typecheck`、ESLint/Prettier 通过。

## 5. 协作节奏

- 每天 15 分钟站会，只看看板，找被卡住的人。
- 没人批的 PR，SM（②）负责催。
- Sprint 结束开 Review + Retro，纪要存档 `docs/retro/`。

## 6. 签名

| 成员 | 签名（同意本章程） |
|---|---|
| ① huangjiasheng567 | ✅ huangjiasheng567 |
| ② H-Canopy | ✅ H-Canopy |
| ③ xing-ding | ☐ |
| ④ Twh743 | ☐ |
| ⑤ 1311948 | ☐ |
