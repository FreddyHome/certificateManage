<script setup lang="ts">
import { computed, ref, watch, onScopeDispose } from 'vue';
import {
  certificateChain,
  chainRevision,
  useCertificateChainSession,
} from '../../services/certificate-chain';
useCertificateChainSession();
import RequestParameters from '../../components/RequestParameters.vue';
import { eligibleChains, eligibleOwnChains } from './business-editor';
import { certificateLabel } from './sync-editor';
import type {
  SyncCertificate,
  SyncChainNode,
  SyncCustomer,
  SyncServer,
  TransferStep,
} from '../../services/sync-types';

const props = defineProps<{
  s: TransferStep;
  servers: SyncServer[];
  certs: SyncCertificate[];
  customers: SyncCustomer[];
}>();
const c = computed(() => props.s.config);
const isTn = computed(() => c.value.businessComponent === 'TN_CERTIFICATE');
const isKey = computed(() => c.value.businessComponent === 'IS_KEYSTORE');
const chains = computed(() => eligibleChains(props.certs, c.value.customerId, c.value.purpose));
const own = computed(() =>
  props.certs.filter(
    (v) =>
      v.owner === 'OWN' &&
      v.kind === 'LEAF' &&
      v.hasKey &&
      ((c.value.selectionMode !== 'MANUAL' && v.id === c.value.certificateId) ||
        (v.enabled !== false && v.chainAvailable !== false && !v.archivedAt)),
  ),
);
const ownChains = computed(() =>
  eligibleOwnChains(props.certs, c.value.certificateId, c.value.certificateCn),
);
const selected = computed(() =>
  isKey.value
    ? c.value.selectionMode === 'MANUAL'
      ? c.value.certificateId
      : ownChains.value[0]?.id
    : c.value.selectionMode === 'MANUAL'
      ? c.value.certificateId
      : chains.value[0]?.id,
);
const customer = computed(() => props.customers.find((v) => v.id === c.value.customerId));
const nodes = ref<SyncChainNode[]>([]);
const loading = ref(false);
const chainError = ref('');
let generation = 0;
onScopeDispose(() => {
  generation++;
});
watch(
  () => [selected.value, chainRevision.value] as const,
  async ([id]) => {
    const current = ++generation;
    nodes.value = [];
    chainError.value = '';
    if (!id) {
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const chain = await certificateChain(id);
      if (current !== generation) return;
      nodes.value = chain;
      if (
        chain.some(
          (n) => Date.parse(n.notBefore) > Date.now() || Date.parse(n.notAfter) <= Date.now(),
        )
      )
        chainError.value = '该链包含尚未生效或已过期的证书，请选择其他可用链。';
    } catch (error) {
      if (current === generation)
        chainError.value = error instanceof Error ? error.message : '证书链读取失败';
    } finally {
      if (current === generation) loading.value = false;
    }
  },
  { immediate: true },
);

function resetChain() {
  c.value.certificateId = null;
  c.value.selectionMode = 'AUTO';
  delete c.value.materialIds;
}
function chooseChain() {
  delete c.value.materialIds;
}
function changeMode() {
  delete c.value.materialIds;
  c.value.certificateId = c.value.selectionMode === 'MANUAL' ? chains.value[0]?.id || null : null;
}
function toggleNode(materialId: number, checked: boolean) {
  const ids = new Set(c.value.materialIds || nodes.value.map((n) => n.materialId));
  if (checked) ids.add(materialId);
  else ids.delete(materialId);
  c.value.certificateId = selected.value || null;
  c.value.selectionMode = 'MANUAL';
  c.value.materialIds = [...ids];
}
function changeSource() {
  c.value.sourceServerId = null;
}
function chooseSourceServer() {
  if (c.value.sourceType === 'HTTP' && !c.value.serverId)
    c.value.serverId = c.value.sourceServerId || null;
}
function resolvedUrl(serverId: number | null | undefined, path: string | undefined) {
  const server = props.servers.find((v) => v.id === serverId);
  const baseUrl = server?.config?.baseUrl;
  if (!path || !baseUrl || path.includes('://')) return path || '';
  return baseUrl.replace(/\/+$/, '') + path;
}
</script>

