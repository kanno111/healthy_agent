<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { logout as logoutRequest } from '../api/auth'
import { session, signOut } from '../stores/session'

const router = useRouter()
const loggingOut = ref(false)

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logoutRequest(session.token)
  } finally {
    signOut()
    await router.replace('/admin/login')
  }
}
</script>

<template>
  <main class="admin-page">
    <header class="topbar admin-topbar">
      <RouterLink class="brand dark brand-link" to="/admin">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent Console</span>
      </RouterLink>
      <div class="admin-account">
        <span>{{ session.name }}</span>
        <button class="logout-button" :disabled="loggingOut" @click="logout">退出登录</button>
      </div>
    </header>

    <section class="admin-shell compact">
      <RouterLink class="back-link" to="/admin">← 返回管理首页</RouterLink>
      <div class="page-title">
        <p class="eyebrow mint">KNOWLEDGE BASE</p>
        <h1>文档与知识库</h1>
        <p>管理医院制度和医疗常识文档。患者端只能检索已审核、已发布的内容。</p>
      </div>

      <div class="knowledge-toolbar">
        <div>
          <b>知识文档</b>
          <span>0 个文档</span>
        </div>
        <button type="button" disabled>上传文档（下一阶段）</button>
      </div>

      <div class="empty-state">
        <span class="empty-icon">＋</span>
        <h2>管理入口已经就绪</h2>
        <p>下一阶段会接入文件上传、内容切分、ES 索引、处理状态和发布控制；本版不保存假数据。</p>
      </div>
    </section>
  </main>
</template>
