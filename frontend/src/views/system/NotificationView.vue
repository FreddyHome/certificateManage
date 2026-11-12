<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { request } from '../../api/client';
import { run } from '../../composables/useAsyncAction';
import { lowerRows, date } from '../../utils/format';
import { ElMessage } from 'element-plus';
const form = reactive({
  enabled: false,
  timezone: 'Asia/Shanghai',
  scanTime: '09:00:00',
  warningDays: 30,
  includeCa: false,
  thresholds: [30, 14, 7, 1],
  recipients: [] as string[],
});
const thresholdText = ref('30,14,7,1'),
  recipientText = ref(''),
  records = ref<any[]>([]);
async function load() {
  Object.assign(form, await request('/system/notifications'));
  thresholdText.value = form.thresholds.join(',');
  recipientText.value = form.recipients.join(',');
  await logs();
}
async function logs() {
  records.value = lowerRows(await request('/notification-deliveries'));
}
onMounted(() => run(load));
function save() {
  run(async () => {
    const thresholds = thresholdText.value
      .split(/[,，\s]+/)
      .filter(Boolean)
      .map(Number);
    if (thresholds.some((x) => !Number.isInteger(x))) throw Error('提醒天数必须为整数');
    await request('/system/notifications', 'PUT', {
      ...form,
      thresholds,
      recipients: recipientText.value.split(/[,，\s]+/).filter(Boolean),
    });
    ElMessage.success('通知策略已保存');
  });
}
function scan() {
  run(async () => {
    const r = await request('/system/notifications/scan', 'POST');
    ElMessage.success(`本次新增 ${r.created} 条通知任务（仅处理今日已到检查时间的节点）`);
    await logs();
  });
}
function retry(id: number) {
  run(async () => {
    await request(`/notification-deliveries/${id}/retry`, 'POST');
    await logs();
  });
}
</script>
<template>
  <h1>通知服务</h1>
  <p class="sub">邮件到期提醒，SMTP服务器在独立菜单配置</p>
  <div class="panel">
    <el-form label-position="top"
      ><el-form-item label="启用提醒"><el-switch v-model="form.enabled" /></el-form-item>
      <div class="form-grid">
        <el-form-item label="收件人（逗号分隔）"><el-input v-model="recipientText" /></el-form-item
        ><el-form-item label="提前提醒天数（逗号分隔）"
          ><el-input v-model="thresholdText" /></el-form-item
        ><el-form-item label="每日检查时间"
          ><el-time-picker v-model="form.scanTime" value-format="HH:mm:ss" /></el-form-item
        ><el-form-item label="时区"><el-input v-model="form.timezone" /></el-form-item
        ><el-form-item label="即将过期状态阈值（天）"
          ><el-input-number v-model="form.warningDays" :min="0" :max="3650" /></el-form-item
        ><el-form-item label="包含自有CA证书提醒"
          ><el-switch v-model="form.includeCa"
        /></el-form-item>
      </div>
      <el-button type="primary" @click="save">保存规则</el-button
      ><el-button @click="scan">检查今日节点</el-button></el-form
    >
    <p class="sub" style="margin-top: 16px">
      新旧证书分别提醒；不补发历史节点，不发送已过期后的重复提醒。发送失败自动重试最多3次。
    </p>
  </div>
  <div class="panel">
    <div class="panel-heading">
      <h2>发送记录</h2>
      <el-button @click="run(logs)">刷新</el-button>
    </div>
    <el-table :data="records"
      ><el-table-column prop="cn_snapshot" label="证书名称" min-width="160" /><el-table-column
        prop="recipient_address"
        label="收件人"
        min-width="160"
      /><el-table-column prop="threshold_days" label="提前天数" width="90" /><el-table-column
        prop="delivery_status"
        label="状态"
        width="120"
      /><el-table-column prop="attempts" label="尝试次数" width="90" /><el-table-column
        label="创建时间"
        min-width="170"
        ><template #default="{ row }">{{ date(row.created_at) }}</template></el-table-column
      ><el-table-column prop="error_message" label="失败原因" min-width="160" /><el-table-column
        label="操作"
        width="80"
        ><template #default="{ row }"
          ><el-button
            v-if="row.delivery_status === 'FAILED'"
            link
            type="primary"
            @click="retry(row.id)"
            >重试</el-button
          ></template
        ></el-table-column
      ></el-table
    >
  </div>
</template>
