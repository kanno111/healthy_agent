<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentAdmin } from '../api/agent'
import { login as loginRequest, logout as logoutRequest } from '../api/auth'
import { ApiError } from '../api/http'
import { signIn } from '../stores/session'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (loading.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) {
    error.value = '请输入管理员账号和密码'
    return
  }

  loading.value = true
  let issuedToken = ''
  try {
    const result = await loginRequest(username.value.trim(), password.value)
    issuedToken = result.token
    if (result.role !== 'STAFF') {
      await logoutRequest(result.token).catch(() => undefined)
      issuedToken = ''
      throw new ApiError('该入口仅允许管理员账号登录')
    }

    const verified = await getCurrentAdmin(result.token)
    if (!verified.authenticated || verified.role !== 'STAFF' || verified.userId !== result.userId) {
      throw new ApiError('管理员身份校验失败，请重新登录')
    }

    signIn({ token: result.token, userId: result.userId, name: result.name, role: 'STAFF' })
    await router.replace('/admin')
  } catch (cause) {
    if (issuedToken) await logoutRequest(issuedToken).catch(() => undefined)
    error.value = cause instanceof ApiError ? cause.message : '登录失败，请稍后重试'
  } finally {
    password.value = ''
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page admin-auth">
    <section class="brand-panel admin-brand-panel">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent Console</span>
      </div>
      <div class="brand-copy">
        <p class="eyebrow">KNOWLEDGE OPERATIONS</p>
        <h1>让可信内容，<br />成为回答的依据。</h1>
        <p class="brand-description">统一维护医院制度、常见医疗知识与文档索引，后续支持上传、解析、检索测试和版本管理。</p>
      </div>
      <div class="feature-list" aria-label="管理能力">
        <span><b>01</b> 管理员身份独立隔离</span>
        <span><b>02</b> 文档与索引可追踪</span>
        <span><b>03</b> 患者端仅消费已发布知识</span>
      </div>
    </section>

    <section class="form-panel">
      <div class="login-card">
        <div class="mobile-brand"><span class="brand-mark" aria-hidden="true"><i></i><i></i></span>Healthy Agent Console</div>
        <p class="eyebrow mint">ADMIN CONSOLE</p>
        <h2>管理员登录</h2>
        <p class="form-intro">复用医院预约系统的管理员账号</p>

        <form @submit.prevent="submit" novalidate>
          <label for="admin-username">管理员账号</label>
          <div class="input-wrap">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 21a8 8 0 0 0-16 0M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z" /></svg>
            <input id="admin-username" v-model.trim="username" autocomplete="username" placeholder="请输入管理员账号" :disabled="loading" autofocus />
          </div>
          <label for="admin-password">密码</label>
          <div class="input-wrap">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 10V8a6 6 0 0 1 12 0v2M5 10h14v11H5V10Zm7 5v2" /></svg>
            <input id="admin-password" v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" :disabled="loading" />
          </div>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="submit-button" type="submit" :disabled="loading">
            <span>{{ loading ? '正在验证管理员身份…' : '进入管理控制台' }}</span>
            <svg v-if="!loading" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
          </button>
        </form>

        <div class="security-note">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Zm-3-10 2 2 4-4" /></svg>
          <span>管理接口只接受 Gateway 验证后的 STAFF 身份。</span>
        </div>
        <RouterLink class="role-switch" to="/login">← 返回患者登录</RouterLink>
      </div>
    </section>
  </main>
</template>
