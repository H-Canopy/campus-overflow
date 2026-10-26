# 怎么跑覆盖率（一页说明）

> 维护人：③ xing-ding（QA）　最后更新：W7 末
> 对应任务书第 4 步：教全组跑覆盖率，写完发群里。

---

## 1. 一条命令

```bash
cd backend
mvn verify
```

`verify` 会依次：跑测试 → 生成 JaCoCo 报告 → 执行**覆盖率门禁检查**。
门禁不达标时构建直接失败，报错长这样：

```
Rule violated for bundle co-qa-domain: lines covered ratio is 0.28, but expected minimum is 0.70
```

## 2. 报告在哪

| 内容 | 路径 |
|---|---|
| HTML 报告（截图用这个） | `backend/<模块>/target/site/jacoco/index.html` |
| 机器可读数据 | `backend/<模块>/target/site/jacoco/jacoco.xml` |
| 执行数据 | `backend/<模块>/target/jacoco.exec` |

直接双击 `index.html` 就能看，不需要起服务。四个领域模块各有一份：

```
co-identity/co-identity-domain/target/site/jacoco/index.html
co-qa/co-qa-domain/target/site/jacoco/index.html
co-reputation/co-reputation-domain/target/site/jacoco/index.html
co-discovery/co-discovery-domain/target/site/jacoco/index.html
```

## 3. 只想看覆盖率、不想被红测试卡住

测试还在红的时候，加这个参数让构建继续跑完：

```bash
mvn -pl co-qa/co-qa-domain -am test -Dmaven.test.failure.ignore=true
mvn -pl co-qa/co-qa-domain jacoco:report
```

## 4. 门禁怎么配的

- 阈值唯一定义处：`backend/pom.xml` 的 `<domain.coverage.minimum>`
- 四个领域模块的 `pom.xml` 各自引用它做 `jacoco:check`，**只看领域层，不看应用层/基础设施层**
- 初始值是 `0`（等于不设防），任务要求 W13 提到 `0.70`

改阈值只需要动一处：

```xml
<!-- backend/pom.xml -->
<domain.coverage.minimum>0.70</domain.coverage.minimum>
```

> ⚠️ **红线**：只许往上提，不许为了"通过检查"往下调。分工方案第 78 行明确写了这一条。

## 5. 当前基线（2026-10-26 实测，门禁已提到 0.70）

命令：`mvn -f backend/pom.xml verify -DskipITs -Djacoco.skip=false`

| 领域模块 | 行覆盖 | 状态 |
|---|---|---|
| co-qa-domain | 178 / 221　**80.5%** | ✅ |
| co-discovery-domain | 66 / 83　**79.5%** | ✅ |
| co-identity-domain | 103 / 136　**75.7%** | ✅ |
| co-reputation-domain | 116 / 160　**72.5%** | ✅ |
| **合计** | **463 / 600　77.2%** | ✅ 四个模块全部达标 |

`jacoco:check` 在四个领域模块上均输出 `All coverage checks have been met.`，`mvn verify` BUILD SUCCESS。

> 历史基线（W7 末，仅存对照）：199/466 = 42.7%，当时 `co-reputation-domain` 只有 9.4%，
> 因为领域方法还是 `UnsupportedOperationException`，红色行统计不到真实逻辑。
> 等领域层补齐后覆盖率自然上升——**这次提升没有新写一行测试**，纯粹是"代码有了实现"。

## 6. 提门槛的执行顺序（已于 2026-10-26 完成）

1. ~~① 补完 `ReputationAccount` / `ReputationRulebook` / `AcademicTerm` / `Bounty`，④ 补完 `BadgePolicy`，② 补完 `Comment` / `Tags` / `RelatedQuestionPolicy`~~
   —— 已全部完成，`git grep 'UnsupportedOperationException("TODO'` 在 main 上为 **0**。
2. ~~跑一遍第 5 节的命令，确认**每个模块**都 ≥ 0.70~~ —— 四个模块 72.5%–80.5%，全部达标。
3. ✅ `backend/pom.xml` 的 `domain.coverage.minimum` 已由 `0` 改为 `0.70`。
4. ✅ `mvn verify` 全绿，改动单独提 `chore:` PR。

**之后任何一次提交如果让某个领域模块掉到 70% 以下，CI 会直接失败。** 这是有意的：
新增领域逻辑必须带测试，不然门禁形同虚设。

## 7. 覆盖率低时先看哪里

打开 HTML 报告 → 点类名 → 红色行就是没被执行过的代码。两种情况要分开看：

- **红色行是 `throw new UnsupportedOperationException("TODO(...)")`** → 方法没实现，不是测试的问题，催负责人
- **红色行是正常业务逻辑** → 缺测试，补测试

第二种才是真正的覆盖率缺口。第一种只是"还没写"，补完实现后测试自然会覆盖到。
