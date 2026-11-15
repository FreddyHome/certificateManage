# 固定 HTTP 同步组件

> 兼容范围说明：本文为旧固定 HTTP 任务契约，保留兼容。当前新建 IS/TN 业务组件使用 `rest-api.md` 记录的 `/restv2/wm.certificate:cert/...` 接口，不能将旧 URL 和成功判定套用到新组件。见[需求与设计核对基线](requirements-design-review-2026-11-14.md)。

日期：2026-11-12。依据：用户指定的两个 webMethods 接口及本轮确认的 GET、证书选择规则。

## 设计与兼容

现有 HTTP 显示为“通用 HTTP”，保留旧配置行为。两个新固定组件复用 HTTP 服务器连接、认证、超时和信任配置。数据库 component_type 仍为 HTTP，config.httpComponent 区分 GENERIC、RELOAD_KEYSTORE、UPDATE_TN_PARTNER；无需修改旧迁移或已有任务。

重载 Keystore：固定 GET /wm/is/cert/reload，表单录入 keyStoreName 和 type，后端转为 URL 查询参数。解析响应 isError（布尔或字符串），false 成功，true 失败；缺失或非法判定字段失败。

更新 TNPartner：固定 POST /wm/tn/partner/cert/update；表单选择客户与用途，客户 external_id/id_type 分别作为请求 id/idType。选择属于该客户和用途、当前有效且到期时间最晚的证书，等到期以资产 ID 倒序稳定选择；完整链也必须有效。certChain 按根到终端排列，index 从 1 起，certificateBase64 使用 X509Certificate.getEncoded() 的 DER 字节进行标准 Base64 编码。响应 code 数字或字符串 0 成功，其余及缺失值失败。

两者路径、方法、固定报文和响应判定均由后端确定，不能被客户端通用字段覆盖。成功和失败均将响应 message 写入执行日志，日志不写完整响应报文或证书 Base64。保留 HTTP 状态码检查、响应体大小限制、无自动重试、步骤失败停止后续步骤等规则。

## 数据时机

任务保存/校验时检查必要配置。每次入队重新选择证书、客户标识，并冻结在执行快照；链节点加入现有证书引用保护，执行前继续检查证书有效性。客户缺少标识、无匹配有效证书或链不可用时，阻止就绪/入队并提示原因。

## 实施与验收

- [x] 后端固定配置与客户/证书链解析，复用已有执行快照和引用保护。
- [x] HTTP 业务响应判定及成功/失败 message 日志。
- [x] 前端三个组件入口、固定配置表单、客户和用途选择。
- [x] 回归固定 URL/方法/参数、响应判定、Base64/链顺序、证书选择/快照及旧通用 HTTP 兼容。
- [x] 完整 Java/前端格式、测试、类型检查和生产前端构建；静态复核。

## 验证记录

后端42项测试、Java格式检查、前端15项测试、TypeScript检查和生产前端构建通过。回归包含真实本地HTTP：GET/query编码、POST报文、X509Certificate.getEncoded逐张比对、有效到期最晚选择、客户标识快照冻结、引用保护、成功失败message日志、失败停止后续步骤。另覆盖非2xx即使业务码成功也必须失败且保留message，以及非JSON响应安全兜底。

响应message会进行长度限制、控制字符清理和已配置秘密值脱敏；不会将整段响应或证书Base64写入日志。

独立审查发现固定报文误入通用模板替换的边界，已加入客户标识含字面占位符的真实HTTP回归并修正；固定JSON直接发送快照内容，只有通用HTTP执行模板替换。
