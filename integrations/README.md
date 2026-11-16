# 对接程序

本目录用于存放与证书管理系统对接的程序。

可按对接系统或程序名称创建子目录，并在各自目录中维护源码、配置示例和运行说明。真实凭据、私钥及运行数据不提交到仓库。

## WmCertificateManage对接包

[WmCertificateManage.zip](WmCertificateManage.zip)是部署在webMethods Integration Server上的对接程序。包内manifest声明版本1.0，目标IS版本11.1.0.0；其他版本及目标环境权限需联调验证。TN功能还依赖目标环境提供被调用的Trading Networks服务。

本说明根据ZIP内REST资源`ns/wm/certificate/cert/node.ndf`、三个Flow服务定义及证书管理系统实际调用代码整理，未修改原始ZIP，不代表已经在目标IS完成接口联调。

## 1. 通用调用约定

- 基础地址为目标IS地址，例如`https://is.example.com:5555`，不是证书管理系统地址。
- REST资源为`wm.certificate:cert`，三个入口统一以`/restv2/wm.certificate:cert`开头。请求使用`Accept: application/json`，POST另加`Content-Type: application/json`。
- 认证及ACL以目标IS配置为准。可使用获准调用这些服务的IS账号进行Basic认证；文件读写还取决于IS进程的操作系统权限。本系统在“服务器管理”配置目标IS基础地址和认证，业务组件复用该配置。
- 返回业务字段`code`和`message`，当前包使用字符串`"0"`表示成功、`"1"`表示捕获到业务异常。HTTP状态仍需单独检查，认证、路由等失败可能不返回该JSON。
- 本系统判定为HTTP 2xx且`code`为字符串或数值0；错误时展示或记录`message`。写入可能已经发生，失败后先核查目标状态，不自动重试。
- `path`是目标IS所在机器的文件路径，按本系统配置约定使用完整绝对路径，不是浏览器、证书管理系统本机或上传文件名。当前包直接读写该路径，不提供目录白名单校验；调用账号及可访问文件范围应按实际部署限定。

下列curl示例使用Bash语法，`IS_USER`为调用账号，curl会提示输入密码。域名、目录、客户标识和Base64均为示例，不应直接用于真实更新。

## 2. 读取证书库文件

**GET `/restv2/wm.certificate:cert/keystore/get`**

内部服务：`wm.certificate.cert_.services:getTruststore`。该接口读取文件，不刷新IS配置。

| 参数 | 位置/类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `path` | Query / string | 是 | 目标IS可读取的证书库文件绝对路径；文件须存在 |

```bash
curl --user "$IS_USER" --get \
  'https://is.example.com:5555/restv2/wm.certificate:cert/keystore/get' \
  --header 'Accept: application/json' \
  --data-urlencode 'path=/opt/certificates/partner-truststore.jks'
```

成功响应：

```json
{
  "code": "0",
  "message": "success",
  "path": "/opt/certificates/partner-truststore.jks",
  "content": "BASE64_OF_COMPLETE_STORE_FILE"
}
```

`content`为整个文件原始字节的Base64，不是PEM正文，也不是单张证书。`path`返回读取时的路径，本系统后续更新truststore时沿用该路径。接口本身不要求Store密码；本系统读取返回文件后，用任务中配置的Store密码解析、处理和重新生成证书库。

## 3. 更新证书库并刷新IS

**POST `/restv2/wm.certificate:cert/keystore/update`**

内部服务：`wm.certificate.cert_.services:putTruststore`。用于IS truststore和keyStore，操作顺序为：解码Base64 → 将原文件重命名为`原路径.yyyyMMddHHmmss`备份 → 写入新文件 → 调用`wm.server.security.keystore:refreshStore`刷新。

| 参数 | JSON类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyStoreName` | string | 是 | 目标IS已经配置的证书库名称，不是文件名 |
| `type` | string | 是 | `trust`表示truststore；`key`表示keyStore，本系统自动按组件传入 |
| `path` | string | 是 | 目标证书库文件的绝对路径，应与该IS证书库配置一致 |
| `content` | string | 是 | 整个JKS或PKCS12文件原始字节的Base64 |

`store-update.json`示例：

```json
{
  "keyStoreName": "PartnerTrustStore",
  "type": "trust",
  "path": "/opt/certificates/partner-truststore.jks",
  "content": "BASE64_OF_COMPLETE_STORE_FILE"
}
```

```bash
curl --user "$IS_USER" \
  'https://is.example.com:5555/restv2/wm.certificate:cert/keystore/update' \
  --header 'Accept: application/json' \
  --header 'Content-Type: application/json' \
  --data-binary @store-update.json
