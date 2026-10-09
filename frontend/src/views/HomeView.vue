<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentPatient } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import {
  confirmPatientAction,
  rejectPatientAction,
  sendPatientMessage,
  type ChatCitation,
  type ChatTokenUsage,
  type PatientActionResponse,
  type PatientActionStatus,
  type PendingPatientAction
} from '../api/chat'
import { ApiError } from '../api/http'
import { session, signOut } from '../stores/session'

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
  welcome?: boolean
  citations?: ChatCitation[]
  model?: string
  usage?: ChatTokenUsage
  mode?: 'RAG' | 'TOOL' | 'CONFIRMATION_REQUIRED'
  tools?: string[]
  pendingAction?: PendingPatientAction | null
  actionStatus?: PatientActionStatus
  actionMessage?: string
  actionBusy?: boolean
  actionError?: string
}

const router = useRouter()
const checking = ref(true)
const loggingOut = ref(false)
const sending = ref(false)
const input = ref('')
const error = ref('')
const chatViewport = ref<HTMLElement | null>(null)
const MEMORY_MAX_MESSAGES = 40
let nextMessageId = 1
let conversationId = crypto.randomUUID()

const createWelcomeMessage = (): ChatMessage => ({
  id: nextMessageId++,
  role: 'assistant',
  welcome: true,
  content: `你好，${session.name}。我可以查询科室、医生、号源以及你的预约和候补记录，也可以根据知识库回答医院制度问题。`
})

const messages = ref<ChatMessage[]>([createWelcomeMessage()])
const normalizedInput = computed(() => input.value.trim())
const canSend = computed(() => normalizedInput.value.length > 0 && normalizedInput.value.length <= 1000)
const showSuggestions = computed(() => messages.value.length === 1 && !sending.value)

onMounted(async () => {
  try {
    const verified = await getCurrentPatient(session.token)
    if (verified.role !== 'PATIENT' || verified.userId !== session.userId) throw new Error('role mismatch')
  } catch {
    await leaveForLogin()
  } finally {
    checking.value = false
  }
})

async function send(message?: string) {
  const content = (message ?? normalizedInput.value).trim()
  if (!content || content.length > 1000 || sending.value || checking.value) return

  appendMemoryMessage({ id: nextMessageId++, role: 'user', content })
  input.value = ''
  error.value = ''
  sending.value = true
  await scrollToBottom()

  try {
    const result = await sendWithConversationRecovery(content)
    const reusedPendingCard = reconcileActionState(result)
    appendMemoryMessage({
      id: nextMessageId++,
      role: 'assistant',
      content: result.answer,
      citations: result.citations,
      model: result.model,
      usage: result.usage,
      mode: result.mode,
      tools: result.tools,
      pendingAction: reusedPendingCard ? null : result.pendingAction,
      actionStatus: result.pendingAction?.status
    })
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    error.value = cause instanceof ApiError ? cause.message : '消息发送失败，请稍后重试'
  } finally {
    sending.value = false
    await scrollToBottom()
  }
}

function reconcileActionState(result: Awaited<ReturnType<typeof sendPatientMessage>>) {
  if (result.actionUpdate) {
    const existing = messages.value.find(message =>
      message.pendingAction?.actionId === result.actionUpdate?.actionId)
    if (existing) {
      existing.actionStatus = result.actionUpdate.status
      existing.actionMessage = result.actionUpdate.message
    }
  }
  if (!result.pendingAction) return false
  const existing = messages.value.find(message =>
    message.pendingAction?.actionId === result.pendingAction?.actionId)
  if (!existing) return false
  existing.pendingAction = result.pendingAction
  existing.actionStatus = result.pendingAction.status
  return true
}

async function sendWithConversationRecovery(content: string) {
  try {
    return await sendPatientMessage(session.token, conversationId, content)
  } catch (cause) {
    if (!(cause instanceof ApiError) || cause.code !== 40018) throw cause

    conversationId = crypto.randomUUID()
    const currentUserMessage = messages.value.at(-1)
    messages.value = currentUserMessage ? [currentUserMessage] : []
    return sendPatientMessage(session.token, conversationId, content)
  }
}

