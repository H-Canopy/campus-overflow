# 上下文映射图（Context Map）

> CampusOverflow 是模块化单体：六个限界上下文在一个进程里，但**物理分包、禁止跨上下文直接依赖实现**，只允许通过对方的 api 模块通信（ArchUnit 强制）。

## 上下文划分

~~~mermaid
flowchart TB
    subgraph Core["核心子域"]
        QA["Q&A 问答<br/>Question / Answer / Vote / Comment"]
        REP["Reputation 声誉<br/>Account / Ledger / Bounty / Badge"]
    end
    subgraph Supporting["支撑子域"]
        ID["Identity 身份与课程<br/>User / Course / CourseMember"]
        DIS["Discovery 内容发现<br/>Search / Notification"]
    end
    SK["Shared Kernel<br/>领域异常 / Actor / 事件契约 / 分页"]
    BS["Bootstrap<br/>装配 / 安全链 / 发件箱中继"]

    ID -- "发布 UserRegistered" --> REP
    QA -- "发布 QuestionPosted / AnswerSubmitted<br/>AnswerAccepted / VoteCast" --> REP
    QA -- "发布 QuestionPosted / AnswerAccepted" --> DIS
    REP -- "发布 ReputationChanged / BadgeAwarded" --> DIS
    QA -. "查询作者信息<br/>IdentityApi" .-> ID
    DIS -. "查询作者信息<br/>IdentityApi" .-> ID
    REP -. "查询作者信息<br/>IdentityApi" .-> ID
    ID --> SK
    QA --> SK
    REP --> SK
    DIS --> SK
    BS -. "装配所有上下文" .-> QA
    BS -. "装配所有上下文" .-> REP
    BS -. "装配所有上下文" .-> ID
    BS -. "装配所有上下文" .-> DIS
~~~

实线 = 领域事件（异步、经发件箱），虚线 = 同步查询（只读 api 接口）。

## 关系类型

| 上游 → 下游 | 关系 | 说明 |
|---|---|---|
| Identity → Q&A / Reputation / Discovery | **客户-供应商** | 只有 Identity 能决定"这个用户是谁、什么角色"，下游只读 |
| Q&A → Reputation | **事件驱动（发布-订阅）** | 采纳、投票产生声誉，但 Q&A 不认识 Reputation |
| Q&A → Discovery | **事件驱动** | 问题与回答变更后重建搜索索引 |
| Reputation → Discovery | **事件驱动** | 得徽章、声誉变化触发通知 |
| 所有上下文 → Shared Kernel | **共享内核** | 只有异常、Actor、事件接口、分页四个包，改动需全员同意 |

## 为什么 Q&A 和 Reputation 要拆开

投票是**高频写**，声誉结算是**低频批处理**（带每日上限、对账任务）。放在一起会让投票事务背上账本写入的开销；拆开之后投票只写自己的表，声誉由事件异步结算。

代价是**最终一致**：用户投完票，声誉不会立刻变。这个代价换来的是投票接口的 p99 稳定，以及声誉规则可以独立改参数。

## 跨上下文的三条硬规矩

1. **只能依赖对方的 api 模块**，不能 import 对方的 domain 或 infrastructure（ArchUnit 会红）。
2. **不能跨上下文做数据库 join**，需要的数据要么冗余、要么走 api 查询。
3. **事件必须幂等消费**：`processed_event` 表 + 账本唯一键 `(event_id, user_id, reason)`。

## 与代码的对应

| 上下文 | 模块 | 领域包 |
|---|---|---|
| Identity | `co-identity` | `com.campusoverflow.identity.domain` |
| Q&A | `co-qa` | `com.campusoverflow.qa.domain` |
| Reputation | `co-reputation` | `com.campusoverflow.reputation.domain` |
| Discovery | `co-discovery` | `com.campusoverflow.discovery.domain` |
| Shared Kernel | `co-shared-kernel` | `com.campusoverflow.shared.*` |
| 装配 | `co-bootstrap` | — |