```

成功响应：

```json
{
  "code": "0",
  "message": "success"
}
```

使用注意：

- 当前流程先备份原文件，原路径不存在或重命名失败时会失败，不是用于任意新路径首次创建文件的接口。目标目录和IS证书库配置需事先准备。
- 请求不包含Store密码或私钥密码。生成文件所用密码必须与IS现有配置匹配；本系统在任务中维护这些密码，不把它们作为本接口字段传递。
- 备份失败、写入失败或刷新返回错误时进入失败响应；写入或刷新失败不自动恢复原文件。应检查原路径、时间戳备份和IS当前加载状态后再决定后续操作。
- keyStore组件传入`type=key`，内容为自有叶子私钥及完整链制作的库；truststore组件传入`type=trust`，内容为处理后的可信证书库。

## 4. 更新TN业务组件证书链

**POST `/restv2/wm.certificate:cert/tn/update`**

内部服务：`wm.certificate.cert_.services:updateCertificate`。通过`wm.tn.profile:getInternalID`查找业务组件，再更新其已有证书用途记录。

| 参数 | JSON类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | TN业务组件的外部标识，不是本系统客户主键 |
| `idType` | string | 本系统要求 | 外部标识类型，例如`DUNS`，应与TN配置一致；包内服务签名标为可选，本系统为避免歧义要求客户配置该值 |
| `certChain` | array | 是 | 从根到叶子的完整公钥证书链，不包含私钥 |
| `certChain[].index` | 序号 | 是 | 从1开始，根为1，后续节点递增；包内签名声明string，本系统JSON发送数值序号，目标IS的解析转换需在联调时确认 |
| `certChain[].certificateBase64` | string | 是 | 单张X.509证书DER字节的Base64，不带PEM头尾，不是整个证书库或整链PEM的Base64 |

`tn-update.json`示例（三层链）：

```json
{
  "id": "987654321",
  "idType": "DUNS",
  "certChain": [
    { "index": 1, "certificateBase64": "BASE64_OF_ROOT_DER" },
    { "index": 2, "certificateBase64": "BASE64_OF_INTERMEDIATE_DER" },
    { "index": 3, "certificateBase64": "BASE64_OF_LEAF_DER" }
  ]
}
```

```bash
curl --user "$IS_USER" \
  'https://is.example.com:5555/restv2/wm.certificate:cert/tn/update' \
  --header 'Accept: application/json' \
  --header 'Content-Type: application/json' \
  --data-binary @tn-update.json
```

成功响应：

```json
{
  "code": "0",
  "message": "success."
}
```

当前包内部按`index`降序排列，再逐张解码成供TN使用的链字节数组。外部调用方仍按根→叶子编号，本系统自动生成该请求。

当前实现依次查询`sign`、`decrypt`、`ssl`三种用途，有既有`chainBytes`的记录才调用`wm.tn.security:updateCertificateData`；没有记录则跳过，不自动首次创建。接口没有`usage`或`purpose`参数，因此不是只更新本系统所选用途对应的一个TN记录。本系统的“客户/用途”用于选择输入证书链。

流程没有异常时返回`code=0`，但不返回实际更新数量；三种用途全部跳过也可能返回成功。对首次初始化或多用途业务组件，应在TN中核对实际证书变化，不能把`code=0`解释为三种用途均已创建或更新。多用途更新中途失败不自动回滚已成功部分。

## 5. 失败响应与本系统配置对应

三个接口捕获业务异常时统一返回：

```json
{
  "code": "1",
  "message": "具体失败原因"
}
```

认证/ACL、REST路由或平台层异常可能表现为非2xx或其他响应格式；先核对HTTP状态，再检查JSON业务码。

| 本系统组件 | 使用方式 |
| --- | --- |
| IS truststore更新（HTTP来源） | GET读取原文件 → 本系统解析并更新可信条目 → POST更新库，`type=trust`；GET路径参数及返回path供更新阶段使用 |
| IS truststore更新（SFTP来源） | 原文件通过SFTP读取，不调用GET接口；处理后仍POST更新库并刷新，`type=trust` |
| IS keyStore更新 | 本系统按选定规则制作含私钥及完整链的库 → POST更新库，`type=key`，path取任务配置的完整文件路径 |
| TN证书更新 | 本系统按客户/用途选链并生成id、idType及DER节点Base64 → POST更新TN |

这些路径是新业务组件的默认值，可在任务中配置相应接口路径。它们与旧兼容的`/wm/is/cert/reload`、`/wm/tn/partner/cert/update`不是同一套接口，不应混用请求字段及成功判定。
