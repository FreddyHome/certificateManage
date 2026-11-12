# 项目开发规范分析与本轮优化

日期：2026-11-12。范围：项目结构、构建配置、前端请求与客户页面、后端入口和关键服务的静态检查；不是全量安全审计或全部业务链路验收。

## 项目现状

- 后端为 Java 21 / Spring Boot 3.5.16 单模块，按 certificate、customer、server、sync 等业务分包；使用 JdbcTemplate、H2、Flyway 和 Spring Security。
- 前端为 Vue 3、TypeScript、Element Plus，使用 hash 路由和页面懒加载；生产构建嵌入后端 JAR。
- 已有数据库迁移和前后端回归测试。当前规模适合继续维护模块化单体，暂不需要拆分微服务或 Maven 多模块。

## 已完成的局部重构

| 位置 | 原问题 | 本轮处理 |
| --- | --- | --- |
| frontend/src/api/client.ts | 网络请求同时承担 UI 提示、格式转换和证书下载操作 | 保留 CSRF、JSON 与二进制请求；移出 UI 和业务职责，展开压缩代码 |
| frontend/src/composables/useAsyncAction.ts | 页面依赖网络基础层来显示错误 | 统一页面异步操作错误提示；更新所有调用处 |
| frontend/src/utils/format.ts | 日期和字段名转换混在请求模块 | 提取通用纯函数 |
| frontend/src/services/certificates.ts | 下载文件名和浏览器 DOM 操作绑在网络层 | 提取证书导出服务，复用二进制请求能力 |
| frontend/src/views/certificates/certificates.ts | 证书业务筛选规则放在通用 utils | 移入证书模块，同步迁移现有回归测试 |
| frontend/src/services/customers.ts | 页面直接拼接新增、编辑路径，缺少响应类型 | 定义 Customer 接口及查询、保存服务 |
| frontend/src/views/customers/CustomerView.vue | 脚本和模板压缩为长行、使用 any | 规范排版、显式类型、通过服务层访问接口 |

未新增依赖，保持请求路径、请求字段、页面文案、数据库及部署方式。新增客户请求和证书下载边界回归测试，覆盖请求方法、CSRF、密码传递、文件名与对象 URL 释放。

## 首轮评估建议（历史快照）

客户分层、同步查询/调度及前端同步拆分、请求层统一处理已在后续批次实施，当前状态见 [结构重构记录](structure-refactor.md)。下表保留首轮评估时的证据，不能当作当前未修复清单。

| 优先级 | 证据位置 | 建议与原因 |
| --- | --- | --- |
| 高 | CertificateService.saveImport、CertificateController.upload 调用 CustomerController.key | 将客户名称归一化规则下沉到客户模块，解除业务服务对 HTTP 入口的依赖 |
| 中 | CustomerController、UserController 等直接操作 JdbcTemplate；CustomerController 使用 Map 请求 | 按模块逐步分离请求 DTO、Service 事务和持久化职责，避免一次性全仓搬迁 |
| 中 | WorkflowService.list 对每个任务单独查询最后执行状态 | 改为批量查询或联表获取，避免任务数增长时产生 N+1 查询；用代表性数据验证查询数及耗时 |
| 中 | 其他页面仍直接调用 request，基础 request 默认返回 any | 按客户模块模式逐步补充业务服务和 DTO，再收紧基础请求默认类型 |
| 中 | main.ts 全量注册 Element Plus；生产主包约 1,045 kB，gzip 约 346 kB | 单独评估按需引入及分包，结合实际加载测量决定，不通过提高警告阈值掩盖问题 |
| 中 | 多数 Java 类缺少规范文档注释，部分方法压缩为一行 | 在修改对应业务时同步整理中文注释；作者和历史首次创建日期需要真实来源，不推测补填 |

## 本轮验证

- 修改前：前端 2 个测试文件、3 项测试通过；TypeScript 检查和生产构建通过。
- 修改后：前端 3 个测试文件、6 项测试通过；TypeScript 检查和生产构建通过。体积警告仍存在。
- 后端首次执行 `./mvnw.cmd test` 时，因全局 Maven 仓库目录 `D:/appData/maven/repo` 写权限失败而未进入编译或测试，当时默认 Java 运行时为 17。
- 2026-11-12 环境更新后复验：Java 和 Maven 均使用 JDK 21.0.10。普通沙箱仍拒绝创建缓存子目录；通过权限提升执行同一命令后，依赖下载和缓存写入成功，后端 29 项测试全部通过（失败 0、错误 0、跳过 0），Maven 返回 `BUILD SUCCESS`。
- 未修改 Java、SQL、依赖版本或生产数据；未启动系统进行浏览器业务验收，不能据此前端构建断言所有业务流程已验收。

后端验证已补齐。当前普通沙箱和提升权限后的缓存访问表现不同，后续构建仍需使用能写入该缓存目录的执行权限。
