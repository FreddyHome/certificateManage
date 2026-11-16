<script setup lang="ts">
import { ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import {
  listArchivedCertificatePage,
  restoreCertificate,
  deleteArchivedCertificate,
  download,
  type CertificateOwner,
  type CertificateRow,
} from '../../services/certificates';
import { run } from '../../composables/useAsyncAction';
import { invalidateCertificateChains } from '../../services/certificate-chain';
import { date } from '../../utils/format';
import { useCertificatePagination } from './pagination';
import DetailDialog from './components/DetailDialog.vue';
const owner = ref<CertificateOwner | ''>('');
const query = ref('');
const busy = ref(new Set<number>());
const detailId = ref<number>();
const { tables, load, changePage } = useCertificatePagination(
  listArchivedCertificatePage,
  () => ({ owner: owner.value, query: query.value }),
  () => false,
  run,
);
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
function refresh() {
  invalidateCertificateChains();
  run(load);
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
    <el-input v-model="query" clearable maxlength="200" placeholder="搜索CN / 客户" />
    <el-select v-model="owner" clearable placeholder="证书归属"
      ><el-option label="自有证书" value="OWN" /><el-option label="客户证书" value="CUSTOMER"
    /></el-select>
    <el-button :loading="tables.main.loading" @click="refresh">刷新</el-button>
  </div>
  <section class="panel">
    <el-table :data="tables.main.items" v-loading="tables.main.loading" empty-text="暂无归档证书">
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
      :current-page="tables.main.page"
      :page-size="20"
      :total="tables.main.total"
      layout="prev,pager,next,total"
      @update:current-page="(page: number) => run(() => changePage('main', page))"
    />
  </section>
  <DetailDialog :id="detailId" @closed="detailId = undefined" />
</template>
