# 证书管理系统 MVP

Spring Boot 3.5 / Java 21 + Vue 3 / TypeScript + H2。前端编译后嵌入 Spring Boot，生产环境只启动一个 JAR。

## 源码打包

### 1. 准备构建环境

需要JDK 21（不只是JRE）、Node.js 22.12+及npm，并确保`java`、`node`、`npm`在PATH中可用；建议JAVA_HOME指向JDK 21。项目自带Maven Wrapper，无需另装Maven。首次构建需要访问Maven依赖仓库及npm registry下载依赖。

所有构建命令均在源码根目录执行，即包含`pom.xml`、`mvnw.cmd`和`frontend`的目录。

### 2. 执行完整生产构建

Windows（PowerShell或CMD）：

```powershell
.\mvnw.cmd -Pproduction clean verify
```

Linux：

```bash
chmod +x mvnw
./mvnw -Pproduction clean verify
```

必须启用`production`配置；普通构建不会自动完成前端构建和发布包组装。无需先进入frontend手工执行npm，Maven会统一调用。构建依赖下载或任一测试/检查失败时，先处理失败原因，再重新构建；以命令退出成功及`BUILD SUCCESS`作为交付条件。

### 3. 自动执行的打包流程

| 顺序 | 阶段 | 执行内容 |
| --- | --- | --- |
| 1 | clean | 清理target中的上次构建产物，不清理项目根目录的data和logs |
| 2 | generate-resources | 在frontend执行npm ci，再执行npm run build（TypeScript检查和Vite生产构建） |
| 3 | process-resources / compile | 将前端dist复制到后端static资源目录，复制后端资源并编译Java |
| 4 | test | 运行后端测试；执行前端npm run verify，包含格式检查、单元测试和类型检查 |
| 5 | package | 生成JAR，Spring Boot重打包，将前端与第三方依赖内置到可执行JAR |
| 6 | package | Maven Assembly按照deploy/assembly.xml组装发布目录及ZIP，自动放入配置、BAT和SH脚本 |
| 7 | verify | 运行配置范围内的Java格式检查；全部检查成功后完成构建 |

发布文件在package阶段已生成，但verify仍可能失败，因此不要仅凭ZIP存在判断构建通过。

### 4. 获取构建产物

当前版本为0.1.0，成功构建后生成：

| 路径 | 用途 |
| --- | --- |
| `target/certificate-manage-0.1.0.jar` | Spring Boot可执行JAR，包含前端及第三方依赖 |
| `target/certificate-manage-0.1.0-dist.zip` | 用于交付、上传和解压部署的完整发布包 |
| `target/certificate-manage-0.1.0-dist/certificateManage/` | 与ZIP内容一致的展开目录，可直接检查目录结构和文件 |

### 5. 发布包文件来源

| 源码/构建文件 | 发布包内位置 | 打包处理 |
| --- | --- | --- |
| `target/certificate-manage-0.1.0.jar` | `lib/certificate-manage.jar` | 复制并使用固定运行文件名 |
| `src/main/resources/application.yml` | `config/application.yml` | 复制默认应用配置，部署后可在JAR外修改 |
| `deploy/config/jvm.options` | `config/jvm.options` | 复制JVM参数，每行一项 |
| `deploy/bin/*.bat` | `bin/*.bat` | 自动复制Windows启动、停止、重启、状态及共用脚本，使用CRLF换行 |
| `deploy/bin/*.sh` | `bin/*.sh` | 自动复制Linux对应脚本，使用LF换行并设置0755可执行权限 |
| `deploy/README.md` | `README.md` | 发布包中的部署维护说明 |

bin内脚本会在源码打包时一并放入展开目录和ZIP，部署人员无需手工创建或复制。维护脚本源码应修改deploy/bin，不修改target中的临时产物。发布包只带logs、data、run的目录占位，不包含已有数据库、主密钥、历史日志、测试脚本或前端node_modules。

## 部署包结构与启动

```text
certificateManage/
├── lib/certificate-manage.jar
├── config/application.yml
├── config/jvm.options
├── bin/                  # start/stop/restart/status 的 .bat 和 .sh
├── logs/                 # 默认系统日志、启动输出
├── data/                 # 首次启动生成，升级时保留
├── run/                  # PID与维护锁
└── README.md
```

Windows使用`bin\start.bat`、`stop.bat`、`restart.bat`、`status.bat`；Linux使用对应`.sh`。脚本从任意目录调用，始终以项目根目录运行。Java依赖仍内置于单个可执行JAR中，lib不拆分第三方依赖。配置、日志和数据位于JAR外，发布包不包含已有数据库、主密钥或历史日志。详见[部署维护说明](deploy/README.md)。

将ZIP解压到部署父目录后启动：

```powershell
# Windows
.\certificateManage\bin\start.bat
```

