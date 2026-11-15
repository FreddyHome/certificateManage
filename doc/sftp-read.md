# SFTP 文件读取与步骤输出

## 行为

SFTP 增加 READ 操作。用户选择现有服务器后，只填写远端绝对文件路径 sourcePath，无需输入文件名、远程目录、覆盖选项或证书来源。读取不修改源文件。

读取输出为本次执行内存文件：原始字节及路径最后一段的文件名，以步骤 key 为引用标识。后续 SFTP UPLOAD 和通用 HTTP 使用现有 sourceStep 选择它。HTTP 支持 BINARY、MULTIPART 和 JSON 的 ${fileBase64}，固定 HTTP 业务组件保持各自固定输入。KEYSTORE/TRUSTSTORE 仍以证书资产条目为输入，本次不添加文件解析、合并功能。

后端仅允许引用前序有文件输出的步骤：KEYSTORE、TRUSTSTORE、SFTP READ。READ 不接收 sourceStep 或 certificateId，不允许其他操作伪装成文件来源。前端提供一致的来源选项和自动文件名，用户在消费步骤仍可更改发送文件名。

单文件最多 10 MiB，本次执行累计生成/读取文件最多 25 MiB。读取前检查普通文件及大小，流读取再限制字节数，防止文件变化绕过大小限制。使用现有连接认证、超时和错误诊断。文件内容不写入结果 JSON 或日志；执行结果记录文件名、字节数及读取路径，运行结束清空内存字节。读取失败后后续步骤跳过。

## 实施计划

1. 增加真实 SFTP/HTTP 工作流测试：读取后原样上传、HTTP 二进制/Multipart/Base64、缺失文件、大小限制、来源顺序和非法路径。
2. RemoteTransport 提供带文件输出的 SFTP 读取入口，复用原认证连接及截止时间。
3. WorkflowValidator 注册 READ 为文件输出步骤，校验路径和输入；WorkflowRunner 将读取字节存入现有产物上下文并仅保存元数据。
4. 前端增加读取操作，完善 SFTP 和通用 HTTP 来源选择；测试文件来源筛选、文件名推导及配置保存。
5. 执行前后端验证、生产构建，重启服务并检查首页/API。
