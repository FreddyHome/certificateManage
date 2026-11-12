import { createRouter,createWebHashHistory } from 'vue-router';
import { request } from '../api/client';
const router=createRouter({history:createWebHashHistory(),routes:[
 {path:'/login',component:()=>import('../views/auth/LoginView.vue')},
 {path:'/',component:()=>import('../layouts/AdminLayout.vue'),children:[
 {path:'',redirect:'/certificates/own'},
 {path:'certificates/own',component:()=>import('../views/certificates/CertificateView.vue'),props:{owner:'OWN'}},
 {path:'certificates/customer',component:()=>import('../views/certificates/CertificateView.vue'),props:{owner:'CUSTOMER'}},
 {path:'servers',component:()=>import('../views/servers/ServerView.vue')},
 {path:'sync',component:()=>import('../views/sync/SyncView.vue')},
 {path:'customers',component:()=>import('../views/customers/CustomerView.vue')},
 {path:'system/audit',component:()=>import('../views/system/AuditView.vue')},
 {path:'system/users',component:()=>import('../views/system/UserView.vue')},
 {path:'system/smtp',component:()=>import('../views/system/SmtpView.vue')},
 {path:'system/notifications',component:()=>import('../views/system/NotificationView.vue')}
 ]}
]});
router.beforeEach(async to=>{if(to.path==='/login')return;try{await request('/auth/me')}catch{return '/login'}});
export default router;