```bash
# Linux；解压工具若未保留权限，先执行 chmod +x certificateManage/bin/*.sh
./certificateManage/bin/start.sh
```

部署运行只需Java 21，无需Node、npm或Maven。Linux维护脚本另需Bash 4.4+、curl及flock，Windows BAT使用系统自带PowerShell。升级时先停止原实例并备份data及主密钥，再更新lib、bin并合并config，保留已有data和logs。

访问 http://127.0.0.1:8080 ，初始账号 `admin`，密码 `123456`。首次登录后在右上角修改密码。初始化仅执行一次，重启不会覆盖已保存密码。

## 已实现

- 自有证书：根 / 中间 / 叶子三个表格，按顺序创建三层证书；AS2 / HTTPS / 通用用途。
- 客户证书：终端列表、展开证书链；客户可下拉选择或直接输入，保存时自动创建。
- 导入：多文件 CER / DER / CRT / PEM、P7B / P7C；自有额外支持 P12 / PFX。验证完整链、CA约束、签名及终端私钥匹配，客户拒绝私钥。
- 证书名称解析 CN；每张证书独立存储，链节点记录根起始的层序。新旧证书并列，按到期时间升序。
- 证书详情、链节点详情，PEM链 / DER / P7B导出，自有含私钥证书可加密码导出P12。
- 客户管理、管理员用户管理、SMTP配置、邮件通知策略及投递记录、失败重试。
- 服务器管理：SFTP / SSH / HTTP配置、别名、连通性测试、加密凭据和HTTP额外信任CA。
- 同步管理：IS truststore / IS keyStore / TN 业务组件、独立 Shell 步骤，兼容已有证书库制作、SFTP上传/移动/读取及HTTP步骤，支持六字段Cron、立即执行及步骤记录。
- BCrypt密码、会话认证、CSRF校验、停用/改密使原会话失效；私钥与SMTP密码使用AES-GCM加密。

## 数据与部署

默认在**进程工作目录**生成 `data/certificate-manage.mv.db` 和 `data/master.key`。应固定工作目录并使用专用系统账号运行，限制这两个文件及其备份的访问权限。停机后同时备份整个 `data` 目录；恢复时必须使用同一份主密钥，丢失密钥无法解密已有私钥和SMTP密码。

维护脚本加载`config/application.yml`覆盖内置默认配置，JVM参数按`config/jvm.options`每行一项读取。默认系统日志为`logs/certificate-manage.log`，10MB滚动、保留30天、总量1GB；可通过外置配置调整，或用`LOG_FILE`指定文件。启动输出另存`logs/console.log`与`console-error.log`。如直接改配置文件的端口/地址，应同时设置脚本的`HEALTH_URL`为可访问的本机根地址；使用`PORT`环境变量时两者会保持一致。

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

### 对接端三个API

目标Integration Server需部署[WmCertificateManage对接包](integrations/WmCertificateManage.zip)。以下为目标IS接口，不是本系统的`/api/v1`接口：

| 方法 | 路径 | 用途 | 主要参数 |
| --- | --- | --- | --- |
| GET | `/restv2/wm.certificate:cert/keystore/get` | 读取原证书库文件并返回Base64 | Query：`path` |
| POST | `/restv2/wm.certificate:cert/keystore/update` | 备份原文件、写入证书库并刷新IS配置 | JSON：`keyStoreName`、`type`（key/trust）、`path`、`content` |
| POST | `/restv2/wm.certificate:cert/tn/update` | 更新TN业务组件已有用途的证书链 | JSON：`id`、`idType`、`certChain[{index,certificateBase64}]` |

调用使用目标IS账号及服务权限；请求/响应为JSON，读取接口通过Query传参。业务组件以HTTP 2xx且`code=0`判断成功；失败响应的`message`用于诊断，不能只检查HTTP状态。读取返回`path`及`content`，其中`content`是整个文件的Base64；TN节点是单张DER证书的Base64，两者不能混用。

完整字段、请求/响应、curl示例、备份行为及TN已有记录限制见 [对接程序README](integrations/README.md)。

### 业务组件配置

新增任务提供三个业务组件及独立 Shell 步骤。业务组件为：IS truststore 更新（读取 SFTP/HTTP 原库 → 清理过期证书并导入指定节点 → HTTP 写入重载）、IS keyStore 更新（选择自有终端证书与完整链创建 JKS/PKCS12 → HTTP 写入重载，文件全路径在写入阶段配置）、TN 证书更新（客户与用途选择整链 → HTTP 更新）。默认自动选择到期最晚的可用客户链，每次入队时冻结；也可指定链，truststore 支持勾选节点。服务器鉴权复用服务器管理，IS 更新固定传入 `type=key/trust`，业务成功条件为 HTTP 2xx 且 `code=0`。新业务组件的列表保留摘要，原始HTTP请求响应可在加密审计详情中查看。Shell 配置见下文。旧通用任务仍兼容，以下通用步骤说明适用于已有任务。

