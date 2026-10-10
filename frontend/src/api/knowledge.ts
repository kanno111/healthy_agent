import { ApiError, readApiResponse } from './http'
import { agentRequestHeaders } from './agentHeaders'

export type KnowledgeDocument = {
  id: string
  fileName: string
  size: number
  contentType: string
  status: 'UPLOADED'
  indexed: boolean
  chunkCount: number
  indexedAt: string | null
  uploadedAt: string
}

export type KnowledgeDocumentPreview = {
  documentId: string
  fileName: string
  contentType: string
  preview: string
  extractedCharacters: number
  previewCharacters: number
  truncated: boolean
}

export type KnowledgeIndexResult = {
  documentId: string
  indexed: boolean
  chunkCount: number
  indexedAt: string | null
  skipped: boolean
}

export type KnowledgeSearchHit = {
  rank: number
  chunkId: string
  documentId: string
  fileName: string
  contentType: string
  chunkIndex: number
  content: string
  score: number
}

export type KnowledgeSearchStrategy = 'VECTOR' | 'BM25' | 'HYBRID'

export type KnowledgeSearchResponse = {
  query: string
  strategy: KnowledgeSearchStrategy
  embeddingModel: string | null
  limit: number
  results: KnowledgeSearchHit[]
}

export type KnowledgeRetrievalEvaluationCaseResult = {
  id: string
  category: string
  question: string
  shouldAnswer: boolean
  expectedFileNames: string[]
  returnedFileNames: string[]
  firstRelevantRank: number
  hitAtK: boolean
  recallAtK: number
  precisionAtK: number
  reciprocalRank: number
  ndcgAtK: number
}

export type KnowledgeRetrievalEvaluationResponse = {
  datasetVersion: string
  strategy: KnowledgeSearchStrategy
  limit: number
  totalCases: number
  positiveCases: number
  negativeCases: number
  recallAtK: number
  precisionAtK: number
  hitRateAtK: number
  mrr: number
  ndcgAtK: number
  negativeEmptyRate: number
  evaluatedAt: string
  cases: KnowledgeRetrievalEvaluationCaseResult[]
}

export type KnowledgeRagCitation = {
  reference: number
  chunkId: string
  documentId: string
  fileName: string
  chunkIndex: number
  content: string
  score: number
}

export type TokenUsage = {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export type KnowledgeRagResponse = {
  question: string
  answer: string
  model: string
  embeddingModel: string
  citations: KnowledgeRagCitation[]
  usage: TokenUsage
}

const DOCUMENT_ENDPOINT = '/api/agent/admin/knowledge/documents'

export async function listKnowledgeDocuments(token: string): Promise<KnowledgeDocument[]> {
  let response: Response
  try {
    response = await fetch(DOCUMENT_ENDPOINT, {
      headers: agentRequestHeaders(token)
    })
  } catch {
    throw new ApiError('无法连接到文档服务，请确认 Gateway 和 Agent 已启动')
  }
  return readApiResponse(response, '读取文档列表失败')
}

export async function uploadKnowledgeDocument(
  token: string,
  file: File
): Promise<KnowledgeDocument> {
  const form = new FormData()
  form.append('file', file)

  let response: Response
  try {
    response = await fetch(DOCUMENT_ENDPOINT, {
      method: 'POST',
      headers: agentRequestHeaders(token),
      body: form
    })
  } catch {
    throw new ApiError('无法连接到文档服务，请稍后重试')
  }
  return readApiResponse(response, '文档上传失败')
}

export async function previewKnowledgeDocument(
  token: string,
  documentId: string
): Promise<KnowledgeDocumentPreview> {
  let response: Response
  try {
    response = await fetch(`${DOCUMENT_ENDPOINT}/${encodeURIComponent(documentId)}/parse-preview`, {
      method: 'POST',
      headers: agentRequestHeaders(token)
    })
  } catch {
    throw new ApiError('无法连接到文档解析服务，请稍后重试')
  }
  return readApiResponse(response, '文档解析失败')
}

export async function deleteKnowledgeDocument(token: string, documentId: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(`${DOCUMENT_ENDPOINT}/${encodeURIComponent(documentId)}`, {
      method: 'DELETE',
      headers: agentRequestHeaders(token)
    })
  } catch {
    throw new ApiError('无法连接到文档服务，请稍后重试')
  }
  await readApiResponse<null>(response, '文档删除失败')
}

export async function buildKnowledgeDocumentIndex(
  token: string,
  documentId: string,
  force: boolean
): Promise<KnowledgeIndexResult> {
  let response: Response
  try {
    const query = force ? '?force=true' : ''
    response = await fetch(`${DOCUMENT_ENDPOINT}/${encodeURIComponent(documentId)}/index${query}`, {
      method: 'POST',
      headers: agentRequestHeaders(token)
    })
  } catch {
    throw new ApiError('无法连接到索引服务，请稍后重试')
  }
  return readApiResponse(response, '文档索引构建失败')
}

export async function searchKnowledge(
  token: string,
  query: string,
  limit: number,
  strategy: KnowledgeSearchStrategy = 'VECTOR'
): Promise<KnowledgeSearchResponse> {
  let response: Response
  try {
    response = await fetch('/api/agent/admin/knowledge/search', {
      method: 'POST',
      headers: {
        ...agentRequestHeaders(token),
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ query, limit, strategy })
    })
  } catch {
    throw new ApiError('无法连接到知识检索服务，请稍后重试')
  }
  return readApiResponse(response, '知识检索失败')
}

export async function evaluateKnowledgeRetrieval(
  token: string,
  strategy: KnowledgeSearchStrategy,
  limit: number
): Promise<KnowledgeRetrievalEvaluationResponse> {
  let response: Response
  try {
    response = await fetch('/api/agent/admin/knowledge/evaluations/retrieval', {
      method: 'POST',
      headers: {
        ...agentRequestHeaders(token),
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ strategy, limit })
    })
  } catch {
    throw new ApiError('无法连接到检索评测服务，请稍后重试')
  }
  return readApiResponse(response, '检索评测失败')
}

export async function askKnowledge(
  token: string,
  question: string,
  limit: number
): Promise<KnowledgeRagResponse> {
  let response: Response
  try {
    response = await fetch('/api/agent/admin/knowledge/rag/ask', {
      method: 'POST',
      headers: {
        ...agentRequestHeaders(token),
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ question, limit })
    })
  } catch {
    throw new ApiError('无法连接到 RAG 问答服务，请稍后重试')
  }
  return readApiResponse(response, '知识库问答失败')
}
