# 项目性能检查报告

检查日期：2026-11-16

> 后续状态：本报告保留优化前的检查基线。P1、P2已按用户要求实施，复测见[P1验收记录](performance-p1-verification-2026-11-16.md)与[P2验收记录](performance-p2-verification-2026-11-16.md)。审计保留期限/自动清理等剩余事项按P3另行处理，不能据此将历史数据自动删除。

代码基线：`75116809a6b53f748aae616f39c4a5391dc7324c`。本轮只检查和形成报告，未实施业务代码或正式运行配置优化。

## 1. 检查结论

优先处理证书列表的重复 SQL 和连接池争用，其次处理全量数据接口、首屏资源及重复请求。这些问题比直接增加 JVM 内存、数据库连接数更值得优先投入。

最明确的实测问题：在隔离环境的 10,000 条证书数据下，一次列表查询执行 10,001 次 SELECT；默认 10 个数据库连接时，10 个并发列表请求中有 9 个等待约 30 秒后返回 HTTP 500。异常栈定位在逐行查询提醒阈值的位置。

当前正式实例首页和 API 探测正常，空闲堆使用量约 46.8MiB，采样时没有 Full GC。上述并发故障发生在隔离测试实例，不能表述为正式实例已经发生故障。

## 2. 范围与方法

覆盖前端资源与页面请求、后端 API 和 SQL、H2 表结构及索引、证书解析与自动选取、工作流与执行计划、HTTP/SFTP/SSH 传输、邮件通知、审计日志、JVM 和部署参数。

检查采用静态代码审查、现有构建资源体积计算、原服务低频只读探测，以及独立实例的合成数据实验。未修改原 `data` 数据库或主密钥，未触发真实同步、远程命令或邮件发送。

| 项目 | 条件 |
| --- | --- |
| 本机 | Windows 11 专业版，Intel Core Ultra 7 155H，16 核/22 线程，约 31.5GiB 内存 |
| Java | JDK 21.0.10，G1，`-Xms128m -Xmx512m` |
| 原服务 | `127.0.0.1:8080`，PID 61056；只做首页、会话接口、静态响应头及 JVM 只读采样 |
| 隔离实例 | `127.0.0.1:18081`，独立数据库、独立主密钥、独立日志；测试后已关闭 |
| 数据集 | 10,000 条客户叶子资产，每条 3 个链节点；1,000 个未启用的草稿任务；100,000 条审计摘要 |
| 连接池 | 实测最大连接数 10，获取连接超时 30,000ms |
| API 方法 | 预热 3 次后采样；串行每项 10–30 次，5 并发 10 次，10 并发 10 次 |
| 原始结果 | 本地 `logs/performance-20261116/` 中的 `results.json`、`query-count.json`、`frontend-assets.json`、`original-service.json` 和异常日志；这些文件按项目规则不进入 Git |

数据集主要验证元数据查询和响应规模，证书 DER 使用占位材料，不用于密码学运算或真实业务执行。没有测量真实证书签发吞吐、远程网络速度、邮件吞吐和浏览器渲染耗时。任务没有真实执行历史，大型执行历史查询仅做静态审查。

HTTP 延迟包含本机客户端、连接、响应体接收和当时的 JIT/GC 状态；样本较少，P95 仅供本轮对比，不是生产容量或最大 QPS 承诺。合成数据压缩率也不代表真实数据压缩率。

## 3. 实测结果

### 3.1 API 响应

以下均来自隔离实例，响应体按实际接收字节统计：

| 场景 | 并发 | 请求数 | P50 | P95 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 首页 HTML | 1 | 30 | 14.62ms | 29.41ms | 30/30 为 200；仅 589B HTML，不等于页面可交互时间 |
| 已登录会话查询 | 1 | 30 | 13.03ms | 27.26ms | 30/30 为 200 |
| 10,000 条证书列表 | 1 | 15 | 110.38ms | 129.24ms | 15/15 为 200；响应 5,535,577B，约 5.28MiB |
| 1,000 个草稿任务列表 | 1 | 15 | 20.59ms | 39.17ms | 15/15 为 200；响应 333,787B |
| 100,000 条审计记录，首页 50 条 | 1 | 20 | 5.54ms | 26.17ms | 20/20 为 200 |
| 同一审计数据，第 2,000 页 | 1 | 10 | 4.16ms | 18.22ms | 10/10 为 200；本数据集未复现深页慢查询 |
| 10,000 条证书列表 | 5 | 10 | 246.05ms | 289.44ms | 10/10 为 200 |
| 10,000 条证书列表 | 10 | 10 | 30,218.90ms | 30,379.68ms | **9 次 500、1 次 200**；延迟含失败请求 |