function appendMemoryMessage(message: ChatMessage) {
  if (messages.value.length === 1 && messages.value[0]?.welcome) {
    messages.value = []
  }
  messages.value.push(message)
  if (messages.value.length > MEMORY_MAX_MESSAGES) {
    messages.value.splice(0, messages.value.length - MEMORY_MAX_MESSAGES)
  }
}

async function decideAction(message: ChatMessage, decision: 'confirm' | 'reject') {
  const action = message.pendingAction
  if (!action || message.actionBusy || message.actionStatus !== 'PENDING') return
  message.actionBusy = true
  message.actionError = ''
  try {
    const result: PatientActionResponse = decision === 'confirm'
      ? await confirmPatientAction(session.token, conversationId, action.actionId)
      : await rejectPatientAction(session.token, conversationId, action.actionId)
    message.actionStatus = result.status
    message.actionMessage = result.message
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    if (cause instanceof ApiError && (cause.code === 40910 || cause.code === 40911 || cause.code === 40420)) {
      message.actionStatus = cause.code === 40910 ? 'EXPIRED' : 'FAILED'
    }
    message.actionError = cause instanceof ApiError ? cause.message : '操作处理失败，请稍后重试'
  } finally {
    message.actionBusy = false
    await scrollToBottom()
  }
}

function actionStatusLabel(status?: PatientActionStatus) {
  switch (status) {
    case 'PENDING': return '等待确认'
    case 'EXECUTING': return '正在执行'
    case 'SUCCEEDED': return '已完成'
    case 'REJECTED': return '已放弃'
    case 'EXPIRED': return '已过期'
    case 'FAILED': return '执行失败'
    default: return '未知状态'
  }
}

function displayValue(value: string | null | undefined) {
  return value?.trim() || '未提供'
}

function displayExpiry(value: string) {
  const expiresAt = new Date(value)
  if (Number.isNaN(expiresAt.getTime())) return '10 分钟内有效'
  return `请在 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(expiresAt)} 前确认`
}

function resetConversation() {
  if (sending.value) return
  conversationId = crypto.randomUUID()
  messages.value = [createWelcomeMessage()]
  input.value = ''
  error.value = ''
}

async function scrollToBottom() {
  await nextTick()
  if (chatViewport.value) chatViewport.value.scrollTop = chatViewport.value.scrollHeight
}

function scoreText(score: number) {
  return Number.isFinite(score) ? score.toFixed(4) : '0.0000'
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
  await router.replace('/login')
}
</script>

