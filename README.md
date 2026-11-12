# 证书管理系统 MVP

Spring Boot 3.5 / Java 21 + Vue 3 / TypeScript + H2。前端编译后嵌入 Spring Boot，生产环境只启动一个 JAR。

## 启动

构建环境：JDK 21、Node.js 22.12+、npm。首次 Maven Wrapper 运行需要联网下载依赖。

```powershell
.\mvnw.cmd -Pproduction clean package
java -jar target/certificate-manage-0.1.0.jar
```

Linux/macOS 使用 `./mvnw -Pproduction clean package`。生成的 JAR 部署时仅需 Java 21。

访问 http://127.0.0.1:8080 ，初始账号 `admin`，密码 `123456`。首次登录后在右上角修改密码。初始化仅执行一次，重启不会覆盖已保存密码。

## 已实现

- 自有证书：根 / 中间 / 叶子三个表格，按顺序创建三层证书；AS2 / HTTPS / 通用用途。
- 客户证书：终端列表、展开证书链；客户可下拉选择或直接输入，保存时自动创建。
- 导入：多文件 CER / DER / CRT / PEM、P7B / P7C；自有额外支持 P12 / PFX。验证完整链、CA约束、签名及终端私钥匹配，客户拒绝私钥。
- 证书名称解析 CN；每张证书独立存储，链节点记录根起始的层序。新旧证书并列，按到期时间升序。
- 证书详情、链节点详情，PEM链 / DER / P7B导出，自有含私钥证书可加密码导出P12。
- 客户管理、管理员用户管理、SMTP配置、邮件通知策略及投递记录、失败重试。
- 服务器管理：SFTP / HTTP配置、别名、连通性测试、加密凭据和HTTP额外信任CA。
- 同步管理：顺序步骤编辑、JKS / PKCS12 Keystore或Truststore制作、SFTP上传、HTTP调用、六字段Cron定时及立即执行、步骤执行记录。
- BCrypt密码、会话认证、CSRF校验、停用/改密使原会话失效；私钥与SMTP密码使用AES-GCM加密。

## 数据与部署

默认在**进程工作目录**生成 `data/certificate-manage.mv.db` 和 `data/master.key`。应固定工作目录并使用专用系统账号运行，限制这两个文件及其备份的访问权限。停机后同时备份整个 `data` 目录；恢复时必须使用同一份主密钥，丢失密钥无法解密已有私钥和SMTP密码。

支持以下环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | 监听地址 |
| `PORT` | `8080` | 端口 |
| `DB_URL` | `jdbc:h2:file:./data/certificate-manage` | 数据库路径 |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / 空 | 数据库认证 |
| `ENCRYPTION_KEY_FILE` | `./data/master.key` | 32字节主密钥文件 |