### 3.2 证书列表查询

直接调用业务服务，在 3 次预热后测量 5 次，绕过 HTTP、会话和请求审计：

| 资产数 | 业务服务耗时中位数 |
| --- | --- |
| 100 | 9.33ms |
| 1,000 | 20.86ms |
| 10,000 | 102.08ms |

另用 JDBC 代理独立计数：10,000 条列表共执行 **10,001 次 SELECT，其中提醒阈值查询 10,000 次**。初次 H2 查询统计器未有效记录嵌套调用，其零值不作为证据；以上次数来自独立 JDBC 计数。

并发失败日志的关键内容：

```text
HikariPool-1 - Connection is not available,
request timed out after 30000ms (total=10, active=10, idle=0, waiting=...)
at CertificateService.dto(CertificateService.java:396)
at CertificateService.list(CertificateService.java:426)
```

### 3.3 前端资源与实际响应头

读取现有 `frontend/dist`，不重新构建；gzip 为本地压缩计算，并非已启用传输压缩：

| 资源 | 原始体积 | gzip level 9 |
| --- | --- | --- |
| 主 JS `index-Ck94_xeD.js` | 1,045,911B | 345,698B |
| 主 CSS `index-BHS3p0GS.css` | 365,041B | 48,642B |
| 入口 JS + CSS | **1,410,952B，约 1.35MiB** | **394,340B，约 385KiB** |

这两项占当前全部 JS/CSS 原始体积约 92.4%。gzip 体积约减少 72.1%，实际首页提速还取决于网络、解析、执行和渲染。

原服务和隔离实例均核对了主 JS：请求携带 `Accept-Encoding: gzip` 时，响应没有 `Content-Encoding`；`Cache-Control` 为 `no-cache, no-store, max-age=0, must-revalidate`。说明当前直连应用没有对该静态资源启用 gzip 和长期缓存。若将来加反向代理，需再次检查代理后的实际响应。

### 3.4 JVM 采样

- 原实例最大堆 512MiB，采样时已提交约 128MiB、使用约 46.8MiB，Metaspace 使用约 58.4MiB。
- 原实例本次 3 秒 GC 采样计数没有变化，启动以来记录的 Full GC 次数为 0。
- 隔离实验结束前堆使用约 270MiB，Full GC 次数为 0；其占用包含合成数据库缓存、数据生成和请求分配，不能归因于某一个 API。
- 本轮没有长期稳定性测试，不能据此断定无内存泄漏，也没有证据支持优先扩大堆或替换 GC。

## 4. 优化项与优先级

P1 表示本轮建议优先处理；P2 为数据和任务增长前应规划；P3 为有进一步指标后再决定。成本按相对改造范围标为低、中、高，不作为排期承诺。

