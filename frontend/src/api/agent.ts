import { ApiError, readApiResponse } from './http'

export type AgentRole = 'PATIENT' | 'STAFF'

export type AgentSession = {
  userId: number
  role: AgentRole
  authenticated: true
}

async function getCurrentSession(token: string, endpoint: string): Promise<AgentSession> {
  let response: Response
  try {
    response = await fetch(endpoint, {
      headers: { Authorization: `Bearer ${token}` }
    })
  } catch {
    throw new ApiError('无法连接到 Agent 服务，请确认服务和 Gateway 路由已启动')
  }

  return readApiResponse(response, '登录状态验证失败')
}

export function getCurrentPatient(token: string): Promise<AgentSession> {
  return getCurrentSession(token, '/api/agent/patient/auth/me')
}

export function getCurrentAdmin(token: string): Promise<AgentSession> {
  return getCurrentSession(token, '/api/agent/admin/auth/me')
}
