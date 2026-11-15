# REST 接口文档

> 本文记录外部 IS/TN 服务接口，并非本项目 `/api/v1` 全部管理接口清单。当前业务组件和旧固定 HTTP 任务使用不同契约；远端待确认项不因本地实现存在而自动确认。区分规则见[需求与设计核对基线](requirements-design-review-2026-11-14.md)。

本文根据提供的接口记录持续整理，未明确的信息标记为“待确认”。

## 1. 获取 Keystore

### 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 获取 Keystore |
| URL | `/restv2/wm.certificate:cert/keystore/get` |
| 请求方法 | `GET` |
| 请求头 | `Content-Type: application/json` |
| 鉴权方式 | 待确认 |

### 请求参数

| 参数名 | 类型 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `path` | string | Query（GET 查询参数） | 待确认 | 文件全名路径，例如 `/opt/wm/is/cccc.jks` |

请求示例：

```http
GET /restv2/wm.certificate:cert/keystore/get?path=%2Fopt%2Fwm%2Fis%2Fcccc.jks HTTP/1.1
Content-Type: application/json
```

### 响应参数

| 参数名 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | 业务结果码：`"0"` 表示成功，`"1"` 表示失败 |
| `message` | string | 结果说明，成功示例为 `success` |
| `path` | string | 文件全名路径 |
| `content` | string | 文件内容的 Base64 编码字符串，解码后得到 Keystore 文件的原始字节数据 |

成功响应示例：

```json
{
  "code": "0",
  "message": "success",
  "path": "/opt/wm/is/cccc.jks",
  "content": "asdfasdfasdfasdfasdfasd"
}
```

示例中的 `content` 为占位内容。实际使用时，将接口返回的 `content` 进行 Base64 解码，即可得到文件字节数据，可用于后续处理或以二进制方式写入文件。

### 结果码

| code | 含义 |
| --- | --- |
| `"0"` | 成功 |
| `"1"` | 失败 |

### 待确认信息

- `path` 是否必填。
- 鉴权方式。
- 失败响应示例及 HTTP 状态码。

## 2. 写入并重载keystore

### 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 写入并重载keystore |
| 接口用途 | 写入新的 Keystore 文件，并重载 IS 中对应的 Keystore |
| URL | `/restv2/wm.certificate:cert/keystore/update` |
| 请求方法 | `POST` |
| 请求头 | `Content-Type: application/json` |
| 鉴权方式 | 待确认 |

### 请求参数

参数通过 JSON 请求体传递。

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyStoreName` | string | 待确认 | IS 中 Keystore 的名称 |
| `path` | string | 待确认 | Keystore 文件的全路径 |
| `content` | string | 待确认 | 新的 Keystore 文件内容，使用 Base64 编码字符串 |
| `type` | string | 是 | `key` 更新私钥库，`trust` 更新信任库，仅允许这两个值 |

请求体结构：

```json
{
  "keyStoreName": "",
  "path": "",
  "content": "",
  "type": "key"
}
```

调用时，将 `keyStoreName` 和 `path` 替换为实际的 Keystore 名称及文件全路径，将新文件的原始字节数据进行 Base64 编码后填入 `content`。`type` 采用用户确认的 `key` 或 `trust`。IS keyStore 更新组件的文件全路径在写入阶段配置；truststore 更新组件沿用读取阶段输出的 path。

### 响应参数

| 参数名 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | 业务结果码：`"0"` 表示成功，`"1"` 表示失败 |
| `message` | string | 结果说明，成功示例为 `success` |

成功响应示例：

```json
{
  "code": "0",
  "message": "success"
}
```

### 结果码

| code | 含义 |
| --- | --- |
| `"0"` | 成功 |
| `"1"` | 失败 |

### 待确认信息

- 请求参数是否必填。
- 鉴权方式。
- 失败响应示例及 HTTP 状态码。

## 3. 更新 TN 客户证书

### 基本信息

| 项目 | 内容 |
| --- | --- |
| 接口名称 | 更新 TN 客户证书 |
| URL | `/restv2/wm.certificate:cert/tn/update` |
| 请求方法 | `POST` |
| 请求头 | `Content-Type: application/json` |
| 鉴权方式 | 待确认 |

### 请求参数

参数通过 JSON 请求体传递。

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 待确认 | 客户标识，示例为 `987654321` |
| `idType` | string | 待确认 | 客户标识类型，示例为 `DUNS`，可选值待确认 |
| `certChain` | array<object> | 待确认 | 证书链，数组元素结构见下表 |

`certChain` 数组元素：

| 参数名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `index` | integer | 待确认 | 证书在链中的序号，示例为 `1`、`2`、`3`；排序规则及对应证书层级待确认 |
| `certificateBase64` | string | 待确认 | 证书的 Base64 编码字符串 |

请求示例：

```json
{
  "id": "987654321",
  "idType": "DUNS",
  "certChain": [
    {
      "index": 1,
      "certificateBase64": "MIIEKjCCApKgAwIBAgIUY2iSfBT9jgt5Hvr3ESPt52D7/cQwDQYJKoZIhvc..."
    },
    {
      "index": 2,
      "certificateBase64": "MIIELDCCApSgAwIBAgIUCa2MIdyzwpLIX0kTeV+xWJX6gw8wDQYJKoZIhvc..."
    },
    {
      "index": 3,
      "certificateBase64": "MIIEPzCCAqegAwIBAgIUJ/VLYzbw7YTWZuLgWh/+Uppa1hEwDQYJKoZIhvcN..."
    }
  ]
}
```

示例中的证书内容已截断，调用时需提供完整的 Base64 编码字符串。

### 响应参数

| 参数名 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | 业务结果码：`"0"` 表示成功，`"1"` 表示失败 |
| `message` | string | 结果说明，成功示例为 `success` |

成功响应示例：

```json
{
  "code": "0",
  "message": "success"
}
```

### 结果码

| code | 含义 |
| --- | --- |
| `"0"` | 成功 |
| `"1"` | 失败 |

### 待确认信息

- 请求参数是否必填。
- `idType` 的可选值。
- 证书链的排序规则、`index` 对应的证书层级及证书数量限制。
- 鉴权方式。
- 失败响应示例及 HTTP 状态码。
