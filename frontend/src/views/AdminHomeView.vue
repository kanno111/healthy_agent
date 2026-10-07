<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentAdmin } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import { session, signOut } from '../stores/session'

const router = useRouter()
const checking = ref(true)
const loggingOut = ref(false)

onMounted(async () => {
  try {
    const verified = await getCurrentAdmin(session.token)
    if (verified.role !== 'STAFF' || verified.userId !== session.userId) throw new Error('role mismatch')
  } catch {
    signOut()
    await router.replace('/admin/login')
  } finally {
    checking.value = false
  }
})

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logoutRequest(session.token)
  } finally {
    signOut()
    await router.replace('/admin/login')
    loggingOut.value = false
  }
}
</script>

<template>
  <main class="admin-page">
    <header class="topbar admin-topbar">
      <div class="brand dark">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent Console</span>
      </div>
      <div class="admin-account">
        <span>{{ session.name }} · 管理员</span>
        <button class="logout-button" :disabled="loggingOut" @click="logout">{{ loggingOut ? '正在退出…' : '退出登录' }}</button>
      </div>
    </header>

    <section class="admin-shell">
      <div class="admin-heading">
        <div>
          <div class="status-pill"><i></i>{{ checking ? '正在确认登录状态' : '管理员身份已验证' }}</div>
          <p class="eyebrow mint">ADMIN CONSOLE</p>
          <h1>知识库管理</h1>
          <p>这里负责维护 Agent 可检索的公共医疗知识和医院制度，不接触患者预约数据库。</p>
        </div>
        <div class="admin-identity">
          <span>当前管理员</span>
          <strong>{{ session.name }}</strong>
          <small>用户 ID：{{ session.userId }}</small>
        </div>
      </div>

      <div class="admin-grid">
        <RouterLink class="admin-card active" to="/admin/knowledge">
          <span class="card-icon">文</span>
          <div><b>文档与知识库</b><p>上传、解析并索引医院知识文档</p></div>
          <em>进入 →</em>
        </RouterLink>
        <RouterLink class="admin-card active" to="/admin/knowledge/search">
          <span class="card-icon">检</span>
          <div><b>向量检索测试</b><p>输入自然语言问题，验证 BGE-M3 与 ES 的 chunk 召回结果</p></div>
          <em>进入 →</em>
        </RouterLink>
        <article class="admin-card disabled">
          <span class="card-icon">任</span>
          <div><b>索引任务</b><p>查看文档处理进度与失败原因</p></div>
          <em>后续开放</em>
        </article>
      </div>

      <div class="scope-note">
        <b>当前版本边界</b>
        <p>已完成文档上传、解析、切分、BGE-M3 向量化、ES 索引和管理员检索测试。本阶段不增加知识发布机制，检索范围为所有已成功构建索引的文档。</p>
      </div>
    </section>
  </main>
</template>
