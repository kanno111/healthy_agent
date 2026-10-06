import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import AdminHomeView from './views/AdminHomeView.vue'
import AdminLoginView from './views/AdminLoginView.vue'
import KnowledgeManagementView from './views/KnowledgeManagementView.vue'
import { session } from './stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true, role: 'PATIENT' } },
    { path: '/admin/login', component: AdminLoginView, meta: { public: true, role: 'STAFF' } },
    { path: '/', component: HomeView, meta: { role: 'PATIENT' } },
    { path: '/admin', component: AdminHomeView, meta: { role: 'STAFF' } },
    { path: '/admin/knowledge', component: KnowledgeManagementView, meta: { role: 'STAFF' } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  const requiredRole = to.meta.role as 'PATIENT' | 'STAFF' | undefined
  const home = session.role === 'STAFF' ? '/admin' : '/'

  if (to.meta.public) {
    return session.loggedIn ? home : true
  }
  if (!session.loggedIn) {
    return requiredRole === 'STAFF' ? '/admin/login' : '/login'
  }
  return requiredRole && session.role !== requiredRole ? home : true
})

export default router
