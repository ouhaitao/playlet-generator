import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'

/**
 * 应用骨架（M1 前端最小闭环的展示层起点）。
 * 后续按 DESIGN.md 逐页扩展：对话建项目 / 分镜表确认 / 审核台 / 合成。
 */
export default function App() {
  const { data: projects, isLoading, error } = useQuery({
    queryKey: ['projects'],
    queryFn: api.projects.list,
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
        <h2 className="mb-4 text-lg font-semibold">项目列表</h2>
        {isLoading && <p className="text-muted-foreground">加载中…</p>}
        {error && (
          <p className="text-destructive">
            后端未就绪（{String(error)}）。M1 前端骨架已可运行，REST 接口待后端实现。
          </p>
        )}
        {projects && projects.length === 0 && (
          <p className="text-muted-foreground">暂无项目。从「新建项目」开始对话建项目。</p>
        )}
        {projects && projects.length > 0 && (
          <ul className="space-y-2">
            {projects.map((p) => (
              <li key={p.id} className="rounded-lg border p-4">
                <div className="font-medium">{p.synopsis.slice(0, 60)}</div>
                <div className="mt-1 text-sm text-muted-foreground">
                  {p.mode === 'ONE_SHOT' ? '一镜到底' : '分镜'} · {p.aspectRatio} · {p.videoModel} ·{' '}
                  {p.state}
                </div>
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  )
}
