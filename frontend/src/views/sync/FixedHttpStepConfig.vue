<script setup lang="ts">
import type { SyncCustomer, SyncServer, TransferStep } from '../../services/sync-types';

defineProps<{
  s: TransferStep;
  servers: SyncServer[];
  customers: SyncCustomer[];
}>();
</script>

<template>
  <el-form-item label="HTTP 服务器">
    <el-select v-model="s.config.serverId" filterable>
      <el-option
        v-for="server in servers.filter((server) => server.type === 'HTTP' && server.enabled)"
        :key="server.id"
        :value="server.id"
        :label="server.alias"
      />
    </el-select>
  </el-form-item>
  <p>使用所选服务器已配置的认证信息。</p>
  <template v-if="s.config.httpComponent === 'RELOAD_KEYSTORE'">
    <p>GET /wm/is/cert/reload</p>
    <el-form-item label="Keystore 名称（keyStoreName）">
      <el-input v-model="s.config.keyStoreName" />
    </el-form-item>
    <el-form-item label="类型（type）">
      <el-input v-model="s.config.type" />
    </el-form-item>
    <p>查询参数由系统生成。响应 isError 为 false 时成功，为 true 时失败。</p>
  </template>
  <template v-else>
    <p>POST /wm/tn/partner/cert/update</p>
    <el-form-item label="客户">
      <el-select v-model="s.config.customerId" filterable placeholder="选择客户">
        <el-option
          v-for="customer in customers"
          :key="customer.id"
          :value="customer.id"
          :label="customer.name"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="证书用途">
      <el-select v-model="s.config.purpose" placeholder="GENERAL">
        <el-option v-for="purpose in ['AS2', 'HTTPS', 'GENERAL']" :key="purpose" :value="purpose" />
      </el-select>
    </el-form-item>
    <p>
      自动使用客户外部标识，选择该客户及用途下有效且到期最晚的证书，按根证书到终端证书顺序生成请求正文。
    </p>
    <p>响应 code 为 0 时成功，其他值失败。</p>
  </template>
  <p>成功和失败的响应 message 均写入执行日志。</p>
</template>
