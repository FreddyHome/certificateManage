# 同步业务组件验收记录

日期：2026-11-13

## 实现范围

- 新增入口仅提供 IS truststore 更新、IS keyStore 更新和 TN 证书更新；已有通用任务保留。
- truststore：SFTP/HTTP 来源读取，在原库上删除可选过期条目、导入所选链节点，自动 CN + SHA-256 别名，POST 写入重载。
- keyStore：按用户最新要求删除来源读取；第一步选择自有终端证书、格式及密码创建完整私钥链库；第二步配置 HTTP 服务器、URL、keyStoreName 和文件全路径。不会读取来源文件。
- TN：客户用途筛选、自动最新或指定链、冻结客户标识及完整链，按文档接口提交。
- 双服务器依赖、连接和秘密版本快照；阶段日志；新业务远端结果采用固定摘要，防止响应回显文件内容。

## 自动验证

- 完整后端：83 tests，0 failures，0 errors。
- 业务组件专项：26 tests，覆盖 JKS/PKCS12、SFTP/HTTP、过期清理、单节点导入、别名幂等及冲突、私钥源拒绝、独立JKS私钥密码、PKCS12密码约束、密码留空保留、自动最新与入队冻结、双服务器快照、非法响应、业务失败阻断、文件大小和累计25MiB限制、响应回显隔离、无来源keystore和路径/URL校验。
- 前端：7 suites / 20 tests 通过，Prettier、vue-tsc 和 Vite 生产构建通过。
- 独立审阅：keyStore两步调整及响应回显修复经复核，无未解决的重要发现。

## 打包与重启

完整 `-Pproduction verify` 的测试和前端阶段通过；首次 Spring Boot repackage 因原服务占用 JAR 无法重命名而失败。精确确认并停止旧进程后，使用已构建的前端及通过验证的类执行 `-DskipTests verify`，重新打包及 Spotless 检查成功。

启动 JAR：`target/certificate-manage-0.1.0.jar`。工作目录仍为 `D:\workspace\project\certificateManage`，沿用原 `data/certificate-manage.mv.db` 和 `data/master.key`，主密钥 SHA-256 前后校验一致。服务在本地 8080 端口启动，当前进程 PID 39324。

## 重启后检查

以下均返回 HTTP 200：

- 首页 `/`，包含应用挂载节点。
- 登录后 `/api/v1/auth/me`。
- `/api/v1/sync-tasks`。
- `/api/v1/servers`。
- `/api/v1/certificates?owner=OWN`。
- `/api/v1/certificates?owner=CUSTOMER`。
- 实际同步页面脚本分包与本次构建文件内容一致，包含三个业务标识和 keyStore 写入路径字段。

未对真实 IS/TN 服务器执行写入或重载。远端契约由本地真实 HTTP/SFTP 集成测试验证；真实环境的认证、权限、IS 重载及 TN 链顺序仍需使用目标服务器联调。浏览器检查工具超时，未进行界面点击验收。
