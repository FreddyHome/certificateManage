import { ElMessage } from 'element-plus';
let token='';let header='X-CSRF-TOKEN';
export async function csrf(){const response=await fetch('/api/v1/auth/csrf',{credentials:'same-origin'});const data=await response.json();token=data.token;header=data.headerName;}
export async function request<T=any>(path:string,method='GET',body?:any):Promise<T>{
 if(method!=='GET'&&(!token||path==='/auth/login'))await csrf();const headers:Record<string,string>={};if(method!=='GET')headers[header]=token;if(method==='POST'&&(path==='/certificates/issue'||path==='/imports/commit'||/^\/sync-tasks\/\d+\/execute$/.test(path)))headers['X-Idempotency-Key']=Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
 if(body!==undefined&&!(body instanceof FormData))headers['Content-Type']='application/json';
 const response=await fetch('/api/v1'+path,{method,credentials:'same-origin',headers,body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});
 if(!response.ok){let message='请求失败';try{message=(await response.json()).message||message}catch{}if(response.status===401){token='';if(path!='/auth/login'&&typeof location!=='undefined')location.hash='/login';}throw new Error(message)}
 if(path==='/auth/logout')token='';if(response.status===204||response.headers.get('content-length')==='0')return undefined as T;const text=await response.text();return (text?JSON.parse(text):undefined) as T;
}
export function run(fn:()=>Promise<any>){return fn().catch(e=>ElMessage.error(e.message||'操作失败'));}
export function date(value:string){return value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—'}
export function lowerRows(rows:any[]){return rows.map(r=>Object.fromEntries(Object.entries(r).map(([k,v])=>[k.toLowerCase(),v])))}
export async function download(id:number,format:string,password=''){if(!token)await csrf();const r=await fetch(`/api/v1/certificates/${id}/export`,{method:'POST',headers:{'Content-Type':'application/json',[header]:token},body:JSON.stringify({format,password})});if(!r.ok){let e=await r.json();throw new Error(e.message||'导出失败')}const url=URL.createObjectURL(await r.blob());const a=document.createElement('a');a.href=url;a.download=`certificate-${id}.${format.toLowerCase()}`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}



