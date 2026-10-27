import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth-context'
import type { Page, QuestionSummary, SearchResult } from '../types'

export function QuestionListPage() {
  const { me } = useAuth()
  const [data, setData] = useState<Page<QuestionSummary | SearchResult> | null>(
    null,
  )
  const [tags, setTags] = useState<{ name: string; questionCount: number }[]>(
    [],
  )
  const [keyword, setKeyword] = useState('')
  const [activeTag, setActiveTag] = useState<string | undefined>()
  const [sort, setSort] = useState('newest')
  const [unanswered, setUnanswered] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    api
      .popularTags()
      .then(setTags)
      .catch(() => undefined)
  }, [])

  // 搜索防抖：停止输入 300ms 后才发请求，避免每敲一个字符打一次接口
  const [debouncedKeyword, setDebouncedKeyword] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedKeyword(keyword.trim()), 300)
    return () => clearTimeout(timer)
  }, [keyword])

  useEffect(() => {
    // cancelled 用来丢弃过期响应：旧请求晚回来时不再覆盖新结果
    let cancelled = false
    const searching =
      debouncedKeyword !== '' || activeTag || unanswered || sort !== 'newest'
    const promise = searching
      ? api.search(debouncedKeyword, { tag: activeTag, sort, unanswered })
      : api.questions(1)
    promise
      .then((r) => {
        if (cancelled) return
        setData(r)
        setError('')
      })
      .catch((e) => {
        if (!cancelled) setError(e.message)
      })
    return () => {
      cancelled = true
    }
  }, [debouncedKeyword, activeTag, sort, unanswered])

  return (
    <div className="layout">
      <section>
        <div className="toolbar">
          <input
            placeholder="搜索问题（支持中文全文检索）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select value={sort} onChange={(e) => setSort(e.target.value)}>
            <option value="newest">最新</option>
            <option value="active">最近活跃</option>
            <option value="hot">得分最高</option>
            <option value="relevance">相关度</option>
          </select>
          <label className="check">
            <input
              type="checkbox"
              checked={unanswered}
              onChange={(e) => setUnanswered(e.target.checked)}
            />
            仅看未回答
          </label>
          {me && (
            <Link className="primary" to="/ask">
              我要提问
            </Link>
          )}
        </div>

        {activeTag && (
          <p className="muted">
            标签筛选：<b>{activeTag}</b>{' '}
            <button onClick={() => setActiveTag(undefined)}>清除</button>
          </p>
        )}
        {error && <p className="error">{error}</p>}

        <ul className="qlist">
          {data?.items.map((q) => (
            <li key={q.id}>
              <div className="stats">
                <span>
                  <b>{q.score}</b> 票
                </span>
                <span className={q.accepted ? 'accepted' : ''}>
                  <b>{q.answerCount}</b> 回答
                </span>
                <span>{q.viewCount} 浏览</span>
              </div>
              <div className="qmain">
                <Link className="qtitle" to={`/questions/${q.id}`}>
                  {q.title}
                </Link>
                <p className="excerpt">{q.excerpt}</p>
                <div className="tagrow">
                  {q.tags.map((t) => (
                    <button
                      key={t}
                      className="tag"
                      onClick={() => setActiveTag(t)}
                    >
                      {t}
                    </button>
                  ))}
                  <span className="muted">
                    {q.author.displayName} ·{' '}
                    {new Date(q.createdAt).toLocaleString('zh-CN')}
                  </span>
                </div>
              </div>
            </li>
          ))}
        </ul>
        {data && data.items.length === 0 && (
          <p className="muted">没有匹配的问题，换个关键词试试。</p>
        )}
      </section>

      <aside>
        <h3>热门标签</h3>
        <div className="tagrow">
          {tags.map((t) => (
            <button
              key={t.name}
              className="tag"
              onClick={() => setActiveTag(t.name)}
            >
              {t.name}
              <span className="count">{t.questionCount}</span>
            </button>
          ))}
        </div>
      </aside>
    </div>
  )
}