| 编号 | 优先级 | 优化项 | 证据类型 | 成本 |
| --- | --- | --- | --- | --- |
| OPT-01 | P1 | 消除证书列表逐行 SQL 和连接池争用 | 并发故障及查询次数已实测 | 低 |
| OPT-02 | P1 | 证书服务端分页、筛选和列表字段裁剪 | 全量响应已实测，浏览器影响为推断 | 中高 |
| OPT-03 | P1 | 静态资源压缩与内容哈希缓存 | 体积和实际响应头已核对 | 低中 |
| OPT-04 | P1 | Element Plus 组件与样式按需引入 | 引用方式及产物体积已核对 | 中 |
| OPT-05 | P1 | 去除同步页面请求瀑布和保存后重复请求 | 代码路径确认 | 低 |
| OPT-06 | P2 | 服务器列表批量查询，避免为配置标志解密全部秘密 | 代码路径确认 | 中 |
| OPT-07 | P2 | 自动选证、链解析与执行校验按请求复用 | 代码路径确认，未测密码学吞吐 | 中 |
| OPT-08 | P2 | 独立任务受控并行，执行线程与调度线程分离 | 串行模型确认，未测远程吞吐 | 中高 |
| OPT-09 | P2 | 通知按目标日期筛选、减少重复扫描与单封建连 | 扫描和投递代码确认 | 中 |
| OPT-10 | P2 | 原始审计队列背压、批量写入和详情查询解耦 | 队列及事务实现确认 | 中高 |
| OPT-11 | P2 | 降低大文件 Base64、JSON 和审计重复复制 | 数据路径确认，未测峰值内存 | 中高 |
| OPT-12 | P2 | 远程 HTTP 客户端与 TLS 配置受控复用 | 每请求创建确认，收益依赖任务频率 | 中 |
| OPT-13 | P2 | 缩短全局锁持有时间，批量快照和引用写入 | 锁内处理路径确认 | 中高 |
| OPT-14 | P2/P3 | 日志深分页、索引匹配及历史归档 | 本数据集未复现慢查询，属于增长风险 | 中 |
| OPT-15 | P2 | 共享会话与选项状态，合并重复链查询 | 前端调用路径确认 | 低中 |
| OPT-16 | P2 | 建立 API、连接池、队列及远程阶段指标 | 当前诊断能力补充项 | 中 |

### OPT-01：证书列表重复 SQL 与连接池争用