内部网络访问建议通过 HTTPS 反向代理转发到回环地址，并设置 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`。MVP使用H2单实例，不支持多副本同时访问同一数据库文件。

## 邮件通知

先在“系统管理 → SMTP配置”保存真实服务器参数和发件邮箱，使用测试邮件验证，再启用“通知服务”。支持STARTTLS和TLS，必须验证服务器证书。修改配置时密码留空表示保留原密码。

通知默认关闭，节点默认提前30/14/7/1天，Asia/Shanghai时区09:00后扫描，每分钟轮询。只在命中的日期生成任务；不补历史节点、不发送已过期提醒。按资产、节点、收件人和到期日去重；失败每15分钟重试，自动最多3次，之后可以手动重试。稳定Message-ID有助于识别重投，但SMTP无法保证跨进程崩溃时绝对只投递一次。

## 配置证书同步

1. 在“服务器管理”新增SFTP或HTTP服务器，填写认证信息并保存测试。SFTP按用户确认的自管服务器场景不校验主机指纹，可选密码或SSH密钥认证；超时单位为秒。HTTP测试仅支持HEAD/GET，不上传证书。
2. 在“同步管理”新增草稿，添加制作Keystore或Truststore步骤，选择JKS或PKCS12，设置至少8位Store密码、文件名和证书条目别名。Keystore私钥条目只接受含私钥的自有证书，Truststore只包含公钥。PKCS12使用相同Store/Key密码。
3. 添加SFTP或HTTP步骤，选择服务器别名与前序制作产物；也可直接导出所选公钥证书。SFTP填写绝对目录且不自动创建目录，默认目标存在则失败；HTTP支持multipart、二进制、JSON与简单响应字段判定。
4. 保存并校验，确认未来执行时间及最终条目，设为“就绪”。可立即执行，或启用定时。Cron为六字段（秒 分 时 日 月 周），默认时区Asia/Shanghai。
5. 从执行记录查看步骤结果。步骤失败会停止后续步骤，不自动重试；远端可能已经写入，确认远端状态后再重新执行。SFTP传完文件不代表应用已重新加载，可追加目标系统提供的HTTP刷新调用。

服务器/证书按稳定ID引用，修改别名不破坏任务。任务排队时冻结流程、连接配置及秘密版本；后续修改下次生效，停用服务器会阻止尚未开始的传输步骤。任务或活跃执行引用的证书/服务器不可删除。

生成产物仅在内存中传递，不落临时磁盘；单个Store最多10MB，单次执行累计产物最多25MB。生产按单实例串行工作线程执行，通知和心跳使用独立调度线程。重启将未完成任务标为中断，不自动重放；不补执行停机期间错过的Cron节点。旧加密配置保留以保护执行历史，本轮未自动清理历史秘密版本。

服务器密码/令牌/SSH私钥及Store密码均加密保存；普通JSON接口不返回明文。输入框留空保留原秘密，服务器可显式清除。HTTP认证在服务器配置中维护，步骤请求头不允许覆盖Authorization/Cookie等敏感头。JSON正文只支持`${fileBase64}`和`${certificateBase64}`替换。

## 开发与验证命令

```powershell
.\mvnw.cmd test
cd frontend
npm ci
npm test
npm run build
```

后端源码按业务模块组织在 `src/main/java/com/certmanage`，数据库迁移在 `src/main/resources/db/migration`，前端位于 `frontend/src`，测试位于 `src/test` 和前端 `*.spec.ts`。开发时可运行 `mvn spring-boot:run`，前端 `npm run dev` 使用Vite代理访问后端；生产仍统一部署。

## 自有证书删除

自有证书各层提供删除。被其他自有或客户证书链引用时禁止删除，须从叶子往根逐层处理。采用逻辑删除，保留历史和加密私钥材料，重新导入同一证书可恢复。已删除证书不再提醒。

## 操作与执行日志

“系统管理 → 审计日志”统一查询数据库 `execution_logs`，主要记录执行人、执行时间、操作/执行内容；来源与结果可筛选。覆盖账号登录退出、查询、新增编辑删除、证书导入导出与签发、配置测试、同步任务及步骤、通知扫描和邮件投递。手动执行记录实际用户，定时执行记录系统；日志查询自身和CSRF/会话探测请求不再产生日志。

日志只追加，没有修改/删除入口，不自动清理。旧操作审计迁入统一日志表；旧同步和通知仅迁入已有结果快照，不伪造升级前未记录的逐步日志。密码、令牌、私钥、Store密码和报文内容不写入日志。

服务器测试失败会在页面和日志中说明具体阶段与原因，例如地址解析失败、连接被拒绝、超时、密码/密钥认证失败、私钥读取失败或SFTP子系统不可用；HTTP可显示实际响应状态码。诊断采用安全分类，不直接展示含凭据风险的底层报文。已有旧日志没有保存的原因无法追补，需重新测试生成新记录。

操作已完成但成功日志写库暂时失败时，仅重试日志写入，不重放远端操作。该重试缓冲在当前进程内，数据库持续不可用且进程退出时仍可能遗失缓冲事件；正常写入数据库的日志不受重启影响。

## MVP边界

全部用户为管理员，数据库预留角色；实际登录会话使用服务器内存，重启需重新登录，`app_sessions`表暂未启用。前端采用hash路由。生成证书固定RSA 3072 / SHA-256，HTTPS叶子需填写SAN。导入支持多层中间CA，必须带完整根链；不访问AIA、OCSP或CRL，不等同于目标系统已信任该链。CA补录私钥、远程脚本执行、登录限流及复杂权限不在本轮实现。通用同步组件已实现，具体第三方应用的HTTP契约需在任务中配置并联调。

具体需求和数据模型见 [需求文档](doc/requirements.md)、[设计文档](doc/design.md)、[数据库设计](doc/database-design.md)。验收记录见 [MVP验收](doc/mvp-verification.md)。

服务器与流程同步扩展见 [设计](doc/server-sync-design.md)、[实施计划](doc/server-sync-plan.md)、[同步验收记录](doc/server-sync-verification.md)。

## 开发规范优化记录

项目结构分析、本轮局部重构和后续优先项见 [开发规范分析](doc/code-quality-review.md)，执行范围见 [局部重构计划](doc/code-quality-plan.md)。

## 结构重构与统一检查

客户模块按 HTTP 入口、业务事务、持久化职责划分；证书导入通过客户服务复用客户。同步模块保留任务服务契约，查询和调度分别由专用组件承担。前端同步页面拆为编辑、步骤配置和执行记录组件，业务请求位于 `frontend/src/services`。

完整验证（包含后端测试、前端格式/测试/类型检查与构建、Java 格式检查）：

```powershell
.\mvnw.cmd -Pproduction verify
```

运行中的 JAR 不需要为源码检查而停止，可以使用以下不重新打包 JAR 的等价检查：

```powershell
.\mvnw.cmd -Pproduction test spotless:check
```

Java 格式检查当前覆盖本轮重构的客户、证书入口/服务和同步任务服务/查询/调度及相关测试，后续模块逐步纳入；修复格式使用 `.\mvnw.cmd spotless:apply`。前端检查覆盖全部 `frontend/src`，修复格式使用 `npm --prefix frontend run format`，单独验证使用 `npm --prefix frontend run verify`。`-DskipTests` 会同时跳过生产构建中的后端测试与前端验证；正常验证请使用默认值。

本批边界和验证结果见 [结构重构记录](doc/structure-refactor.md)。

## 固定 HTTP 同步组件

同步任务现提供通用 HTTP、重载 Keystore 和更新 TNPartner 证书。重载组件固定 GET `/wm/is/cert/reload`，填写 `keyStoreName` 和 `type`；TNPartner组件固定 POST `/wm/tn/partner/cert/update`，选择客户与用途，系统自动生成客户标识及根到终端的 DER Base64 链。客户需在客户管理中配置外部 ID 和 idType。同一客户用途匹配多张时，自动选择整链有效且终端到期最晚的一张。

重载响应 `isError=false`、TN响应 `code=0` 且 HTTP 状态成功时才成功；接口 `message` 在成功与失败时均进入执行日志。配置及验证说明见 [固定 HTTP 组件](doc/fixed-http-components.md)。
