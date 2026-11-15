# SSH Shell 命令执行 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在服务器管理新增 SSH 连接，并在同步流程中提供固定参数、无文件输入的 Shell 步骤。

**Architecture:** 使用独立 SSH 类型服务器和 SSH 步骤，复用凭据、版本快照和加密审计。命令传输抽取到 SshCommandTransport，原 SFTP 传输不改。

**Tech Stack:** Java 21、Spring Boot、MINA SSHD 2.16、H2/Flyway、Vue 3、TypeScript。

**Spec:** docs/superpowers/specs/2026-11-13-ssh-command-design.md

## Global Constraints

- 所有输入来自步骤固定配置；服务器从菜单配置后选择。
- 执行超时默认 60 秒，范围 1–300 秒；每个输出流保留上限 1 MiB，继续排空并标记截断。
- 保留工作区已有修改，直接在用户项目实现，沿用原 data 和主密钥。
- 所有验证通过后重新打包、重启并检查首页和 API。

## Review Focus

- 纯 SSH 服务器无 SFTP 子系统也能测试和执行。
- 多行命令原样发送，远端最终退出码决定结果。
- 静默命令不受服务器较短读写超时影响；执行超时仍生效。
- 超大 stdout/stderr 不阻塞，不泄露到普通列表，保留截断标记。
- 排队快照与服务器停用规则继续生效，SSH 不能作为文件来源。

### Task 1: 后端连接、校验、迁移及执行

**Files:** 新增 SshCommandTransport.java、db/migration/V10__ssh_shell_steps.java、SshCommandWorkflowTest.java；修改 ServerService.java、RemoteTransport.java、WorkflowValidator.java、WorkflowRunner.java。

**Interfaces:** SshCommandTransport.call(Map<String,Object> connection, Map<String,Object> step, boolean test) 返回不含原始输出的结果摘要，原始输出进入加密审计。

- [x] 编写真实本地 SSH 命令服务集成测试，覆盖成功、非零退出、超时、大输出、密钥认证、快照和无文件输入校验。
- [x] 运行定向 Maven 测试，确认现有实现因缺失 SSH 功能失败。
- [x] 增加独立服务器类型、兼容已有数据库的迁移、执行通道及流程分支。
- [x] 重跑测试并确认通过。

### Task 2: 前端配置

**Files:** 修改 ServerView.vue、sync-types.ts、sync-editor.ts、SyncTaskEditor.vue；新增 ShellStepConfig.vue 和对应测试。

**Interfaces:** SSH 配置包含 serverId、command、timeoutSeconds；组件仅展示已启用 SSH 服务器。

- [x] 增加配置保存、复制隔离、无文件产物及组件参数呈现测试并确认失败。
- [x] 新增协议选项与 Shell 步骤编辑入口；保持现有界面样式。
- [x] 运行前端 verify 和生产构建，确认通过。

### Task 3: 回归、文档及服务重启

- [x] 更新 README 与功能说明，记录多行退出码和超时语义。
- [x] 运行生产 verify（前端、后端、格式检查）。
- [x] 检查变更，处理重要审查问题。
- [x] 打包并在原目录重启服务，验证数据库迁移、首页及受认证 API。

## Execution ledger

按已确认需求在本会话直接实现；已有未提交变更较多，不切换工作树或提交用户的其他改动。


- 后端 RED：原实现拒绝 SSH 协议；前端 RED：缺失独立 Shell 固定参数模型。
- Task 1 complete：纯 SSH、密码/私钥、快照/停用、输出截断、非零退出/超时/缺失退出码及数据库升级测试通过。
- Task 2 complete：前端 24 项测试、类型检查和格式检查通过。
- Final review：独立只读审查未发现本次 SSH 范围内的重要缺陷。
- Final fixed：认证库原生超时与关闭定时器竞态，回归测试先失败后通过；最终完整后端 102/102 通过。
- Task 3 complete：生产 verify 通过；后台编译曾覆盖静态输出，在完整测试后重新复制已验证 frontend/dist 并重新打包，确认最终 JAR 含首页和 V10 迁移。
- Runtime complete：首页、服务器/同步页面分包、登录后 servers 与 sync-tasks API 全部 HTTP 200；原工作目录、data 数据库和主密钥保留，服务 PID 57756。证据 logs/ssh-final-verify.log、logs/ssh-final-package.log、logs/ssh-runtime-smoke.json。