1. 在“服务器管理”新增SFTP或HTTP服务器，填写认证信息并保存测试。SFTP按用户确认的自管服务器场景不校验主机指纹，可选密码或SSH密钥认证；超时单位为秒。HTTP测试仅支持HEAD/GET，不上传证书。
2. 在“同步管理”新增草稿，添加制作Keystore或Truststore步骤，选择JKS或PKCS12，设置至少8位Store密码、文件名和证书条目别名。Keystore私钥条目只接受含私钥的自有证书，Truststore只包含公钥。PKCS12使用相同Store/Key密码。
3. 添加SFTP或HTTP步骤，选择服务器别名与前序制作产物；也可直接导出所选公钥证书。SFTP填写绝对目录且不自动创建目录，默认目标存在则失败；HTTP支持multipart、二进制、JSON与简单响应字段判定。
4. 保存并校验，确认未来执行时间及最终条目，设为“就绪”。可立即执行，或启用定时。Cron为六字段（秒 分 时 日 月 周），默认时区Asia/Shanghai。
5. 从执行记录查看步骤结果。步骤失败会停止后续步骤，不自动重试；远端可能已经写入，确认远端状态后再重新执行。SFTP传完文件不代表应用已重新加载，可追加目标系统提供的HTTP刷新调用。

服务器/证书按稳定ID引用，修改别名不破坏任务。任务排队时冻结流程、连接配置及秘密版本；后续修改下次生效，停用服务器会阻止尚未开始的传输步骤。任务或活跃执行引用的证书/服务器不可删除。

生成产物仅在内存中传递，不落临时磁盘；单个Store最多10MB，单次执行累计产物最多25MB。生产按单实例串行工作线程执行，通知和心跳使用独立调度线程。重启将未完成任务标为中断，不自动重放；不补执行停机期间错过的Cron节点。旧加密配置保留以保护执行历史，本轮未自动清理历史秘密版本。

服务器密码/令牌/SSH私钥及Store密码均加密保存；普通JSON接口不返回明文。输入框留空保留原秘密，服务器可显式清除。HTTP认证在服务器配置中维护，步骤请求头不允许覆盖Authorization/Cookie等敏感头。JSON正文只支持`${fileBase64}`和`${certificateBase64}`替换。

## 业务执行计划

证书同步使用月份、日期、星期、小时、分钟五列多选设置执行计划。每列未选具体值表示不限；日期支持1–31日，没有该日期的月份跳过；日期与星期同时指定时必须同时满足。默认每天02:00、北京时间。草稿只保存配置，就绪任务保存后按计划自动执行；需要立即执行时使用列表“执行”按钮，无需切换执行方式。实时显示中文规则和最近三次计划时间，预览不会保存任务或触发同步。周计划不自动处理法定节假日和调休。

旧常用规则无损转换为五列选择；月底、秒级等特殊规则保留原表达式，在折叠的高级设置中查看和编辑。现有未启用任务不会仅因打开页面而被启用；就绪状态保存后按计划运行。列表显示中文计划及按计划时区格式化的下次计划时间。前次未结束时不会并行，失败不自动重试，停机遗漏不逐次补跑（恢复时可能处理一次已到期触发）。实现见 [五列执行计划](docs/superpowers/plans/2026-11-16-calendar-schedule.md)。

## IS keyStore 自动换证

IS keyStore 的证书选择支持“自动选择同 CN 到期最晚的可用链”和“指定证书”。自动模式以所选自有叶子证书的 CN 精确匹配，每次手动或定时入队时，在已启用、未归档/删除、含私钥且整条链当前有效的自有叶子证书中选择到期最晚者；到期相同按证书 ID 降序选择。匹配不限制用途；新证书需提前导入，生效后下一次任务即可使用，无需等待旧证书过期。基准证书过期不阻止选择其他有效链；没有可用候选时任务失败。入队后证书和私钥引用冻结，后续导入只影响下次执行。指定模式始终使用所选证书。已有 IS keyStore 配置中 `selectionMode=AUTO` 的任务同样采用上述规则；旧通用“制作 Keystore”步骤保持原有固定条目行为。

## Shell 命令执行

在“服务器管理”新增 `SSH / Shell` 服务器，配置主机、端口、用户名以及密码或 SSH 私钥，保存后可测试连接。连接测试仅验证 SSH 连接和认证，不执行命令，也不要求 SFTP 子系统；主机信任方式沿用现有自管服务器设置。

