<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentPatient } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import { session, signOut } from '../stores/session'

const router = useRouter()
const checking = ref(true)
const loggingOut = ref(false)

onMounted(async () => {
  try {
    const verified = await getCurrentPatient(session.token)
    if (verified.role !== 'PATIENT' || verified.userId !== session.userId) throw new Error('role mismatch')
  } catch {
    signOut()
    await router.replace('/login')
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
    await router.replace('/login')
    loggingOut.value = false
  }
}
</script>

<template>
  <main class="home-page">
    <header class="topbar">
      <div class="brand dark">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent</span>
      </div>
      <button class="logout-button" :disabled="loggingOut" @click="logout">
        {{ loggingOut ? '正在退出…' : '退出登录' }}
      </button>
    </header>

    <section class="welcome-shell">
      <div class="status-pill"><i></i>{{ checking ? '正在确认登录状态' : '患者身份已验证' }}</div>
      <p class="eyebrow mint">YOUR HEALTH ASSISTANT</p>
      <h1>你好，{{ session.name }}</h1>
      <p class="welcome-copy">
        登录功能已经就绪。下一阶段将在这里接入医疗知识问答、医生号源查询和预约操作。
      </p>

      <div class="identity-card">
        <div>
          <span>当前身份</span>
          <strong>患者</strong>
        </div>
        <div>
          <span>用户 ID</span>
          <strong>{{ session.userId }}</strong>
        </div>
        <div>
          <span>认证链路</span>
          <strong>Gateway · JWT · Redis</strong>
        </div>
      </div>

      <div class="coming-next">
        <span>下一步</span>
        <p>基础对话接口与流式回答</p>
      </div>
    </section>
  </main>
</template>
