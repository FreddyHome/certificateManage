<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { request, csrf } from '../../api/client';
import { run } from '../../composables/useAsyncAction';
const username = ref('admin'),
  password = ref(''),
  loading = ref(false),
  router = useRouter();
function login() {
  run(async () => {
    loading.value = true;
    try {
      await request('/auth/login', 'POST', { username: username.value, password: password.value });
      await csrf();
      router.push('/');
    } finally {
      loading.value = false;
    }
  });
}
</script>
<template>
  <div class="login">
    <el-card class="login-card"
      ><h1>证书管理系统</h1>
      <p class="sub">内部证书资产与到期通知</p>
      <el-form @submit.prevent="login" label-position="top"
        ><el-form-item label="用户名"
          ><el-input v-model="username" autocomplete="username" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password" /></el-form-item
        ><el-button type="primary" native-type="submit" :loading="loading" style="width: 100%"
          >登录</el-button
        ></el-form
      ></el-card
    >
  </div>
</template>
