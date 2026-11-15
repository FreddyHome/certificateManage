<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { request } from '../../api/client';
import {
  download,
  setCertificateEnabled,
  archiveCertificate,
  type CertificateRow,
} from '../../services/certificates';
import { run } from '../../composables/useAsyncAction';
import { date } from '../../utils/format';
import { filteredCertificates } from './certificates';
import ImportDialog from './components/ImportDialog.vue';
import IssueDialog from './components/IssueDialog.vue';
import DetailDialog from './components/DetailDialog.vue';
import { ElMessageBox } from 'element-plus';
const props = defineProps<{ owner: string }>();
const rows = ref<CertificateRow[]>([]),
  query = ref(''),
  purpose = ref(''),
  status = ref(''),
  loading = ref(false),
  showImport = ref(false),
  showIssue = ref(false),
  issueKind = ref('ROOT'),
  detailId = ref<number>();
const changingIds = ref(new Set<number>());
async function changeEnabled(row: { id: number; enabled?: boolean }, enabled: boolean) {
  if (changingIds.value.has(row.id)) return;
  changingIds.value.add(row.id);
  try {
    await run(async () => {
      await setCertificateEnabled(row.id, enabled);
      row.enabled = enabled;
      await load();
    });
  } finally {
    changingIds.value.delete(row.id);
  }
}
const labels: Record<string, string> = {
  ROOT: '根证书',
  INTERMEDIATE: '中间证书',
  LEAF: '叶子证书',
};
const filtered = computed(() =>
  filteredCertificates(rows.value, query.value, purpose.value).filter(
    (r) => !status.value || r.status === status.value,
  ),
);
const page = ref(1);
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20));
async function archive(row: CertificateRow) {
  if (changingIds.value.has(row.id)) return;
  changingIds.value.add(row.id);
  try {
    try {
      await ElMessageBox.confirm(
        `确定归档证书“${row.name}”？归档后将停止同步、签发和通知，可在归档证书中查看、导出或恢复。`,
        '归档确认',
        { type: 'warning', confirmButtonText: '归档', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
    await run(async () => {
      await archiveCertificate(row.id);
      await load();
      page.value = Math.min(page.value, Math.max(1, Math.ceil(filtered.value.length / 20)));
    });
  } finally {
    changingIds.value.delete(row.id);
  }
}
async function load() {
  loading.value = true;
  try {
    rows.value = await request(`/certificates?owner=${props.owner}`);
  } finally {
    loading.value = false;
  }
}
watch(
  () => props.owner,
  () => run(load),
  { immediate: true },
);
watch([query, purpose, status], () => (page.value = 1));
function create(kind: string) {
  issueKind.value = kind;
  showIssue.value = true;
}
async function expand(row: CertificateRow, expanded: CertificateRow[]) {
  if (expanded.includes(row) && !row.chain)
    row.chain = await request(`/certificates/${row.id}/chain`);
}
function exported(row: CertificateRow, format: string) {
  run(async () => {
    let pw = '';
    if (format === 'P12')
      pw = (
        await ElMessageBox.prompt('请输入至少8位导出密码', '导出含私钥证书库', {
          inputType: 'password',
        })
      ).value;
    await download(row.id, format, pw);
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ owner === 'OWN' ? '自有证书' : '客户证书' }}</h1>
      <span class="sub"
        >{{
          owner === 'OWN' ? '根 → 中间 → 叶子，分层管理' : '终端证书独立并列，点击名称展开证书链'
        }}
        · 按到期时间升序</span
      >
    </div>
    <el-button type="primary" @click="showImport = true">导入证书</el-button>
  </div>
  <div class="panel toolbar">
    <el-input v-model="query" clearable placeholder="搜索CN / 客户" /><el-select
      v-model="purpose"
      clearable
      placeholder="用途"
      ><el-option label="AS2" value="AS2" /><el-option label="HTTPS" value="HTTPS" /><el-option
        label="通用"
        value="GENERAL" /></el-select
    ><el-select v-model="status" clearable placeholder="有效期状态"
      ><el-option
        v-for="s in ['未生效', '有效', '即将过期', '已过期']"
        :key="s"
        :label="s"
        :value="s" /></el-select
    ><el-button @click="run(load)">刷新</el-button>
  </div>
  <template v-if="owner === 'OWN'"
    ><section v-for="kind in ['ROOT', 'INTERMEDIATE', 'LEAF']" :key="kind" class="panel">
      <div class="panel-heading">
        <h2>{{ labels[kind] }}</h2>
        <el-button type="primary" plain @click="create(kind)">创建{{ labels[kind] }}</el-button>
      </div>
      <el-table
        :data="filtered.filter((r) => r.kind === kind)"
        v-loading="loading"
        empty-text="暂无证书"
        ><el-table-column prop="name" label="名称" min-width="180" /><el-table-column
          prop="serial"
          label="证书序列号"
          min-width="220"
          show-overflow-tooltip
        /><el-table-column label="用途" width="90"
          ><template #default="{ row }"
            ><el-tag>{{ row.purpose === 'GENERAL' ? '通用' : row.purpose }}</el-tag></template
          ></el-table-column
        ><el-table-column prop="issuer" label="签发者" min-width="150" /><el-table-column
          label="到期时间"
          min-width="170"
          ><template #default="{ row }">{{ date(row.notAfter) }}</template></el-table-column
        ><el-table-column label="状态" width="105"
          ><template #default="{ row }"
            ><el-tag
              :type="
                row.status === '已过期'
                  ? 'danger'
                  : row.status === '即将过期'
                    ? 'warning'
                    : 'success'
              "
              >{{ row.status }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column label="启用状态" width="140">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled !== false"
              :loading="changingIds.has(row.id)"
              active-text="启用"
              inactive-text="禁用"
              inline-prompt
              @change="(value: boolean | string | number) => changeEnabled(row, Boolean(value))"
            />
          </template> </el-table-column
        ><el-table-column label="操作" width="220"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="detailId = row.id">查看</el-button
            ><el-dropdown @command="(f: string) => exported(row, f)"
              ><el-button link type="primary">导出 ▾</el-button
              ><template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item command="PEM">证书链 PEM</el-dropdown-item
                  ><el-dropdown-item command="DER">证书 DER</el-dropdown-item
                  ><el-dropdown-item command="P7B">证书库 P7B</el-dropdown-item
                  ><el-dropdown-item v-if="row.hasKey" command="P12"
                    >含私钥 P12</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              ></el-dropdown
            ><el-button link type="danger" :loading="changingIds.has(row.id)" @click="archive(row)"
              >归档</el-button
            ></template
          ></el-table-column
        ></el-table
      >
    </section></template
  >
  <section v-else class="panel">
    <el-table
      ref="clientTable"
      :data="visible"
      row-key="id"
      v-loading="loading"
      @expand-change="
        (row: CertificateRow, expanded: CertificateRow[]) => run(() => expand(row, expanded))
      "
      ><el-table-column type="expand" width="1"
        ><template #default="{ row }"
          ><div style="padding: 16px 36px">
            <el-table :data="row.chain?.slice().reverse()" empty-text="加载证书链…"
              ><el-table-column prop="name" label="名称" /><el-table-column
                prop="serial"
                label="证书序列号"
                min-width="220"
                show-overflow-tooltip
              /><el-table-column prop="depth" label="层数" width="80" /><el-table-column
                label="类型"
                width="100"
                ><template #default="{ row: node }">{{
                  labels[node.kind]
                }}</template></el-table-column
              ><el-table-column label="到期时间"
                ><template #default="{ row: node }">{{
                  date(node.notAfter)
                }}</template></el-table-column
              ></el-table
            >
          </div></template
        ></el-table-column
      ><el-table-column label="证书名称" min-width="180"
        ><template #default="{ row }"
          ><el-button
            link
            type="primary"
            @click="($refs.clientTable as any)?.toggleRowExpansion(row)"
            >{{ row.name }}</el-button
          ></template
        ></el-table-column
      ><el-table-column
        prop="serial"
        label="证书序列号"
        min-width="220"
        show-overflow-tooltip
      /><el-table-column prop="customer" label="客户" min-width="100" /><el-table-column
        label="用途"
        width="90"
        ><template #default="{ row }">{{
          row.purpose === 'GENERAL' ? '通用' : row.purpose
        }}</template></el-table-column
      ><el-table-column prop="depth" label="层数" width="70" /><el-table-column
        label="到期时间"
        min-width="170"
        ><template #default="{ row }">{{ date(row.notAfter) }}</template></el-table-column
      ><el-table-column prop="status" label="状态" width="100" /><el-table-column
        label="启用状态"
        width="140"
      >
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled !== false"
            :loading="changingIds.has(row.id)"
            active-text="启用"
            inactive-text="禁用"
            inline-prompt
            @change="(value: boolean | string | number) => changeEnabled(row, Boolean(value))"
          />
        </template> </el-table-column
      ><el-table-column label="操作" width="190"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="detailId = row.id">查看</el-button
          ><el-button link type="primary" @click="exported(row, 'PEM')">导出</el-button>
          <el-button link type="warning" :loading="changingIds.has(row.id)" @click="archive(row)"
            >归档</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-pagination
      class="pagination"
      v-model:current-page="page"
      :page-size="20"
      :total="filtered.length"
      layout="prev,pager,next,total"
    />
  </section>
  <ImportDialog v-model="showImport" :owner="owner" @saved="run(load)" /><IssueDialog
    v-model="showIssue"
    :kind="issueKind"
    @saved="run(load)"
  /><DetailDialog :id="detailId" @closed="detailId = undefined" />
</template>
