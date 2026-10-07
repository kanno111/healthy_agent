<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentAdmin } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import { askKnowledge, type KnowledgeRagResponse } from '../api/knowledge'
import { ApiError } from '../api/http'
import { session, signOut } from '../stores/session'

const router = useRouter()
const question = ref('')
const limit = ref(3)
const loading = ref(false)
const checking = ref(true)
const loggingOut = ref(false)
const error = ref('')
const response = ref<KnowledgeRagResponse | null>(null)
const elapsedMilliseconds = ref<number | null>(null)
const usedLimit = ref(3)

const normalizedQuestion = computed(() => question.value.trim())
const canAsk = computed(() => normalizedQuestion.value.length > 0 && normalizedQuestion.value.length <= 1000)

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

async function ask() {
  if (!canAsk.value || loading.value || checking.value) return
  loading.value = true
  error.value = ''
  response.value = null
  elapsedMilliseconds.value = null
  const startedAt = performance.now()
  const requestedLimit = limit.value
  try {
    response.value = await askKnowledge(session.token, normalizedQuestion.value, requestedLimit)
    usedLimit.value = requestedLimit
    elapsedMilliseconds.value = Math.round(performance.now() - startedAt)
  } catch (cause) {
    elapsedMilliseconds.value = Math.round(performance.now() - startedAt)
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    error.value = cause instanceof ApiError ? cause.message : '知识库问答失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function useExample(value: string) {
  question.value = value
  response.value = null
  elapsedMilliseconds.value = null
  error.value = ''
}

function scoreText(score: number) {
  return Number.isFinite(score) ? score.toFixed(4) : '0.0000'
}

function elapsedText(milliseconds: number | null) {
  if (milliseconds === null) return '—'
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(2)} s`
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
</script>

<template>
  <main class="admin-page rag-page">
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

      <div class="page-title rag-title">
        <p class="eyebrow mint">RAG ANSWER LAB</p>
        <h1>知识库问答测试</h1>
        <p>使用 BGE-M3 与 Elasticsearch 查找依据，再由 DeepSeek 生成带来源的回答。</p>
      </div>

      <section class="search-panel rag-question-panel" aria-labelledby="rag-question-title">
        <div class="search-panel-heading">
          <div>
            <span class="step-label">管理员测试工具</span>
            <h2 id="rag-question-title">向知识库提问</h2>
          </div>
          <label class="limit-field">
            召回数量
            <select v-model.number="limit" :disabled="loading">
              <option :value="1">Top 1</option>
              <option :value="3">Top 3（推荐）</option>
              <option :value="5">Top 5</option>
              <option :value="8">Top 8</option>
            </select>
          </label>
        </div>

        <form class="vector-search-form" @submit.prevent="ask">
          <textarea
            v-model="question"
            maxlength="1000"
            :disabled="loading || checking"
            placeholder="例如：门诊预约取消后如何退费？"
            aria-label="知识库问题"
          ></textarea>
          <div class="search-form-footer">
            <span>{{ normalizedQuestion.length }}/1000</span>
            <button class="primary-button search-button rag-submit" :disabled="!canAsk || loading || checking">
              {{ loading ? '正在检索并生成回答…' : '生成知识库回答' }}
            </button>
          </div>
        </form>

        <div class="search-examples">
          <span>试试这些问题</span>
          <button type="button" @click="useExample('门诊预约取消后如何退费？')">预约退费</button>
          <button type="button" @click="useExample('晚上几点可以去病房看望家人？')">住院探视</button>
          <button type="button" @click="useExample('家属能帮我领取病理报告吗？')">报告领取</button>
          <button type="button" @click="useExample('晚上突然胸痛应该怎么办？')">急诊就医</button>
        </div>

        <p v-if="error" class="notice error-notice" role="alert">{{ error }}</p>
        <p class="rag-provider-note">问题和召回的知识片段会发送给 DeepSeek；登录 Token、密码和 ES 向量不会发送。</p>
      </section>

      <section class="rag-answer-section" aria-live="polite">
        <div v-if="loading" class="rag-loading-card">
          <span class="loading-ring" aria-hidden="true"></span>
          <div>
            <strong>正在生成回答</strong>
            <p>正在执行问题向量化、知识检索和 DeepSeek 生成，请稍候。</p>
          </div>
        </div>

        <template v-else-if="response">
          <article class="rag-answer-card">
            <header class="rag-answer-header">
              <div>
                <span class="answer-kicker">AI ANSWER</span>
                <h2>知识库回答</h2>
              </div>
              <span class="model-chip">{{ response.model }}</span>
            </header>
            <p class="rag-answer-text">{{ response.answer }}</p>

            <div class="rag-metrics" aria-label="本次请求统计">
              <div><span>输入 Token</span><strong>{{ response.usage.promptTokens }}</strong></div>
              <div><span>输出 Token</span><strong>{{ response.usage.completionTokens }}</strong></div>
              <div><span>总 Token</span><strong>{{ response.usage.totalTokens }}</strong></div>
              <div><span>前端耗时</span><strong>{{ elapsedText(elapsedMilliseconds) }}</strong></div>
            </div>
          </article>

          <section class="rag-citations-section">
            <div class="knowledge-toolbar rag-citations-toolbar">
              <div>
                <h2>引用依据</h2>
                <span>{{ response.citations.length }} 个实际上下文片段</span>
              </div>
              <span class="storage-chip">{{ response.embeddingModel }} · Top {{ usedLimit }}</span>
            </div>

            <div v-if="response.citations.length" class="rag-citation-list">
              <details
                v-for="citation in response.citations"
                :key="citation.chunkId"
                class="rag-citation-card"
                :open="citation.reference === 1"
              >
                <summary>
                  <span class="citation-reference">资料 {{ citation.reference }}</span>
                  <span class="citation-title">
                    <strong>{{ citation.fileName }}</strong>
                    <small>Chunk {{ citation.chunkIndex + 1 }}</small>
                  </span>
                  <span class="score-chip">相关分 {{ scoreText(citation.score) }}</span>
                  <span class="citation-toggle" aria-hidden="true">⌄</span>
                </summary>
                <p>{{ citation.content }}</p>
              </details>
            </div>
            <div v-else class="empty-state compact-state rag-no-citation">
              <span class="empty-icon" aria-hidden="true">空</span>
              <h2>没有足够的知识依据</h2>
              <p>本次没有达到最低相关分的知识片段，因此没有调用大模型生成扩展内容。</p>
            </div>
          </section>
        </template>

        <div v-else class="rag-welcome-card">
          <span class="rag-orbit" aria-hidden="true">答</span>
          <h2>等待你的问题</h2>
          <p>回答会同时展示引用文档、实际上下文、相关分、Token 用量和请求耗时，方便检查 RAG 质量。</p>
        </div>
      </section>

      <p class="search-scope-note">当前是管理员测试入口，检索范围为所有已经成功构建索引的文档，暂未启用知识发布状态。</p>
    </section>
  </main>
</template>
