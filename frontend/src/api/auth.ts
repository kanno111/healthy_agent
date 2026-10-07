import { ApiError, readApiResponse } from './http'

export type PatientLoginResult = {
  token: string
  userId: number
  name: string
  role: string
}

export async function localDevLogin(role: 'PATIENT' | 'STAFF'): Promise<PatientLoginResult> {
  let response: Response
  try {
    response = await fetch('/api/agent/dev-auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role })
    })
  } catch {
    throw new ApiError('无法连接到 Agent 本地开发登录服务')
  }
  return readApiResponse(response, '本地开发登录失败')
}

export async function login(username: string, password: string): Promise<PatientLoginResult> {
  let response: Response
  try {
    response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
  } catch {
    throw new ApiError('无法连接到登录服务，请确认 Gateway 和 Identity 已启动')
  }

  return readApiResponse(response, '登录失败，请稍后重试')
}

export async function logout(token: string): Promise<void> {
  if (token.startsWith('local-dev.')) {
    try {
      const response = await fetch('/api/agent/dev-auth/logout', { method: 'POST' })
      await readApiResponse<null>(response, '退出本地会话失败')
    } catch {
      // Local development tokens only live in memory and can be safely discarded client-side.
    }
    return
  }
  try {
    const response = await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    })
    await readApiResponse<null>(response, '退出登录失败')
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('无法连接到退出登录服务')
  }
}
