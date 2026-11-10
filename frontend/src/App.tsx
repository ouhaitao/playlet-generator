import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import ProjectDetail from '@/components/ProjectDetail'

/**
 * 应用入口：项目列表 ↔ 项目详情（分镜 + 审核台）。
 * M1 用 state 路由，不引 react-router。
 */
export default function App() {
  const [projectId, setProjectId] = useState<number | null>(null)
  const { data: projects, isLoading, error } = useQuery({
    queryKey: ['projects'],
    queryFn: api.projects.list,
    enabled: projectId == null,
  })

  return (
    <div className="min-h-screen">
      <header className="border-b px-6 py-4">
        <h1 className="text-xl font-bold">短剧生成平台</h1>
        <p className="text-sm text-muted-foreground">
          agent 驱动 · ComfyUI 生成后端 · 串行审核
        </p>
      </header>

      <main className="p-6">
        {projectId != null ? (
          <ProjectDetail projectId={projectId} onBack={() => setProjectId(null)} />
        ) : (
          <>
            <h2 className="mb-4 text-lg font-semibold">项目列表</h2>
            {isLoading && <p className="text-muted-foreground">加载中…</p>}
            {error && (
              <p className="text-destructive">后端未就绪（{String(error)}）。</p>
            )}
            {projects && projects.length === 0 && (
              <p className="text-muted-foreground">暂无项目。</p>
            )}
            {projects && projects.length > 0 && (
              <ul className="space-y-2">
                {projects.map((p) => (
                  <li key={p.id}>
                    <button
                      className="w-full rounded-lg border p-4 text-left hover:bg-muted"
                      onClick={() => setProjectId(p.id)}
                    >
                      <div className="font-medium">{p.synopsis.slice(0, 60)}</div>
                      <div className="mt-1 text-sm text-muted-foreground">
                        {p.mode === 'ONE_SHOT' ? '一镜到底' : '分镜'} · {p.aspectRatio} ·{' '}
                        {p.videoModel} · {p.state}
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </main>
    </div>
  )
}
