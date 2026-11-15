<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import {
  listArchivedCertificates,
  restoreCertificate,
  deleteArchivedCertificate,
  download,
  type CertificateOwner,
  type CertificateRow,
} from '../../services/certificates';
import { run } from '../../composables/useAsyncAction';
import { date } from '../../utils/format';
import { filteredCertificates } from './certificates';
import DetailDialog from './components/DetailDialog.vue';
const rows = ref<CertificateRow[]>([]);
const owner = ref<CertificateOwner | ''>('');
const query = ref('');
const loading = ref(false);
const busy = ref(new Set<number>());
const page = ref(1);
const detailId = ref<number>();
const filtered = computed(() => filteredCertificates(rows.value, query.value, ''));
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20));
let loadVersion = 0;
async function load() {
  const version = ++loadVersion;
  loading.value = true;
  try {
    const result = await listArchivedCertificates(owner.value || undefined);
    if (version === loadVersion) rows.value = result;
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}
watch(
  owner,
  () => {
    page.value = 1;
    run(load);
  },
  { immediate: true },
);
watch(query, () => (page.value = 1));
async function act(row: CertificateRow, action: 'restore' | 'delete') {
  if (busy.value.has(row.id)) return;
  busy.value.add(row.id);
  try {
    try {
      await ElMessageBox.confirm(
        action === 'restore'
          ? `确定恢复证书“${row.name}”？恢复后保留归档前的启用状态。`
          : `确定删除已归档证书“${row.name}”？被其他证书或业务引用时可能无法删除。`,
        action === 'restore' ? '恢复确认' : '删除确认',
        {
          type: 'warning',
          confirmButtonText: action === 'restore' ? '恢复' : '删除',
          cancelButtonText: '取消',
        },
      );
    } catch {
      return;
    }
    await run(async () => {
      if (action === 'restore') await restoreCertificate(row.id);
      else await deleteArchivedCertificate(row.id);
      await load();
      page.value = Math.min(page.value, Math.max(1, Math.ceil(filtered.value.length / 20)));
    });
  } finally {
    busy.value.delete(row.id);
  }
}
async function exported(row: CertificateRow, format: string) {
  if (busy.value.has(row.id)) return;
  busy.value.add(row.id);
  try {
    let password = '';
    if (format === 'P12') {
      try {
        password = (
          await ElMessageBox.prompt('请输入至少8位导出密码', '导出含私钥证书库', {
            inputType: 'password',
          })
        ).value;
      } catch {
        return;
      }
    }
    await run(() => download(row.id, format, password));
  } finally {
    busy.value.delete(row.id);
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>归档证书</h1>
      <span class="sub">归档证书停止同步、签发和通知，可查看、导出或恢复</span>
    </div>
  </div>
  <div class="panel toolbar">
    <el-input v-model="query" clearable placeholder="搜索CN / 客户" />
    <el-select v-model="owner" clearable placeholder="证书归属"
      ><el-option label="自有证书" value="OWN" /><el-option label="客户证书" value="CUSTOMER"
    /></el-select>
    <el-button :loading="loading" @click="run(load)">刷新</el-button>
  </div>
  <section class="panel">
    <el-table :data="visible" v-loading="loading" empty-text="暂无归档证书">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column label="归属" width="110"
        ><template #default="{ row }">{{
          row.owner === 'OWN' ? '自有证书' : '客户证书'
        }}</template></el-table-column
      >
      <el-table-column prop="customer" label="客户" min-width="100" />
      <el-table-column prop="serial" label="证书序列号" min-width="220" show-overflow-tooltip />
      <el-table-column label="用途" width="90"
        ><template #default="{ row }">{{
          row.purpose === 'GENERAL' ? '通用' : row.purpose
        }}</template></el-table-column
      >
      <el-table-column label="到期时间" min-width="170"
        ><template #default="{ row }">{{ date(row.notAfter) }}</template></el-table-column
      >
      <el-table-column label="归档时间" min-width="170"
        ><template #default="{ row }">{{ date(row.archivedAt) }}</template></el-table-column
      >
      <el-table-column label="操作" width="250"
        ><template #default="{ row }">
          <el-button link type="primary" @click="detailId = row.id">查看</el-button>
          <el-dropdown
            :disabled="busy.has(row.id)"
            @command="(format: string) => exported(row, format)"
            ><el-button link type="primary" :disabled="busy.has(row.id)">导出 ▾</el-button
            ><template #dropdown
              ><el-dropdown-menu>
                <el-dropdown-item command="PEM">证书链 PEM</el-dropdown-item
                ><el-dropdown-item command="DER">证书 DER</el-dropdown-item
                ><el-dropdown-item command="P7B">证书库 P7B</el-dropdown-item
                ><el-dropdown-item v-if="row.hasKey" command="P12">含私钥 P12</el-dropdown-item>
              </el-dropdown-menu></template
            ></el-dropdown
          >
          <el-button link type="primary" :loading="busy.has(row.id)" @click="act(row, 'restore')"
            >恢复</el-button
          >
          <el-button link type="danger" :disabled="busy.has(row.id)" @click="act(row, 'delete')"
            >删除</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <el-pagination
      class="pagination"
      v-model:current-page="page"
      :page-size="20"
      :total="filtered.length"
      layout="prev,pager,next,total"
    />
  </section>
  <DetailDialog :id="detailId" @closed="detailId = undefined" />
</template>
