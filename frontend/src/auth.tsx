import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { api } from './api'
import { AuthContext, type AuthState } from './auth-context'
import type { Me } from './types'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setMe(await api.me())
    } catch {
      setMe(null) // 401 表示未登录，属于正常状态
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    api
      .csrf()
      .catch(() => undefined)
      .then(refresh)
  }, [refresh])

  const value = useMemo<AuthState>(
    () => ({
      me,
      loading,
      login: async (username, password) =>
        setMe(await api.login(username, password)),
      logout: async () => {
        await api.logout()
        setMe(null)
      },
      refresh,
    }),
    [me, loading, refresh],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
