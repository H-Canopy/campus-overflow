import { createContext, useContext } from 'react'
import type { Me } from './types'

export interface AuthState {
  me: Me | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

/** 认证上下文单独成文件：与 AuthProvider 分离，避免 fast-refresh 规则报“组件文件导出非组件”。 */
export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
