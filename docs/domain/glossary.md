# 统一语言术语表 v1.0（Ubiquitous Language）

> 团队内所有文档、代码、PR、看板卡片一律使用本表的叫法。中英对照，按上下文分组。
> 共 40 条。

## 通用

| 中文 | English | 定义 |
|---|---|---|
| 限界上下文 | Bounded Context | 一个模型只在一个边界内保持一致的边界，本项目的六个上下文见上下文映射图 |
| 聚合 | Aggregate | 一个一致性边界，内部对象必须同时正确 |
| 聚合根 | Aggregate Root | 聚合的唯一入口，外部只能通过它访问内部对象 |
| 值对象 | Value Object | 没有身份、只由属性定义的对象，如标题、正文、标签 |
| 实体 | Entity | 有身份标识、生命周期内可变的对象，如回答 |
| 仓储 | Repository | 聚合的持久化入口，接口定义在领域层 |
| 领域事件 | Domain Event | 领域内发生的、其他上下文关心的事实，命名用过去时 |
| 发件箱 | Outbox | 与业务同事务写入的事件表，由中继异步投递，保证"不丢" |
| 幂等 | Idempotency | 同一事件重复消费结果不变 |
| 读模型 | Read Model | 为查询优化的投影，允许冗余，允许与写模型不同步 |
| 投影器 | Projector | 消费事件、更新读模型或通知的组件 |
| 软删除 | Soft Delete | 只置删除时间、不物理删除，所有查询必须过滤 |
| 乐观锁 | Optimistic Lock | 用 version 字段检测并发修改 |
| 统一语言 | Ubiquitous Language | 业务、代码、文档共用的一套词汇 |

## Identity 身份与课程

| 中文 | English | 定义 |
|---|---|---|
| 用户 | User | 系统账号，有角色与状态 |
| 角色 | Role | STUDENT / TA / TEACHER / ADMIN 四类 |
| 课程 | Course | 一个教学单元，问题可挂在课程下 |
| 课程成员 | Course Member | 用户与课程的关系，携带课程内角色 |
| 课程管理者 | Course Manager | 该课程的教师、助教或管理员，可代提问者采纳、删任意内容 |
| 封禁 | Ban | 账号被停用，不能登录 |

## Q&A 问答

| 中文 | English | 定义 |
|---|---|---|
| 问题 | Question | 聚合根，一问多答 |
| 标题 | Title | 值对象，10–150 字 |
| 正文 | Markdown Body | 值对象，只存原文，渲染在基础设施层 |
| 标签 | Tag | 问题的分类，1–5 个，小写归一 |
| 回答 | Answer | 聚合根，挂在问题下 |
| 采纳 | Accept | 提问者选定唯一最佳答案，只能一次 |
| 投票 | Vote | +1 或 −1，改票按增量结算 |
| 教师认证 | Endorse | 课程管理者给优质回答打标记，只能一次 |
| 评论 | Comment | 挂在问题或回答下，只支持一层回复 |
| 目标类型 | Target Type | 投票/评论的挂载对象，QUESTION 或 ANSWER |

## Reputation 声誉

| 中文 | English | 定义 |
|---|---|---|
| 声誉 | Reputation | 贡献的长期度量，只增不减由规则决定 |
| 账本 | Ledger | 声誉与积分的流水，唯一事实来源 |
| 积分 | Points | 与声誉不同的货币，用于悬赏 |
| 可用积分 | Available Points | 当前能花的积分 |
| 冻结积分 | Frozen Points | 已挂悬赏、待结算的积分 |
| 悬赏 | Bounty | 提问者为吸引回答挂出的积分，状态 OPEN / AWARDED / EXPIRED |
| 徽章 | Badge | 达成条件后授予的称号，同类型只发一次 |
| 每日上限 | Daily Cap | 每天因被投票最多获得的声誉（200） |
| 对账 | Reconciliation | 校验"账户声誉 = 账本流水之和"的批处理任务 |

## Discovery 发现

| 中文 | English | 定义 |
|---|---|---|
| 搜索索引 | Search Index | 问题与回答的可检索投影 |
| 相关问题 | Related Question | 按标签 Jaccard 相似度排序的推荐 |
| 提及 | Mention | 评论里的 @昵称，一条最多 5 人 |
| 通知 | Notification | 发给某个用户的消息，类型见 NotificationType |
| 未读数 | Unread Count | 通知中心红点 |

## 质量与过程

| 中文 | English | 定义 |
|---|---|---|
| 测试金字塔 | Test Pyramid | 单元多、集成中、端到端少 |
| 覆盖率门槛 | Coverage Gate | 领域层行覆盖 ≥ 70% 才能合并 |
| 架构约束测试 | Architecture Test | 用 ArchUnit 断言"领域层零框架依赖、跨上下文只走 api" |
| 完成定义 | Definition of Done | 代码 + 测试 + 文档 + 评审通过 + CI 绿 |