在同步流程点击“添加 Shell 命令执行”，选择已启用的 SSH 服务器，填写固定命令或多行脚本以及执行超时（默认 60 秒，可设 1–300 秒）。步骤无文件输入，不引用前序产物或证书，也不产生文件；流程可以只包含 Shell 步骤，并沿用立即执行及定时调度。

命令原样交给远端 SSH exec 服务，以登录用户身份运行，不分配交互式终端。最终退出码 0 视为成功，非 0、缺失退出码、信号退出或超时均停止后续步骤，不自动重试。多行脚本遵循远端 Shell 的规则，需要遇错即停时自行添加 `set -e`。超时会关闭连接，但远端后台或衍生进程可能继续运行，重跑前请核查远端状态。

命令、UTF-8 标准输出、错误输出、退出码和耗时在现有加密执行日志详情中查看。每个输出流最多保留前 1 MiB，超过时继续排空并标明截断及实际接收字节数；超时保留已收到的输出。列表只保存摘要。连接配置和命令沿用入队快照，服务器停用后排队任务不能继续执行。

## 开发与验证命令

```powershell
.\mvnw.cmd test
cd frontend
npm ci
npm test
npm run build
```

后端源码按业务模块组织在 `src/main/java/com/certmanage`，数据库迁移在 `src/main/resources/db/migration`，前端位于 `frontend/src`，测试位于 `src/test` 和前端 `*.spec.ts`。开发时可运行 `mvn spring-boot:run`，前端 `npm run dev` 使用Vite代理访问后端；生产仍统一部署。

## 证书归档与删除

自有证书各层和客户证书均提供归档。归档后移入独立“证书归档”菜单，可查看、导出、恢复或删除；普通列表不再显示。归档停止签发、同步和提醒，恢复时保留原启用状态。删除仅允许归档资产，继续校验链、任务及活跃执行等引用并保留历史材料；显式重新导入已删除证书沿用原恢复语义。规则见 [证书归档](doc/certificate-archive.md)。

## 操作与执行日志

“系统管理 → 审计日志”统一查询数据库 `execution_logs`，主要记录执行人、执行时间、操作/执行内容；来源与结果可筛选。覆盖账号登录退出、查询、新增编辑删除、证书导入导出与签发、配置测试、同步任务及步骤、通知扫描和邮件投递。手动执行记录实际用户，定时执行记录系统；日志查询自身和CSRF/会话探测请求不再产生日志。

日志只追加，没有修改/删除入口，不自动清理。每条记录提供“查看详情”，关联当前步骤的原始执行日志、HTTP请求与响应、SFTP操作及收发文件、原始异常完整堆栈。原始内容以现有主密钥加密保存在 `execution_log_raw`，列表保持摘要，详情按需解密读取。原始字节可查看文本或Base64；超时及超限保存实际已接收内容并标明不完整。旧操作审计迁入统一日志表；旧同步和通知仅迁入已有结果快照，历史未采集的原始内容无法补回。

服务器测试失败会在页面和日志中说明具体阶段与原因，例如地址解析失败、连接被拒绝、超时、密码/密钥认证失败、私钥读取失败或SFTP子系统不可用；HTTP可显示实际响应状态码。诊断采用安全分类，不直接展示含凭据风险的底层报文。已有旧日志没有保存的原因无法追补，需重新测试生成新记录。

操作已完成但成功日志写库暂时失败时，仅重试日志写入，不重放远端操作。该重试缓冲在当前进程内，数据库持续不可用且进程退出时仍可能遗失缓冲事件；正常写入数据库的日志不受重启影响。

## MVP边界

全部用户为管理员，数据库预留角色；实际登录会话使用服务器内存，重启需重新登录，`app_sessions`表暂未启用。前端采用hash路由。生成证书固定RSA 3072 / SHA-256，HTTPS叶子需填写SAN。导入支持多层中间CA，必须带完整根链；不访问AIA、OCSP或CRL，不等同于目标系统已信任该链。CA补录私钥、登录限流及复杂权限不在当前实现范围。远程命令通过独立 Shell 步骤执行，不提供交互终端。通用同步组件保留兼容，具体第三方应用的HTTP契约需按相应组件配置并联调。

具体需求和数据模型见 [需求文档](doc/requirements.md)、[设计文档](doc/design.md)、[数据库设计](doc/database-design.md)。验收记录见 [MVP验收](doc/mvp-verification.md)。

当前需求范围、历史设计冲突、实现差异及待验收项见 [2026-11-14 需求与设计核对基线](doc/requirements-design-review-2026-11-14.md)。

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

## 证书启用状态

自有和客户证书列表提供启用/禁用开关。历史资产默认启用；禁用后保留查看导出，停止作为签发者、同步输入和到期通知对象，支持重新启用。TNPartner自动选证会跳过禁用资产。规则和验证见 [证书启用状态](doc/certificate-enabled.md)。
