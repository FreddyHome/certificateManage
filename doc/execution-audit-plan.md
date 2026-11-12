# 执行审计日志实施计划

用户要求：所有执行日志写入数据库作为审计。范围包括同步入队/启动/步骤/结束/中断、服务器连通测试、SMTP测试、通知扫描/投递/重试及后台执行异常；现有配置操作审计继续保留。仅记录业务事件及白名单结果，不将含秘密的HTTP/SSH原始报文或第三方DEBUG日志写库。

- [x] 新增V5 execution_logs表和只追加日志服务、分页查询接口，先测白名单脱敏/分页。
- [x] 在同步生命周期、服务器测试、邮件通知生命周期及定时异常处追加事件，补回归断言。
- [x] 新增系统管理“审计日志”，按来源、执行ID、时间、结果筛选；同步执行详情可查看相关事件。
- [x] 全量测试、前端构建、升级备份、打包重启；更新文档。

规则：成功、失败均保留，不提供更新删除接口、不自动过期。执行中日志即时写库；事务内的入队事件与入队记录原子提交。日志源数据库不可用时无法保证写入，以后台错误提示暴露失败。不得记录密码、令牌、SSH私钥、Store密码、请求正文或响应原文。

## Final clarification and verification (2026-11-10)
- User clarified unified simple log: actor, execution time, description. execution_logs is canonical, source/outcome are optional filters. UI simplified accordingly.
- Operation interceptor covers successful/failed API actions (excluding technical CSRF/me and log queries); existing Audit hooks give Chinese action summaries. Manual workflow requestedBy is frozen and propagated to steps.
- Historical operations/results migrated as labeled snapshots, no fabricated historical timelines.
- Whole backend26 tests passed, production Vue/typecheck/build/package passed. File H2 V4->V5 upgrade succeeded, backup in data/backups/pre-audit-*, JAR running8080.
- Review fixes: RUN_STARTED logging is inside cleanup protection; audit failure after confirmed SMTP acceptance cannot cause mail replay. Pending success logs retry DB writes while process lives; database outage + process loss can still lose memory-buffered events.
- Normal log rows persist across restart; no delete/edit API or automatic retention. Credentials and body contents excluded. Browser click verification not repeated.
