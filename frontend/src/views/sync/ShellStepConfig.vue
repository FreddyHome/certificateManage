<script setup lang="ts">
import type { ShellStep, SyncServer } from '../../services/sync-types';

defineProps<{ s: ShellStep; servers: SyncServer[] }>();
</script>

<template>
  <el-form-item label="SSH 服务器">
    <el-select
      v-model="s.config.serverId"
      filterable
      placeholder="选择已在服务器配置中新增的 SSH 服务器"
    >
      <el-option
        v-for="server in servers.filter((server) => server.type === 'SSH' && server.enabled)"
        :key="server.id"
        :value="server.id"
        :label="server.alias"
      />
    </el-select>
  </el-form-item>
  <el-form-item label="Shell 命令">
    <el-input
      v-model="s.config.command"
      type="textarea"
      :rows="7"
      :maxlength="100000"
      spellcheck="false"
      placeholder="填写固定命令或多行脚本，例如：systemctl reload nginx"
    />
  </el-form-item>
  <el-form-item label="执行超时（秒）">
    <el-input-number v-model="s.config.timeoutSeconds" :min="1" :max="300" :precision="0" />
  </el-form-item>
  <el-alert
    title="使用所选服务器的登录用户执行。以最终退出码 0 判定成功；多行脚本需要遇错即停时，请自行设置 set -e。"
    type="info"
    :closable="false"
  />
  <p class="muted">
    此步骤使用固定参数，无文件输入。命令和输出可在执行日志详情中查看；超时关闭连接后，请核查远端命令状态。
  </p>
</template>