<template>
  <div class="business-flow">
    <section v-if="!isTn && !isKey" class="business-stage">
      <h3><span>1</span> 设置 {{ isKey ? 'keystore' : 'truststore' }} 来源</h3>
      <div class="business-grid">
        <el-form-item label="来源">
          <el-select v-model="c.sourceType" @change="changeSource"
            ><el-option label="SFTP" value="SFTP" /><el-option label="HTTP" value="HTTP"
          /></el-select>
        </el-form-item>
        <el-form-item label="来源服务器连接名">
          <el-select v-model="c.sourceServerId" filterable @change="chooseSourceServer"
            ><el-option
              v-for="server in servers.filter((v) => v.enabled && v.type === c.sourceType)"
              :key="server.id"
              :label="server.alias"
              :value="server.id"
          /></el-select>
        </el-form-item>
      </div>
      <el-form-item label="文件全名 path（包含路径）" required
        ><el-input v-model="c.sourcePath" placeholder="/opt/wm/is/certificates.jks"
      /></el-form-item>
      <template v-if="c.sourceType === 'HTTP'">
        <el-form-item label="读取 URL" required
          ><el-input v-model="c.sourceUrl" /><small>{{
            resolvedUrl(c.sourceServerId, c.sourceUrl)
          }}</small></el-form-item
        >
        <el-form-item label="请求方法"
          ><el-select v-model="c.sourceMethod"
            ><el-option label="GET" value="GET" /><el-option label="POST" value="POST" /></el-select
        ></el-form-item>
        <el-form-item label="请求头 Headers"
          ><RequestParameters
            :model-value="c.sourceHeaders || {}"
            @update:model-value="c.sourceHeaders = $event"
        /></el-form-item>
        <el-form-item label="查询参数 Query"
          ><div class="parameter-note">path = {{ c.sourcePath || '上方文件全名' }}（自动传入）</div>
          <RequestParameters
            :model-value="c.sourceQuery || {}"
            @update:model-value="c.sourceQuery = $event"
        /></el-form-item>
      </template>
      <p class="stage-note">
        {{
          c.sourceType === 'SFTP' ? '动作：读取文件。' : '读取响应 content 经 Base64 解码。'
        }}输出：path（文件全名）、fileContent（文件内容）。
      </p>
    </section>

    <section class="business-stage">
      <h3>
        <span>{{ isTn || isKey ? 1 : 2 }}</span>
        {{ isTn ? '选择证书链' : isKey ? '创建 keystore' : 'truststore 操作' }}
      </h3>
      <template v-if="!isTn">
        <el-form-item v-if="isKey" label="keystore 格式"
          ><el-select v-model="c.format"
            ><el-option label="PKCS12" value="PKCS12" /><el-option
              label="JKS"
              value="JKS" /></el-select
        ></el-form-item>
        <div class="business-grid">
          <el-form-item
            :label="`${isKey ? 'keystore' : 'truststore'} 密码（与原配置一致）`"
            required
            ><el-input
              v-model="c.password"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="
                c.passwordConfigured
                  ? '已配置，留空保留'
                  : isKey
                    ? '输入与IS配置一致的库密码'
                    : '输入原库密码'
              "
          /></el-form-item>
          <el-form-item v-if="isKey" label="私钥密码（与原配置一致）" required
            ><el-input
              v-model="c.keyPassword"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="c.keyPasswordConfigured ? '已配置，留空保留' : '输入私钥密码'"
          /></el-form-item>
        </div>
        <p v-if="isKey" class="stage-note">
          创建仅包含所选私钥及完整链的 keystore。PKCS12 要求库密码与私钥密码一致。
        </p>
        <el-checkbox v-else v-model="c.deleteExpired">删除原 truststore 中已过期的证书</el-checkbox>
      </template>
      <el-form-item v-if="isKey" label="证书选择方式">
        <el-radio-group v-model="c.selectionMode">
          <el-radio value="AUTO">自动选择同 CN 到期最晚的可用链</el-radio>
          <el-radio value="MANUAL">指定证书</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item
        v-if="isKey"
        :label="c.selectionMode === 'MANUAL' ? '自有证书（终端证书）' : '匹配 CN 的基准叶子证书'"
        required
      >
        <el-select v-model="c.certificateId" filterable @change="delete c.certificateCn"
          ><el-option
            v-if="
              c.selectionMode !== 'MANUAL' &&
              c.certificateCn &&
              !own.some((cert) => cert.id === c.certificateId)
            "
            :value="c.certificateId!"
            :label="`${c.certificateCn}（原基准）`" />
          <el-option
            v-for="cert in own"
            :key="cert.id"
            :value="cert.id"
            :label="certificateLabel(cert)"
        /></el-select>
      </el-form-item>
      <p v-if="isKey && c.selectionMode !== 'MANUAL'" class="stage-note">
        每次入队按基准叶子 CN
        精确匹配，选择含私钥、已启用且整链有效的自有证书中到期最晚的一条；无需等待旧证书过期。当前：{{
          ownChains[0] ? certificateLabel(ownChains[0]) : '暂无同 CN 可用证书链'
        }}。基准证书后续过期不影响匹配；没有可用链时任务失败。
      </p>
      <el-form-item v-if="isKey" label="别名（可选）">
        <el-input v-model="c.alias" placeholder="留空使用证书 CN 原值，不附加指纹或其他编码" />
      </el-form-item>
      <template v-else>
        <div class="business-grid">
          <el-form-item label="客户" required
            ><el-select v-model="c.customerId" filterable @change="resetChain"
              ><el-option
                v-for="v in customers"
                :key="v.id"
                :value="v.id"
                :label="v.name" /></el-select
          ></el-form-item>
          <el-form-item label="证书用途" required
            ><el-select v-model="c.purpose" @change="resetChain"
              ><el-option label="AS2" value="AS2" /><el-option
                label="HTTPS"
                value="HTTPS" /><el-option label="通用" value="GENERAL" /></el-select
          ></el-form-item>
        </div>
        <el-form-item label="要导入的证书链"
          ><el-radio-group v-model="c.selectionMode" @change="changeMode"
            ><el-radio value="AUTO">自动选择到期最晚的可用链</el-radio
            ><el-radio value="MANUAL">指定证书链</el-radio></el-radio-group
          ></el-form-item
        >
        <el-form-item v-if="c.selectionMode === 'MANUAL'" label="指定证书链" required
          ><el-select v-model="c.certificateId" filterable @change="chooseChain"
            ><el-option
              v-for="cert in chains"
              :key="cert.id"
              :value="cert.id"
              :label="`${certificateLabel(cert)} · 到期 ${cert.notAfter?.slice(0, 10)}`" /></el-select
        ></el-form-item>
        <p v-else class="stage-note">
          每次入队时重新选择并冻结最新可用链。当前：{{
            chains[0] ? certificateLabel(chains[0]) : '该客户用途下暂无可用证书链'
          }}
        </p>
      </template>
      <el-alert v-if="chainError" :title="chainError" type="error" :closable="false" />
      <el-table v-loading="loading" :data="nodes" empty-text="选择证书后展示完整链">
        <el-table-column v-if="!isKey && !isTn" label="导入" width="70"
          ><template #default="{ row }"
            ><el-checkbox
              :model-value="!c.materialIds || c.materialIds.includes(row.materialId)"
              @change="toggleNode(row.materialId, Boolean($event))" /></template
        ></el-table-column>
        <el-table-column prop="name" label="证书 CN" /><el-table-column
          prop="kind"
          label="层级"
          width="130"
        /><el-table-column prop="serial" label="序列号" show-overflow-tooltip /><el-table-column
          label="到期时间"
          width="120"
          ><template #default="{ row }">{{ row.notAfter?.slice(0, 10) }}</template></el-table-column
        >
      </el-table>
      <p v-if="!isTn && !isKey" class="stage-note">
        默认全选；部分勾选会固定当前证书链。执行时别名自动采用 CN + SHA-256 指纹。
      </p>
      <p v-if="isTn" class="stage-note">
        客户标识 id：{{ customer?.externalId || '未配置' }} · idType：{{
          customer?.idType || '未配置'
        }}。提交完整链，按根到终端排序。
      </p>
    </section>

    <section class="business-stage">
      <h3>
        <span>{{ isTn || isKey ? 2 : 3 }}</span> {{ isTn ? '更新证书' : '写入服务器并重载' }}
      </h3>
      <el-form-item label="HTTP 目标服务器" required
        ><el-select v-model="c.serverId" filterable
          ><el-option
            v-for="server in servers.filter((v) => v.enabled && v.type === 'HTTP')"
            :key="server.id"
            :value="server.id"
            :label="server.alias" /></el-select
      ></el-form-item>
      <el-form-item label="更新 URL" required
        ><el-input v-model="c.path" /><small>{{
          resolvedUrl(c.serverId, c.path)
        }}</small></el-form-item
      >
      <el-form-item v-if="!isTn" label="keyStoreName" required
        ><el-input v-model="c.keyStoreName"
      /></el-form-item>
      <el-form-item v-if="isKey" label="写入文件全路径 path（包含文件名）" required
        ><el-input v-model="c.storePath" placeholder="/opt/wm/is/certificates.jks"
      /></el-form-item>
      <el-descriptions :column="2" border size="small"
        ><el-descriptions-item label="方法">POST</el-descriptions-item
        ><el-descriptions-item label="Content-Type">application/json</el-descriptions-item
        ><el-descriptions-item v-if="!isTn" label="type">{{
          isKey ? 'key' : 'trust'
        }}</el-descriptions-item
        ><el-descriptions-item label="成功条件"
          >HTTP 2xx 且 code = 0</el-descriptions-item
        ></el-descriptions
      >
      <p class="stage-note">
        {{
          isTn
            ? '自动传入 id、idType、certChain（index、certificateBase64）。'
            : isKey
              ? '自动传入此处文件全路径、第一步生成内容的 Base64、keyStoreName 和 type=key。'
              : '自动传入第一步 path、第二步生成内容的 Base64、keyStoreName 和 type=trust。'
        }}
      </p>
    </section>
  </div>
</template>

<style scoped>
.business-flow {
  display: grid;
  gap: 18px;
}
.business-stage {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 20px;
}
h3 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 20px;
  font-size: 16px;
}
h3 span {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 13px;
}
.business-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.stage-note,
small,
.parameter-note {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.parameter-note {
  width: 100%;
  margin-bottom: 8px;
}
@media (max-width: 640px) {
  .business-grid {
    grid-template-columns: 1fr;
  }
}
</style>
