<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentPatient } from '../api/agent'
import { localDevLogin, login as loginRequest, logout as logoutRequest } from '../api/auth'
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
    error.value = '请输入患者账号和密码'
    return
  }

  loading.value = true
  let issuedToken = ''
  try {
    const result = await loginRequest(username.value.trim(), password.value)
    issuedToken = result.token

    if (result.role !== 'PATIENT') {
      await logoutRequest(result.token).catch(() => undefined)
      issuedToken = ''
      throw new ApiError('当前 Agent 仅支持患者账号登录')
    }

    const verified = await getCurrentPatient(result.token)
    if (!verified.authenticated || verified.role !== 'PATIENT' || verified.userId !== result.userId) {
      throw new ApiError('患者身份校验失败，请重新登录')
    }

    signIn({ token: result.token, userId: result.userId, name: result.name, role: 'PATIENT' })
    await router.replace('/')
  } catch (cause) {
    if (issuedToken) {
      await logoutRequest(issuedToken).catch(() => undefined)
    }
    error.value = cause instanceof ApiError ? cause.message : '登录失败，请稍后重试'
  } finally {
    password.value = ''
    loading.value = false
  }
}

async function submitLocalDev() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await localDevLogin('PATIENT')
    const verified = await getCurrentPatient(result.token)
    if (!verified.authenticated || verified.role !== 'PATIENT' || verified.userId !== result.userId) {
      throw new ApiError('本地患者身份校验失败')
    }
    signIn({ token: result.token, userId: result.userId, name: result.name, role: 'PATIENT' })
    await router.replace('/')
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '本地开发登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="brand-panel">
      <div class="brand">
        <span class="brand-mark" aria-hidden="true">
          <i></i><i></i>
        </span>
        <span>Healthy Agent</span>
      </div>

      <div class="brand-copy">
        <p class="eyebrow">PATIENT AI ASSISTANT</p>
        <h1>让就医服务，<br />从一次自然对话开始。</h1>
        <p class="brand-description">
          查询医院知识、寻找合适医生，并在确认后安全地完成预约操作。
        </p>
      </div>

      <div class="feature-list" aria-label="服务特点">
        <span><b>01</b> 医疗知识有据可查</span>
        <span><b>02</b> 预约操作二次确认</span>
        <span><b>03</b> 患者数据严格隔离</span>
      </div>
    </section>

    <section class="form-panel">
      <div class="login-card">
        <div class="mobile-brand">
          <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
          Healthy Agent
        </div>

        <p class="eyebrow mint">WELCOME BACK</p>
        <h2>患者登录</h2>
        <p class="form-intro">使用医院预约系统的患者账号继续</p>

        <form @submit.prevent="submit" novalidate>
          <label for="username">患者账号</label>
          <div class="input-wrap">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M20 21a8 8 0 0 0-16 0M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10Z" />
            </svg>
            <input
              id="username"
              v-model.trim="username"
              name="username"
              autocomplete="username"
              placeholder="请输入患者账号"
              :disabled="loading"
              autofocus
            />
          </div>

          <label for="password">密码</label>
          <div class="input-wrap">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 10V8a6 6 0 0 1 12 0v2M5 10h14v11H5V10Zm7 5v2" />
            </svg>
            <input
              id="password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              :disabled="loading"
            />
          </div>

          <p v-if="error" class="form-error" role="alert">{{ error }}</p>

          <button class="submit-button" type="submit" :disabled="loading">
            <span>{{ loading ? '正在验证患者身份…' : '登录并进入 Agent' }}</span>
            <svg v-if="!loading" viewBox="0 0 24 24" aria-hidden="true">
              <path d="m9 18 6-6-6-6" />
            </svg>
          </button>
          <div class="login-divider"><span>医院 Gateway 未启动</span></div>
          <button class="local-dev-button" type="button" :disabled="loading" @click="submitLocalDev">
            {{ loading ? '正在登录…' : '使用本地开发患者登录' }}
          </button>
        </form>

        <div class="security-note">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Zm-3-10 2 2 4-4" />
          </svg>
          <span>医院服务关闭时可使用本地开发登录；该模式不发送账号密码，也不生成正式 JWT。</span>
        </div>
        <RouterLink class="role-switch" to="/admin/login">管理员登录入口 →</RouterLink>
      </div>
    </section>
  </main>
</template>