证据：[CertificateService.java](../src/main/java/com/certmanage/certificate/CertificateService.java#L395)，395–396、424–431 行。

`dto()` 在外层 ResultSet 逐行映射期间再次调用 JdbcTemplate，读取相同的 `warning_days`。列表没有事务绑定同一连接，内层查询另取连接；并发外层查询占满池后，内层等待空闲连接，已在本轮复现超时。

建议在开始列表查询前读取一次阈值并传给 mapper，或把单行策略 JOIN 到列表查询中。一次请求应只有固定数量的 SQL，不随列表行数增长。修复后保留策略修改即时生效的语义。

增加连接池大小只会移动饱和点，不能消除重复查询；不应作为此问题的主要解决方案。

### OPT-02：服务端分页、筛选与字段裁剪

证据：[CertificateService.java](../src/main/java/com/certmanage/certificate/CertificateService.java#L424)，424–445 行；[CertificateView.vue](../frontend/src/views/certificates/CertificateView.vue#L46)，46–52、78、139–146 行；[ArchivedCertificateView.vue](../frontend/src/views/certificates/ArchivedCertificateView.vue#L23)。

普通及归档接口返回全量资产。客户和归档页面先拉取再前端分页，自有证书三张表直接渲染筛选后的全部记录。10,000 条合成记录响应已达约 5.28MiB，前端分页不能减少这部分网络、JSON 和响应式数据成本。

建议按归属、层级、客户、用途、状态和搜索条件做服务端分页；保留到期时间加 ID 的稳定排序。列表只返回当前表格必需字段，Subject、SAN、完整链等按详情读取。同步编辑器的证书选择改用按条件远程搜索，避免为选项再次拉全量列表。

### OPT-03 / OPT-04：首屏资源

证据：[main.ts](../frontend/src/main.ts#L2)，2–8 行；[application.yml](../src/main/resources/application.yml)；第 3.3 节实际资源响应头。

全量注册 Element Plus 并引入全量 CSS，使登录页也加载较大的入口资源。建议按使用情况引入组件与样式，覆盖消息、确认框等服务式调用；通过登录页和各功能页回归确认样式完整。

对含内容哈希的 `/assets/*` 配置长期 `immutable` 缓存，对 HTML 保留重新校验；按部署层启用 gzip 或 Brotli。敏感 API 和审计原文继续禁止缓存。仅拆分 chunk 而仍在入口全部加载，不能实质减少首屏工作量。

### OPT-05：请求瀑布及重复保存读取

证据：[useSyncTasks.ts](../frontend/src/views/sync/useSyncTasks.ts#L56)，56–62、98–110 行；[services/sync.ts](../frontend/src/services/sync.ts#L36)，36–42 行；[WorkflowService.java](../src/main/java/com/certmanage/sync/WorkflowService.java#L255)。

同步页目前按“任务列表 → 服务器 → 自有证书/客户证书/客户并行”分三个阶段请求。可并行加载独立数据，或先显示列表，在打开编辑器时加载选项。

保存接口已返回任务详情，前端仍再次 GET 详情，再刷新列表。直接使用保存响应可减少一次请求及一个串行阶段。“保存并校验”中的列表刷新与校验，也可在保留正确时序的前提下并行。

### OPT-06：服务器列表

证据：[ServerService.java](../src/main/java/com/certmanage/server/ServerService.java#L91)，91–106、122–143、153–158 行。

列表先取所有服务器 ID，再逐台读取服务器、配置、秘密和信任材料，约为 `1 + (3～4) × N` 次 SQL。凭据先解密，再移除明文并返回“已配置”标志。`connection()` 的 RowMapper 同样含嵌套数据库读取，应一并检查连接池竞争。

建议列表使用专门摘要 DTO 和批量查询；将“是否配置凭据”作为非秘密元数据读取，编辑或执行时才按需解密。不能让性能优化改变秘密留空保留、配置修订和信任材料校验语义。

### OPT-07：自动选证与链解析复用

证据：[BusinessComponents.java](../src/main/java/com/certmanage/sync/BusinessComponents.java#L174)，174–183、205–219、246–253、373–379 行；[WorkflowRunner.java](../src/main/java/com/certmanage/sync/WorkflowRunner.java#L178)，178–190 行；[CertificateService.java](../src/main/java/com/certmanage/certificate/CertificateService.java#L65)。

自动模式先查询并排序候选，再逐候选读取链、解析 DER 和检查有效期。选中后还会重复检查；执行时每个步骤重新查询本次运行全部证书引用，并再次校验步骤引用。

建议先将整链有效性的筛选下推到批量 SQL，再对最终候选做必要的密码学校验；在单次入队、校验或步骤内复用不可变公钥材料及解析结果，批量取得引用。缓存若跨请求使用，必须有容量上限，并保留有效期与启停状态的实时判断。私钥不应因减少查询而无期限常驻缓存。

必须保留同 CN、客户/用途、整链有效、入队冻结及执行前启停检查等现有业务规则。

### OPT-08：执行吞吐与调度隔离

证据：[WorkflowWorker.java](../src/main/java/com/certmanage/sync/WorkflowWorker.java#L13)，13–15 行；[WorkflowRunner.java](../src/main/java/com/certmanage/sync/WorkflowRunner.java#L87)，87–94 行。

当前执行方法每次同步运行一个任务，完成后再固定等待 1 秒；不同任务也经由这一执行通路串行处理。调度线程池为 4，并不使这个固定延迟执行方法自动并行。慢 HTTP 或最长 300 秒的 Shell 会占用执行通路，延长其他任务等待。

建议独立的有界执行线程池，允许不同任务小并发运行，同时维持同一任务互斥、领取租约及恢复语义；再按目标服务器设置并发上限。心跳、计划扫描和日志刷新与远程长操作分离。先解决数据库连接争用和内存复制，再提高任务并发，避免放大其他瓶颈。

### OPT-09：通知扫描及发送

证据：[NotificationService.java](../src/main/java/com/certmanage/notification/NotificationService.java#L194)，194–227、238–242、262–320 行；[SmtpService.java](../src/main/java/com/certmanage/smtp/SmtpService.java#L18)。

策略启用且到达当天检查时间后，后台每分钟取全部有效资产，在 Java 中计算提醒天数。已经生成过的当天投递仍会尝试插入，依靠唯一键异常去重。发送端每轮最多领取 20 条并逐条发送，每封重新读取配置、构造 sender 并连接。

建议按提醒节点换算到期时间区间并下推 SQL，只查询尚缺投递；维护扫描进度时需支持当天新增证书和规则变更，不能只扫描一次就忽略增量。保留唯一键作为并发兜底。发送可按已保存配置版本批量复用连接，并设置有限批量、超时和独立工作线程。

当前关闭策略或未到检查时间会提前返回，不应描述为全天不停地全量发送。

### OPT-10：审计队列、写入和详情读取

证据：[RawExecutionLog.java](../src/main/java/com/certmanage/common/RawExecutionLog.java#L29)，29、302–319、340–377、406–408、439–440 行；[ExecutionLog.java](../src/main/java/com/certmanage/common/ExecutionLog.java#L18)。

原始日志与完成后摘要补写队列都未见容量上限。数据库变慢或持续故障时，待写密文可能不断占堆。原始日志每条单独开启新事务；详情查询还会在请求线程同步尝试刷最多 200 条，读取延迟会受到积压写入影响。

建议按字节数和事件数限制内存队列，配合可靠本地落盘缓冲、积压告警和可恢复补写；后台批量写入并避免 GET 详情承担批量刷新。日志时间线和 metadata 可分页，载荷继续按需读取。不能简单丢弃完整原始审计，也不能因补写失败重放远程操作。

现有实现已包含异步单写线程、事务内异步处理和防重入，不能把所有审计写入都归为同步阻塞。

### OPT-11：文件和原始报文的内存放大

证据：[BusinessComponents.java](../src/main/java/com/certmanage/sync/BusinessComponents.java#L438)，438–445、486–494 行；[RemoteTransport.java](../src/main/java/com/certmanage/server/RemoteTransport.java#L742)，742–797、835–844 行；[RawExecutionLog.java](../src/main/java/com/certmanage/common/RawExecutionLog.java#L302)。

库文件先转 Base64 放入 JSON；HTTP 记录又把整个 JSON 请求体转一次 Base64，再序列化和加密持久化。10MiB 文件的第一次 Base64 约 13.3MiB，第二层约 17.8MiB，期间还有原数组、字符串、JSON 字节和密文等对象。产物 25MiB 限制不等于总堆占用上限。

建议减少重复编码，以独立加密二进制载荷保存完整文件和报文，采用分块处理或流式加密；必要时按完整审计要求设计压缩存储。不得把原始记录改为静默截断。真实峰值需要用脱敏大库文件和 JFR/分配分析进一步验证，本轮未测得 OOM。

### OPT-12：HTTP 与 TLS 复用

证据：[RemoteTransport.java](../src/main/java/com/certmanage/server/RemoteTransport.java#L744)，744–748、990–1040 行；[SshCommandTransport.java](../src/main/java/com/certmanage/server/SshCommandTransport.java#L45)。

每次 HTTP 调用新建并关闭 HttpClient，TLS 上下文也在请求路径构建；SFTP/SSH 也逐次创建客户端及会话。频繁同步时存在重复连接和握手成本。

优先按服务器配置版本和信任材料版本有界复用 HTTP 客户端/TLS 上下文，配置更新后失效并释放。SSH/SFTP 连接池需另行评估失效连接、凭据轮换和资源上限，低频任务未必值得引入该复杂度。不能关闭 HTTPS 身份校验来换取速度。

### OPT-13：缩短全局锁

证据：[WorkflowService.java](../src/main/java/com/certmanage/sync/WorkflowService.java#L74)，74、123、358–445 行；[WorkflowScheduler.java](../src/main/java/com/certmanage/sync/WorkflowScheduler.java#L44)。

保存和入队在唯一 `sync_guard` 行锁内进行多次配置读取、证书校验、解密、JSON 构造和引用写入。重复使用同一服务器的多个步骤会先重复获取快照，最后才用 Map 去重。证书启停、归档和服务器配置也竞争该锁。

先做请求内去重、批量读取与批量写入，再将可移出的纯计算移出锁；提交前仍需在锁内检查修订、启用状态、引用和互斥。锁承担一致性职责，不能直接删除。当前远程 HTTP/SSH 调用不在该锁事务中，应继续保持。

### OPT-14：日志与历史增长

证据：[ExecutionLog.java](../src/main/java/com/certmanage/common/ExecutionLog.java#L219)，219–256 行；[V5 索引](../src/main/resources/db/migration/V5__execution_audit_logs.sql#L9)；[WorkflowQueries.java](../src/main/java/com/certmanage/sync/WorkflowQueries.java#L82)，82、158–164 行。

审计列表每页 count，并采用 offset 深分页；过滤字段索引与时间排序组合不完全一致。任务历史使用 `select *`，而展示只需要摘要字段，且查询历史前额外解析任务步骤。任务列表本身也不分页。

建议按需总数、游标分页、明确字段投影和轻量存在性检查；在百万级数据及真实 source/run/time 分布下使用 EXPLAIN ANALYZE，再决定复合索引。当前已有时间、运行、来源及外键/唯一索引，不能泛称“没有索引”。本轮 100,000 条日志深页没有显示明显变慢，不将它列为当前实测瓶颈。

原始报文、执行快照和历史配置长期累积也会增加存储与备份成本。应先明确业务保留要求，再设计可查询归档；本轮不新增清理动作或删除数据。

### OPT-15：前端状态和重复链请求

证据：[router/index.ts](../frontend/src/router/index.ts#L40)、[AdminLayout.vue](../frontend/src/layouts/AdminLayout.vue#L10)、[BusinessStepConfig.vue](../frontend/src/views/sync/BusinessStepConfig.vue#L52)、[certificates.ts](../frontend/src/views/certificates/certificates.ts#L15)。

每次导航先等待 `/auth/me`，首次布局还会再次获取用户。每个业务步骤独立立即查询证书链，相同证书在多个步骤中重复请求。证书搜索输入还会重新过滤并排序全量数据。

建议共享会话状态与在途请求、编辑会话级链缓存和请求合并、排序与搜索分离；结合服务端分页处理规模。遇到 401、退出、改密和资产变更时及时失效。预览已有 300ms 防抖与代际检查，继续保留；可以再用取消请求减少已过时预览的后台工作。

### OPT-16：建立性能指标

建议补充 API 分位耗时/错误率、Hikari active/idle/pending/timeout、任务排队时长和各远程阶段耗时、审计积压条数及字节数、通知积压和 JVM 分配/GC 指标。记录 p95/p99 时应有足够采样量。

指标需要受控访问并避免采集凭据、私钥和原始业务正文。现有执行日志可以排错，但不足以直接得到上述持续性能分布。

## 5. 推荐实施顺序与验收方式

| 阶段 | 范围 | 建议验收 |
| --- | --- | --- |
| 第一阶段 | OPT-01、02、03、05 | 同一数据集 10/20 并发无连接池等待超时；列表 SQL 次数固定；分页最多返回约定页大小；保存少一次详情 GET；静态压缩和哈希缓存响应头生效 |
| 第二阶段 | OPT-04、06、07、15 | 对比入口体积、真实浏览器加载和渲染；服务器 SQL 不随每条记录重复扩张；相同链在编辑会话内合并请求；资产变更后正确刷新 |
| 第三阶段 | OPT-08 至 14、16 | 慢远程桩和受控故障下，独立任务可限流并行、同任务不重入、心跳正常；数据库故障时审计积压有界且可恢复，远程副作用不重放；大文件堆峰值与日志增长可观测 |

第一阶段修复后，可将“10,000 条资产、单页 50 条、10 并发，本机预热后 P95 小于 300ms、零 5xx”作为初步回归目标，再按实际部署硬件和网络调整。这是建议目标，尚未实现或验收。

本轮不建议直接迁移数据库、增加机器、扩大连接池或提高任务并发来替代查询修复；也不建议降低 RSA 密钥强度、口令哈希成本或关闭证书校验。

## 6. 已有措施与结束状态

已有并应保留的措施包括路由懒加载、审计服务端分页、执行历史条数限制、连接/命令超时、文件及产物上限、任务队列上限、同任务互斥、租约心跳、预览防抖、秘密加密及远程副作用与审计补写分离。

隔离测试实例已关闭，18081 端口不再监听。原服务继续使用原目录、数据库和主密钥，8080 首页与 CSRF API 为 200，匿名 `/api/v1/auth/me` 为预期的 401。本轮未改业务代码或正式运行配置，因此未重新打包或重启原服务。
