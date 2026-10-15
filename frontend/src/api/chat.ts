import { ApiError, readApiResponse } from './http'
import { agentRequestHeaders } from './agentHeaders'

export type ChatCitation = {
  reference: number
  chunkId: string
  documentId: string
  fileName: string
  chunkIndex: number
  content: string
  score: number
}

export type ChatTokenUsage = {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export type AgentTraceStepType = 'ROUTING' | 'LLM' | 'TOOL' | 'RAG' | 'HITL'
export type AgentTraceStatus = 'SUCCESS' | 'FAILED'

export type AgentTraceStep = {
  sequence: number
  type: AgentTraceStepType
  name: string
  startedAt: string
  durationMs: number
  status: AgentTraceStatus
  summary: string
  details: Record<string, unknown>
}

export type AgentTrace = {
  turnId: string
  startedAt: string
  totalDurationMs: number
  steps: AgentTraceStep[]
}

export type PatientActionStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'FAILED'

export type PatientActionType =
  | 'CANCEL_APPOINTMENT'
  | 'CREATE_APPOINTMENT'
  | 'JOIN_WAITLIST'
  | 'CANCEL_WAITLIST'
  | 'CONFIRM_WAITLIST'

export type ActionPreviewField = {
  key: string
  label: string
  value: string
}

export type PatientActionPreview = {
  title: string
  description: string
  confirmButtonText: string
  rejectButtonText: string
  fields: ActionPreviewField[]
}

export type PendingPatientAction = {
  actionId: string
  type: PatientActionType
  status: PatientActionStatus
  preview: PatientActionPreview
  expiresAt: string
}

export type PatientActionResponse = {
  actionId: string
  type: PatientActionType
  status: PatientActionStatus
  message: string
  preview: PatientActionPreview
  turnId: string | null
  trace: AgentTrace | null
}

export type PatientChatResponse = {
  question: string
  answer: string
  mode: 'RAG' | 'TOOL' | 'CONFIRMATION_REQUIRED'
  model: string
  embeddingModel: string | null
  citations: ChatCitation[]
  usage: ChatTokenUsage
  tools: string[]
  pendingAction: PendingPatientAction | null
  actionUpdate: PatientActionResponse | null
  turnId: string | null
  trace: AgentTrace | null
}

export type ConversationSummary = {
  conversationId: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

export type ConversationHistoryMessage = {
  sequence: number
  messageId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
  response: PatientChatResponse | null
}

export type ConversationMessagePage = {
  messages: ConversationHistoryMessage[]
  nextBeforeSequence: number | null
  hasMore: boolean
}

type PatientChatStreamEvent = {
  type: 'delta' | 'trace' | 'complete' | 'error'
  content: string | null
  response: PatientChatResponse | null
  code: number | null
  message: string | null
  turnId: string | null
  trace: AgentTrace | null
}

export function listPatientConversations(token: string) {
  return fetch('/api/agent/patient/conversations', {
    headers: agentRequestHeaders(token)
  }).then(response => readApiResponse<ConversationSummary[]>(response, '无法加载历史对话'))
}

export function createPatientConversation(token: string) {
  return fetch('/api/agent/patient/conversations', {
    method: 'POST',
    headers: agentRequestHeaders(token)
  }).then(response => readApiResponse<ConversationSummary>(response, '新建对话失败'))
}

export function renamePatientConversation(
  token: string,
  conversationId: string,
  title: string
) {
  return fetch(`/api/agent/patient/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'PATCH',
    headers: {
      ...agentRequestHeaders(token),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ title })
  }).then(response => readApiResponse<ConversationSummary>(response, '修改对话名称失败'))
}

export function loadPatientConversationMessages(
  token: string,
  conversationId: string,
  before?: number | null,
  size = 20
) {
  const params = new URLSearchParams({ size: String(size) })
  if (before != null) params.set('before', String(before))
  return fetch(`/api/agent/patient/conversations/${encodeURIComponent(conversationId)}/messages?${params}`, {
    headers: agentRequestHeaders(token)
  }).then(response => readApiResponse<ConversationMessagePage>(response, '无法加载聊天记录'))
}

export async function deletePatientConversation(token: string, conversationId: string) {
  const response = await fetch(
    `/api/agent/patient/conversations/${encodeURIComponent(conversationId)}`,
    { method: 'DELETE', headers: agentRequestHeaders(token) }
  )
  return readApiResponse<void>(response, '删除对话失败')
}

async function sendActionDecision(
  token: string,
  conversationId: string,
  actionId: string,
  decision: 'confirm' | 'reject'
): Promise<PatientActionResponse> {
  let response: Response
  try {
    response = await fetch(`/api/agent/patient/actions/${encodeURIComponent(actionId)}/${decision}`, {
      method: 'POST',
      headers: {
        ...agentRequestHeaders(token),
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ conversationId })
    })
  } catch {
    throw new ApiError('无法连接到操作确认服务，请稍后重试')
  }
  return readApiResponse(response, decision === 'confirm' ? '操作执行失败' : '放弃操作失败')
}

export function confirmPatientAction(token: string, conversationId: string, actionId: string) {
  return sendActionDecision(token, conversationId, actionId, 'confirm')
}

export function rejectPatientAction(token: string, conversationId: string, actionId: string) {
  return sendActionDecision(token, conversationId, actionId, 'reject')
}

export async function sendPatientMessage(
  token: string,
  conversationId: string,
  message: string
): Promise<PatientChatResponse> {
  let response: Response
  try {
    response = await fetch('/api/agent/patient/chat/messages', {
      method: 'POST',
      headers: {
        ...agentRequestHeaders(token),
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ conversationId, message })
    })
  } catch {
    throw new ApiError('无法连接到智能问答服务，请稍后重试')
  }
  return readApiResponse(response, '消息发送失败，请稍后重试')
}

export async function streamPatientMessage(
  token: string,
  conversationId: string,
  message: string,
  onDelta: (content: string) => void,
  onTrace: (trace: AgentTrace) => void
): Promise<PatientChatResponse> {
  let response: Response
  try {
    response = await fetch('/api/agent/patient/chat/messages/stream', {
      method: 'POST',
      headers: {
        ...agentRequestHeaders(token),
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify({ conversationId, message })
    })
  } catch {
    throw new ApiError('无法连接到智能问答服务，请稍后重试')
  }

  if (!response.ok) {
    return readApiResponse(response, '消息发送失败，请稍后重试')
  }
  if (!response.body) throw new ApiError('浏览器未收到流式响应，请稍后重试')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed: PatientChatResponse | null = null

  const consumeEvent = (block: string) => {
    const data = block.split('\n')
      .filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart())
      .join('\n')
    if (!data) return

    let event: PatientChatStreamEvent
    try {
      event = JSON.parse(data) as PatientChatStreamEvent
    } catch {
      throw new ApiError('流式响应格式无效，请稍后重试')
    }
    if (event.type === 'delta' && event.content) {
      onDelta(event.content)
    } else if (event.type === 'trace' && event.trace) {
      onTrace(event.trace)
    } else if (event.type === 'complete' && event.response) {
      completed = event.response
    } else if (event.type === 'error') {
      throw new ApiError(event.message || '消息发送失败，请稍后重试', {
        code: event.code ?? undefined
      })
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    buffer = buffer.replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      consumeEvent(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
  if (buffer.trim()) consumeEvent(buffer)
  if (!completed) throw new ApiError('流式回答意外中断，请稍后重试')
  return completed
}
