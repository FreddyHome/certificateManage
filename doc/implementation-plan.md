# MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Execute inline, with a fresh whole-project review.

**Goal:** 实现可统一打包运行的证书管理 MVP。
**Architecture:** Spring Boot 模块化单体，同源 Vue，H2 文件存储。
**Tech Stack:** Java 21、Spring Boot 3.5、Vue 3、Vite、TypeScript、Bouncy Castle、Flyway、JDBC。
**Spec:** doc/requirements.md、doc/design.md、doc/database-design.md。

## Global Constraints
- admin / 123456 仅首次初始化；所有用户管理员。
- 私钥加密，客户只存公钥，CN命名，完整链验证，数据库逐证书存储。
- 单 JAR，三个自有表格，客户叶子列表，客户可输入并自动创建。

## Review Focus
- 伪造扩展名和不匹配私钥必须拒绝。
- 并发重复导入不能留下孤立客户。
- 停用用户应立即使会话失效。
- 已保存密码重启不能重置。
- SMTP失败不能标记成功；成功通知不得重复发送。

## Tasks
- [x] 1. 工程与认证：pom.xml、config/SecurityConfig、user/UserService、auth/AuthController；先用 MockMvc 测未登录拒绝和初始化登录，观察失败再实现并通过。
- [x] 2. 密码学与证书：certificate/CertificateCrypto、CertificateService、CertificateController；先测三层链、错误私钥、缺根及客户私钥拒绝，再实现导入签发和导出。
- [x] 3. 客户和系统配置：customer、smtp、notification 模块；先测客户唯一、配置秘密脱敏、通知去重，再实现及通过。
- [x] 4. Vue 后台：api、router、layouts、views、components；完成真实接口交互，运行类型检查和生产构建。
- [x] 5. 集成验收：mvn verify、生产JAR启动、HTTP真实登录和三层流程、独立代码审阅；README及部署说明。

## Decisions / progress
- 无 Git 仓库，不创建工作树或提交，保持用户当前目录。
- CN缺失或多个拒绝；用途单选；导入支持多个中间节点，生成固定三层。
- 过期材料允许登记但不可签发；证书导出支持PEM/DER/P7B，自有叶子支持加密P12；CA补私钥不在本轮。
- 通知默认30/14/7/1天、Asia/Shanghai、09:00，默认关闭；CA提醒可选，按资产去重；不补历史节点、不发送已过期提醒。
- 用户已授权按设计实施，本计划作为执行记录，持续执行。

## Verification handoff
- Backend: 12 tests passed; frontend: 3 tests passed. Production package and fresh H2/JAR startup passed.
- Browser UI acceptance blocked by denied localhost navigation permission; real SMTP delivery requires deployment configuration. See mvp-verification.md.
- No Git repository: files retained in current workspace, no branch integration performed.
