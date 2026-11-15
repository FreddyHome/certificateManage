# 同步业务组件 Implementation Plan

> 执行方式：本会话连续实施，完成后独立审阅。

**Goal:** 按已确认设计实现 IS truststore、IS keyStore 和 TN 证书更新。
**Architecture:** 保留旧步骤，新增 HTTP 步骤内的 businessComponent 标识，独立业务校验和执行服务。来源和目标服务器分别冻结并登记依赖。
**Tech Stack:** Java 21、Spring Boot、Vue 3、TypeScript、H2。
**Spec:** ../specs/2026-11-13-sync-business-components-design.md

## Global Constraints

复用已有数据和主密钥；文件仅在内存处理；单文件10 MiB，累计25 MiB；旧任务兼容；三个新增入口；最终打包重启并检查首页和API。

## Review Focus

HTTP失败及非法响应不写回；库密码错误不写回；信任库保留原条目且只删除过期证书；双服务器依赖完整；手动链和节点不会被自动替换。

## Task 1: 后端业务组件及契约测试

- [x] 在 BusinessComponentsWorkflowTest 中先写本地HTTP端到端测试，验证保留原库、自动链、写入报文、密码错误、失败中断。
- [x] 运行新测试，确认缺少业务能力导致失败。
- [x] 新建 BusinessComponents：prepare(step, strict) 校验和解析证书；execute(step, allowance, phase) 读取、生成、写回；配置标识为 IS_TRUSTSTORE、IS_KEYSTORE、TN_CERTIFICATE。
- [x] 扩展 RemoteTransport 的 HTTP 文件读取入口，响应文件不进入结果或日志；限制JSON大小，验证code/path/content。
- [x] 接入 WorkflowValidator、WorkflowRunner、WorkflowService 和 WorkflowQueries，来源及目标均登记引用、快照和秘密版本。
- [x] 运行后端测试并修复回归。

## Task 2: 前端业务配置

- [x] 先写配置默认值及链选择测试，确认失败。
- [x] 扩展 sync-types、sync-editor，新增 BusinessStepConfig.vue 和来源配置，复用现有界面样式，三个入口替换旧新增按钮。
- [x] 证书链节点接口补充材料ID，前端用ID保存节点选择；自动模式采用整链，部分选择切手动。
- [x] 运行前端测试、类型检查、格式检查和构建。

## Task 3: 审阅与发布验证

- [x] 更新rest-api.md与使用说明，独立审阅修改，补充遗漏测试。
- [x] 运行完整后端、前端检查及production打包。
- [x] 精确确认原服务进程，原工作目录及原data重启，检查首页与认证API，报告实际结果。

## 执行记录

- 当前目录包含大量已有未提交工作，直接在用户指定目录增量实施，保留原变更；不创建会丢失未提交工作的新检出，不提交用户已有工作。
- 用户已批准设计并要求实施，实施计划不再追加审批环节。

- 实施期间用户调整：IS keyStore 去掉来源读取，改为创建与写入两步；storePath 在写入区域配置，格式由用户选择。
- 最终后端83项（业务专项26项）、前端20项、类型和格式检查通过；原服务占用JAR导致首次repackage失败，停止精确确认的原进程后重新打包成功。
- 已用原目录、原data和原主密钥重启，首页及认证API均200；实际同步分包与本次构建一致。