<template>
  <main class="patient-chat-page">
    <header class="topbar patient-chat-topbar">
      <div class="brand dark">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent</span>
      </div>
      <div class="patient-chat-actions">
        <span class="patient-name">{{ session.name }}</span>
        <button class="new-chat-button" :disabled="sending" @click="resetConversation">新对话</button>
        <button class="logout-button" :disabled="loggingOut" @click="logout">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>

    <section class="patient-chat-shell">
      <header class="chat-intro">
        <div>
          <span class="chat-status"><i></i>{{ checking ? '正在验证身份' : '预约助手在线' }}</span>
          <h1>医院智能助手</h1>
          <p>支持知识库问答、业务查询，以及需要患者明确确认的预约和候补操作。</p>
        </div>
        <span class="chat-mode-chip">RAG + Tools + 人工确认</span>
      </header>

      <div ref="chatViewport" class="chat-viewport" aria-live="polite">
        <div class="message-stream">
          <article
            v-for="message in messages"
            :key="message.id"
            class="chat-message"
            :class="`chat-message-${message.role}`"
          >
            <div class="message-avatar" aria-hidden="true">{{ message.role === 'assistant' ? 'AI' : '我' }}</div>
            <div class="message-column">
              <div class="message-bubble">{{ message.content }}</div>

              <div v-if="message.role === 'assistant' && message.citations?.length" class="message-sources">
                <span class="source-heading">回答依据</span>
                <details v-for="citation in message.citations" :key="citation.chunkId" class="patient-source-card">
                  <summary>
                    <span>[资料{{ citation.reference }}]</span>
                    <strong>{{ citation.fileName }}</strong>
                    <small>{{ scoreText(citation.score) }}</small>
                  </summary>
                  <p>{{ citation.content }}</p>
                </details>
              </div>

              <section
                v-if="message.role === 'assistant' && message.pendingAction"
                class="patient-action-card"
                :class="`action-${(message.actionStatus || 'PENDING').toLowerCase()}`"
              >
                <header>
                  <div>
                    <span>HUMAN CONFIRMATION</span>
                    <strong>{{ message.pendingAction.preview.title }}</strong>
                  </div>
                  <em>{{ actionStatusLabel(message.actionStatus) }}</em>
                </header>
                <p class="action-description">{{ message.pendingAction.preview.description }}</p>
                <dl>
                  <div
                    v-for="field in message.pendingAction.preview.fields"
                    :key="field.key"
                  >
                    <dt>{{ field.label }}</dt>
                    <dd>{{ displayValue(field.value) }}</dd>
                  </div>
                </dl>
                <p v-if="message.actionStatus === 'PENDING'" class="action-expiry">
                  {{ displayExpiry(message.pendingAction.expiresAt) }}，超时后必须重新发起。
                </p>
                <p v-if="message.actionMessage" class="action-result">{{ message.actionMessage }}</p>
                <p v-if="message.actionError" class="action-error">{{ message.actionError }}</p>
                <div v-if="message.actionStatus === 'PENDING'" class="action-buttons">
                  <button
                    class="action-reject"
                    :disabled="message.actionBusy"
                    @click="decideAction(message, 'reject')"
                  >{{ message.pendingAction.preview.rejectButtonText }}</button>
                  <button
                    class="action-confirm"
                    :disabled="message.actionBusy"
                    @click="decideAction(message, 'confirm')"
                  >{{ message.actionBusy ? '正在处理…' : message.pendingAction.preview.confirmButtonText }}</button>
                </div>
              </section>

              <div v-if="message.role === 'assistant' && message.usage" class="message-meta">
                <span>{{ message.mode === 'RAG' ? '知识库回答' : message.mode === 'CONFIRMATION_REQUIRED' ? '等待人工确认' : '实时业务查询' }}</span>
                <span>{{ message.model }}</span>
                <span>{{ message.usage.totalTokens }} Token</span>
              </div>
              <div v-if="message.role === 'assistant' && message.tools?.length" class="message-tools">
                <span v-for="tool in message.tools" :key="tool">{{ tool }}</span>
              </div>
            </div>
          </article>

          <article v-if="sending" class="chat-message chat-message-assistant">
            <div class="message-avatar" aria-hidden="true">AI</div>
            <div class="message-column">
              <div class="message-bubble typing-bubble">
                <span></span><span></span><span></span>
                <em>正在判断问题并查询所需数据</em>
              </div>
            </div>
          </article>
        </div>

        <div v-if="showSuggestions" class="patient-suggestions">
          <span>你可以这样问</span>
          <div>
            <button @click="send('帮我查询一下我的预约记录')">查询我的预约</button>
            <button @click="send('帮我取消最近一条可取消的预约')">取消预约</button>
            <button @click="send('帮我查询下周有号的医生并预约')">创建预约</button>
            <button @click="send('查询我的候补记录')">查询我的候补</button>
            <button @click="send('医院有哪些科室？')">查询医院科室</button>
            <button @click="send('门诊预约取消后如何退费？')">门诊取消后如何退费？</button>
            <button @click="send('晚上突然持续胸痛应该怎么办？')">夜间胸痛怎么办？</button>
          </div>
        </div>
      </div>

      <footer class="chat-composer-wrap">
        <p v-if="error" class="chat-error" role="alert">{{ error }}</p>
        <form class="chat-composer" @submit.prevent="send()">
          <textarea
            v-model="input"
            maxlength="1000"
            rows="1"
            :disabled="sending || checking"
            placeholder="查询科室、医生、号源、预约或候补，并办理需要确认的操作…"
            aria-label="聊天消息"
            @keydown.enter.exact.prevent="send()"
          ></textarea>
          <button type="submit" :disabled="!canSend || sending || checking" aria-label="发送消息">
            <span aria-hidden="true">↑</span>
          </button>
        </form>
        <div class="composer-hint">
          <span>Enter 发送 · Shift + Enter 换行</span>
          <span>{{ normalizedInput.length }}/1000</span>
        </div>
        <p class="medical-disclaimer">当前对话只使用最近约 20 轮上下文；点击“新对话”或服务重启后不再记忆旧内容。回答不能替代医生诊断；紧急情况请立即拨打 120。</p>
      </footer>
    </section>
  </main>
</template>
