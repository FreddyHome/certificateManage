# 结构重构记录

日期：2026-11-12。保留原有 API、数据库迁移与统一 JAR 部署。本轮没有自动提交或重启运行中的服务。

## 后端边界

```text
com.certmanage
├── customer
│   ├── CustomerController     HTTP 请求与响应
│   ├── CustomerRequest        类型化输入与统一字段校验
│   ├── CustomerResponse       类型化列表响应
│   ├── CustomerService        客户业务、事务与审计
│   ├── CustomerRepository     客户表持久化
│   └── CustomerNames          客户名称归一化
├── certificate
│   └── CertificateService → CustomerService
└── sync
    ├── WorkflowService        任务保存、入队、公开契约
    ├── WorkflowQueries        任务与执行查询、秘密脱敏
    ├── WorkflowScheduler      调度推进、失败记录
    ├── WorkflowRunner         原执行器，保持不变
    └── WorkflowValidator      原校验器，保持不变
```

客户管理和证书导入统一使用客户业务服务，证书类不再依赖 CustomerController；创建审计和客户写入同事务回滚。列表字段、空字符串约定、名称 NFKC/大小写归一化保持不变。显式 null 名称、超长描述等无效输入改为可预测的 422，避免空指针或数据库异常。

同步查询和调度作为同包内部组件由 WorkflowService 装配，公开及包级入口保持兼容。保存、入队和执行快照仍使用原事务及同步锁。任务列表改为单条 SQL 获取最近执行状态；30 个任务的查询回归从 31 次连接获取降到 1 次。此结果验证了数据库往返次数，未进行生产规模耗时压测。

本轮涉及的 Java 类和显式方法补齐中文文档注释；按用户指定，作者 Freddy，日期均填写 2026-11-12，包括历史代码补注释。

## 前端边界

```text
api/client.ts                      共享会话、CSRF、JSON/Blob 与错误处理
services/certificates.ts            签发、导入、导出请求及幂等选项
services/sync.ts                    同步 API
services/sync-types.ts              任务、版本、步骤与执行契约
views/sync
├── SyncView.vue                    页面容器
├── useSyncTasks.ts                 页面状态与异步操作
├── SyncTaskEditor.vue              编辑器
├── StoreStepConfig.vue             Store 步骤配置
├── TransferStepConfig.vue          HTTP/SFTP 步骤配置
├── SyncRunHistory.vue              执行历史
├── SyncRunDetail.vue               执行详情
└── sync-editor.ts                  步骤编辑纯逻辑
```

基础请求层不再根据 URL 识别业务；签发、导入提交、同步执行由对应服务明确传递幂等选项。JSON 和二进制错误响应共享 401 处理及非 JSON 错误兜底，没有引入自动重放写请求。同步保存 payload 独立于编辑状态，保留版本、参数与密码留空语义。

前端全部源码统一采用 Prettier；后端使用 Spotless 渐进覆盖本轮涉及文件，尚未全仓格式化 Java。格式工具为开发/构建依赖，不进入业务运行依赖。生产 Maven 流程已纳入前端 verify；跳过测试标志同时作用于前后端。

## 验证与审查

- 开工基线：后端 29 项、前端 6 项通过。
- 新增回归先捕获了缺失客户服务、同步 N+1、二进制 401/HTML 错误处理问题，再完成实现。
- 整体验证命令：`./mvnw.cmd -B -ntp -Pproduction test spotless:check`，后端 37 项、前端 13 项通过；前端类型检查、格式检查与生产前端构建、Java 格式检查通过，Maven BUILD SUCCESS。
- 独立只读审查对照原实现检查客户事务、同步查询/调度/快照、前端编辑与幂等调用点，未发现可证实的新回归。
- 当前运行的 JAR 保持原版本；本轮未重新打包或重启服务，未做新页面浏览器交互验收或并发压测。

## 后续分批事项

- 用户管理等其余 Controller 的 Service/Repository/DTO 分层。
- 同步保存/入队职责进一步隔离，保留事务边界；HTTP/SFTP 传输和通知服务按需要拆分。
- 其他页面的类型与服务边界，以及模块依赖自动约束、关键组件交互回归。
- Element Plus 按需加载和实际首屏测量：现有主包体积警告仍存在，本批未修改组件库注册方式。

这些事项与本批已验证改动分开推进，不进行一次性目录搬迁或强制引入新的部署单元。
