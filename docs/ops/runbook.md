# CampusOverflow 运维手册 v1.0

> 部署与监控统一入口。所有命令在项目根目录执行，使用 `deploy/docker-compose.yml`。

## 1. 启动

一键启动整套服务（含 Prometheus + Grafana 监控）：

```bash
docker compose -f deploy/docker-compose.yml --profile observability up -d --build
```

- 仅启动应用、不带监控：去掉 `--profile observability` 即可。
- 启动顺序：mysql / redis 先做健康检查，通过后 backend 才拉起，最后 web 与 prometheus / grafana。
- 校验：`docker compose ps` 全部为 running；浏览器访问 `http://localhost:8080/actuator/health` 返回 `UP` 即应用就绪。

## 2. 停止

```bash
docker compose -f deploy/docker-compose.yml --profile observability down
```

- 停止并移除容器，保留数据卷（mysql-data / redis-data / grafana-data）。
- 需要连数据一起清空（重置开发环境）：`down -v`，会删除全部数据卷，谨慎使用。
- 排查时看某服务日志：`docker compose -f deploy/docker-compose.yml logs -f <service>`。

## 3. 回滚

- 代码回滚：切到上一稳定版本 `git checkout <上一tag/commit>`（或 `git revert`），再执行 `up -d --build` 重建镜像。
- 容器回滚：`docker compose down` 后对出问题的服务重新 `up -d --build`；仅重启用 `docker compose restart <service>`。
- 数据回滚：数据卷默认保留，可直接复用；确需重置时才 `down -v`，有备份则先备份再操作。

## 4. 常见故障

- **backend 反复重启 / health 非 UP**：`docker compose logs backend` 查看是否连上 mysql/redis；确认 `DB_HOST=mysql`、`REDIS_HOST=redis`，端口无冲突，等 mysql 健康后再拉起 backend。
- **端口被占用（3306 / 8080 / 3000）**：`docker compose ps` 看是否已有同名容器，或本机其他程序占用端口，释放后 `up -d` 重启。
- **Grafana / Prometheus 无数据**：确认启动时带了 `--profile observability`（缺 profile 监控容器不会启动）；再确认 prometheus 能抓取 backend 的 `/actuator/prometheus` 指标。
- **中文搜索无结果**：确认 mysql 以 `--ngram-token-size=2` 启动（compose 已配置），中文全文索引需要 ngram 解析，重建索引后重试。
- **镜像拉取失败 / 容器起不来**：多为网络问题，配置镜像加速后重试 `up -d --build`；`docker compose ps` 结合 `logs -f` 看具体报错。
