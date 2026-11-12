<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue';
import { request } from '../../api/client';
import { run } from '../../composables/useAsyncAction';
import { lowerRows, date } from '../../utils/format';
import { ElMessageBox, ElMessage } from 'element-plus';
const rows = ref<any[]>([]),
  open = ref(false),
  form = reactive({ username: '', password: '' });
async function load() {
  rows.value = lowerRows(await request('/users'));
}
onMounted(() => run(load));
function add() {
  run(async () => {
    await request('/users', 'POST', form);
    open.value = false;
    form.username = '';
    form.password = '';
    await load();
  });
}
function toggle(row: any) {
  run(async () => {
    await ElMessageBox.confirm(
      `确定${row.enabled ? '停用' : '启用'} ${row.username}？`,
      '用户状态',
    );
    await request('/users/' + row.id, 'PATCH', { enabled: !row.enabled });
    await load();
  });
}
function reset(row: any) {
  run(async () => {
    const p = await ElMessageBox.prompt('输入新密码，至少6位', '重置 ' + row.username + ' 的密码', {
      inputType: 'password',
    });
    await request('/users/' + row.id + '/reset-password', 'POST', { password: p.value });
    ElMessage.success('密码已重置');
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>用户管理</h1>
      <span class="sub">当前所有用户均为管理员</span>
    </div>
    <el-button type="primary" @click="open = true">新增用户</el-button>
  </div>
  <div class="panel">
    <el-table :data="rows"
      ><el-table-column prop="username" label="用户名" /><el-table-column label="权限"
        ><template #default>管理员</template></el-table-column
      ><el-table-column label="状态"
        ><template #default="{ row }"
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '启用' : '停用'
          }}</el-tag></template
        ></el-table-column
      ><el-table-column label="创建时间"
        ><template #default="{ row }">{{ date(row.created_at) }}</template></el-table-column
      ><el-table-column label="操作" width="180"
        ><template #default="{ row }"
          ><el-button link type="primary" @click="toggle(row)">{{
            row.enabled ? '停用' : '启用'
          }}</el-button
          ><el-button link type="primary" @click="reset(row)">重置密码</el-button></template
        ></el-table-column
      ></el-table
    >
  </div>
  <el-dialog v-model="open" title="新增用户" width="450px"
    ><el-form label-position="top"
      ><el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item
      ><el-form-item label="初始密码（至少6位）"
        ><el-input v-model="form.password" type="password" show-password /></el-form-item></el-form
    ><template #footer><el-button type="primary" @click="add">保存</el-button></template></el-dialog
  >
</template>
