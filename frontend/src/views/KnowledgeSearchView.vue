<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentAdmin } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import { searchKnowledge, type KnowledgeSearchResponse } from '../api/knowledge'
import { ApiError } from '../api/http'
import { session, signOut } from '../stores/session'

const router = useRouter()
const query = ref('')
const limit = ref(5)
const loading = ref(false)
const checking = ref(true)
const loggingOut = ref(false)
const error = ref('')
const response = ref<KnowledgeSearchResponse | null>(null)

const canSearch = computed(() => query.value.trim().length > 0 && query.value.trim().length <= 1000)

onMounted(async () => {
  try {
    const verified = await getCurrentAdmin(session.token)
    if (verified.role !== 'STAFF' || verified.userId !== session.userId) throw new Error('role mismatch')
  } catch {
    await leaveForLogin()
  } finally {
    checking.value = false
  }
})

async function search() {
  if (!canSearch.value || loading.value || checking.value) return
  loading.value = true
  error.value = ''
  response.value = null
  try {
    response.value = await searchKnowledge(session.token, query.value.trim(), limit.value)
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    error.value = cause instanceof ApiError ? cause.message : '向量检索失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function useExample(value: string) {
  query.value = value
  response.value = null
  error.value = ''
}

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logoutRequest(session.token)
  } finally {
    await leaveForLogin()
    loggingOut.value = false
  }
}

async function leaveForLogin() {
  signOut()
  await router.replace('/admin/login')
}

function scoreText(score: number) {
  return Number.isFinite(score) ? score.toFixed(4) : '0.0000'
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
        <span>{{ session.name }} · 管理员</span>
        <button class="logout-button" :disabled="loggingOut" @click="logout">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>

    <section class="admin-shell compact knowledge-page">
      <RouterLink class="back-link" to="/admin">← 返回管理首页</RouterLink>

      <div class="page-title">
        <p class="eyebrow mint">VECTOR SEARCH LAB</p>
        <h1>向量检索测试</h1>
        <p>输入自然语言问题，使用 BGE-M3 生成查询向量，并从 Elasticsearch 召回语义最接近的 chunk。</p>
      </div>

      <section class="search-panel" aria-labelledby="search-title">
        <div class="search-panel-heading">
          <div>
            <span class="step-label">管理员测试工具</span>
            <h2 id="search-title">测试问题</h2>
          </div>
          <label class="limit-field">
            返回数量
            <select v-model.number="limit" :disabled="loading">
              <option :value="3">Top 3</option>
              <option :value="5">Top 5</option>
              <option :value="10">Top 10</option>
              <option :value="20">Top 20</option>
            </select>
          </label>
        </div>

        <form class="vector-search-form" @submit.prevent="search">
          <textarea
            v-model="query"
            maxlength="1000"
            :disabled="loading || checking"
            placeholder="例如：门诊预约取消后多久可以收到退款？"
            aria-label="检索问题"
          ></textarea>
          <div class="search-form-footer">
            <span>{{ query.trim().length }}/1000</span>
            <button class="primary-button search-button" :disabled="!canSearch || loading || checking">
              {{ loading ? '正在向量化并检索…' : '开始检索' }}
            </button>
          </div>
        </form>

        <div class="search-examples">
          <span>测试问题</span>
          <button @click="useExample('门诊预约取消后，费用什么时候退回？')">预约退费</button>
          <button @click="useExample('晚上突发胸痛应该去哪里就诊？')">急诊就医</button>
          <button @click="useExample('住院探视有哪些时间和人数限制？')">住院探视</button>
          <button @click="useExample('检验报告可以通过哪些方式领取？')">报告领取</button>
        </div>

        <p v-if="error" class="notice error-notice" role="alert">{{ error }}</p>
      </section>

      <section class="search-results" aria-live="polite">
        <div class="knowledge-toolbar">
          <div>
            <h2>召回结果</h2>
            <span v-if="response">{{ response.results.length }} 个 chunk</span>
            <span v-else>尚未检索</span>
          </div>
          <span v-if="response" class="storage-chip">{{ response.embeddingModel }} · Top {{ response.limit }}</span>
        </div>

        <div v-if="loading" class="empty-state compact-state search-loading">
          <span class="loading-ring" aria-hidden="true"></span>
          <p>正在调用 Embedding API 并执行 Elasticsearch kNN 检索…</p>
        </div>
        <div v-else-if="response && response.results.length === 0" class="empty-state compact-state">
          <span class="empty-icon" aria-hidden="true">检</span>
          <h2>没有可召回的 chunk</h2>
          <p>请先到“文档与知识库”上传文档并构建索引，然后重新测试。</p>
        </div>
        <div v-else-if="response" class="result-list">
          <article v-for="item in response.results" :key="item.chunkId" class="result-card">
            <div class="result-rank">{{ item.rank }}</div>
            <div class="result-body">
              <header>
                <div>
                  <strong>{{ item.fileName }}</strong>
                  <span>Chunk {{ item.chunkIndex + 1 }} · {{ item.chunkId }}</span>
                </div>
                <span class="score-chip">相关分 {{ scoreText(item.score) }}</span>
              </header>
              <p>{{ item.content }}</p>
            </div>
          </article>
        </div>
        <div v-else class="empty-state compact-state">
          <span class="empty-icon" aria-hidden="true">向</span>
          <h2>等待测试</h2>
          <p>检索结果会展示来源文档、chunk 序号、完整 chunk 文本和 Elasticsearch 相关分。</p>
        </div>
      </section>

      <p class="search-scope-note">当前仅用于管理员验证召回质量，不调用大模型生成答案，也不区分知识发布状态。</p>
    </section>
  </main>
</template>
