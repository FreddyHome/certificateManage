<script setup lang="ts">
import { previewCertificateImport, commitCertificateImport } from '../../../services/certificates';
import { ref, reactive, watch } from 'vue';
import { request } from '../../../api/client';
import { run } from '../../../composables/useAsyncAction';
import { ElMessage } from 'element-plus';
const model = defineModel<boolean>();
const props = defineProps<{ owner: string }>();
const emit = defineEmits(['saved']);
const mode = ref('separate'),
  files = ref<(File | null)[]>([null, null, null]),
  keyFile = ref<File | null>(null),
  password = ref(''),
  purpose = ref('GENERAL'),
  customer = ref(''),
  customers = ref<any[]>([]),
  preview = ref<any>(),
  busy = ref(false);
watch(model, async (value) => {
  if (value) {
    preview.value = undefined;
    files.value = mode.value === 'separate' ? [null, null, null] : [null];
    keyFile.value = null;
    password.value = '';
    if (props.owner === 'CUSTOMER') customers.value = await request('/customers');
  }
});
watch(mode, () => {
  files.value = mode.value === 'separate' ? [null, null, null] : [null];
  keyFile.value = null;
  preview.value = undefined;
});
function changed(e: Event, index: number) {
  files.value[index] = (e.target as HTMLInputElement).files?.[0] || null;
  preview.value = undefined;
}
function data() {
  if (files.value.length === 0 || files.value.some((f) => !f)) throw Error('请补齐证书文件');
  if (props.owner === 'OWN' && mode.value !== 'pkcs12' && !keyFile.value)
    throw Error('请上传终端证书私钥');
  if (props.owner === 'CUSTOMER' && !customer.value.trim()) throw Error('请选择或填写客户');
  const d = new FormData();
  d.append('owner', props.owner);
  d.append('mode', mode.value);
  d.append('purpose', purpose.value);
  d.append('customer', customer.value);
  d.append('password', password.value);
  files.value.forEach((f) => d.append('files', f!));
  if (keyFile.value) d.append('key', keyFile.value);
  return d;
}
function submit(save: boolean) {
  run(async () => {
    busy.value = true;
    try {
      preview.value = await (save
        ? commitCertificateImport(data())
        : previewCertificateImport(data()));
      if (save) {
        ElMessage.success('证书导入成功');
        model.value = false;
        emit('saved');
      }
    } finally {
      busy.value = false;
    }
  });
}
</script>
<template>
  <el-dialog
    v-model="model"
    :title="owner === 'OWN' ? '导入自有证书' : '导入客户证书'"
    width="720px"
    destroy-on-close
    ><el-alert
      title="名称由证书 CN 自动解析；提交材料需组成完整证书链。"
      type="info"
      :closable="false"
    /><el-radio-group v-model="mode" style="margin: 20px 0"
      ><el-radio-button value="separate">CER / DER / CRT / PEM</el-radio-button
      ><el-radio-button value="pkcs7">P7B / P7C</el-radio-button
      ><el-radio-button v-if="owner === 'OWN'" value="pkcs12"
        >P12 / PFX</el-radio-button
      ></el-radio-group
    ><el-form label-position="top"
      ><div class="form-grid">
        <el-form-item label="用途"
          ><el-select v-model="purpose"
            ><el-option label="AS2" value="AS2" /><el-option
              label="HTTPS"
              value="HTTPS" /><el-option label="通用" value="GENERAL" /></el-select></el-form-item
        ><el-form-item v-if="owner === 'CUSTOMER'" label="所属客户（可选择或直接输入）"
          ><el-select
            v-model="customer"
            filterable
            allow-create
            default-first-option
            placeholder="选择已有客户或输入新名称"
            ><el-option
              v-for="c in customers"
              :key="c.id"
              :label="c.name"
              :value="c.name" /></el-select
        ></el-form-item>
      </div>
      <div v-for="(_, i) in files" :key="`${mode}-${i}`" class="file-row">
        <span>{{ mode === 'separate' ? '证书文件 ' + (i + 1) : '证书库' }}</span><input
          type="file"
          :accept="
            mode === 'pkcs12' ? '.p12,.pfx' : mode === 'pkcs7' ? '.p7b,.p7c' : '.cer,.der,.crt,.pem'
          "
          @change="changed($event, i)"
        /><el-button
          v-if="mode === 'separate'"
          text
          type="danger"
          @click="
            files.splice(i, 1);
            preview = undefined;
          "
          >移除</el-button
        >
      </div>
      <el-button
        v-if="mode === 'separate'"
        @click="
          files.push(null);
          preview = undefined;
        "
        >＋ 新增一行</el-button
      ><el-form-item
        v-if="owner === 'OWN' && mode !== 'pkcs12'"
        label="终端证书私钥"
        style="margin-top: 20px"
        ><input
          type="file"
          accept=".pem,.key,.der"
          @change="
            keyFile = ($event.target as HTMLInputElement).files?.[0] || null;
            preview = undefined;
          " /></el-form-item
      ><el-form-item
        v-if="owner === 'OWN'"
        :label="mode === 'pkcs12' ? '证书库密码（如有）' : '私钥密码（如有）'"
        ><el-input
          v-model="password"
          type="password"
          show-password
          @input="preview = undefined" /></el-form-item
    ></el-form>
    <div v-if="preview?.chain" class="result">
      验证通过：{{ preview.chain.join(' → ') }}
      <p v-if="owner === 'OWN'">私钥匹配：{{ preview.keyMatched ? '是' : '否' }}</p>
    </div>
    <template #footer
      ><el-button @click="model = false">取消</el-button
      ><el-button :loading="busy" @click="submit(false)">校验预览</el-button
      ><el-button type="primary" :loading="busy" @click="submit(true)">保存</el-button></template
    ></el-dialog
  >
</template>
