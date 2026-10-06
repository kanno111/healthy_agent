import { reactive } from 'vue'

const STORAGE_KEY = 'healthy-agent-session'
const LEGACY_STORAGE_KEY = 'healthy-agent-patient-session'

export type AgentRole = 'PATIENT' | 'STAFF'

export type SessionState = {
  loggedIn: boolean
  token: string
  userId: number | null
  name: string
  role: AgentRole | null
}

function emptySession(): SessionState {
  return { loggedIn: false, token: '', userId: null, name: '', role: null }
}

function restoreSession(): SessionState {
  const saved = sessionStorage.getItem(STORAGE_KEY)
  sessionStorage.removeItem(LEGACY_STORAGE_KEY)
  if (!saved) return emptySession()

  try {
    const parsed = JSON.parse(saved) as Partial<SessionState>
    if (
      parsed.loggedIn && parsed.token && parsed.userId && parsed.name &&
      (parsed.role === 'PATIENT' || parsed.role === 'STAFF')
    ) {
      return parsed as SessionState
    }
  } catch {
    // Invalid browser state is discarded below.
  }

  sessionStorage.removeItem(STORAGE_KEY)
  return emptySession()
}

export const session = reactive<SessionState>(restoreSession())

export function signIn(data: { token: string; userId: number; name: string; role: AgentRole }) {
  Object.assign(session, {
    loggedIn: true,
    token: data.token,
    userId: data.userId,
    name: data.name,
    role: data.role
  })
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
}

export function signOut() {
  Object.assign(session, emptySession())
  sessionStorage.removeItem(STORAGE_KEY)
}
