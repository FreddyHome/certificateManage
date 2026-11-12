<script setup lang="ts">
import {ref,watch} from 'vue';
const props=defineProps<{modelValue:Record<string,any>}>();const emit=defineEmits(['update:modelValue']);const rows=ref<{key:string,value:string}[]>([]);
watch(()=>props.modelValue,v=>{if(JSON.stringify(v)!==JSON.stringify(Object.fromEntries(rows.value.filter(r=>r.key).map(r=>[r.key,r.value]))))rows.value=Object.entries(v||{}).map(([key,value])=>({key,value:String(value)}))},{immediate:true,deep:true});
function change(){emit('update:modelValue',Object.fromEntries(rows.value.filter(r=>r.key.trim()).map(r=>[r.key.trim(),r.value])))}
</script>
<template><div style="width:100%"><div v-for="(row,i) in rows" :key="i" style="display:flex;gap:8px;margin-bottom:8px"><el-input v-model="row.key" placeholder="参数名称" @change="change"/><el-input v-model="row.value" placeholder="参数值" @change="change"/><el-button text type="danger" @click="rows.splice(i,1);change()">删除</el-button></div><el-button size="small" @click="rows.push({key:'',value:''})">新增参数</el-button></div></template>
