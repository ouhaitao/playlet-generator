import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { AppShell, type NavKey } from '@/components/AppShell'
import ProjectDetail from '@/components/ProjectDetail'
import WorkflowLibrary from '@/components/WorkflowLibrary'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/states'

/**
 * 应用入口：项目列表 ↔ 项目详情（分镜 + 审核台）↔ 工作流库。
 * M1 用 state 路由，不引 react-router；外壳见 AppShell。
 */
export default function App() {
  const [page, setPage] = useState<NavKey>('projects')
  const [projectId, setProjectId] = useState<number | null>(null)
  const { data: projects, isLoading, error, refetch } = useQuery({
    queryKey: ['projects'],
    queryFn: api.projects.list,
    enabled: page === 'projects' && projectId == null,
  })

  return (
    <AppShell nav={page} onNav={(k) => { setPage(k); setProjectId(null) }}>
      {page === 'workflows' ? (
        <WorkflowLibrary />
      ) : projectId != null ? (
        <ProjectDetail projectId={projectId} onBack={() => setProjectId(null)} />
      ) : (
        <div className="space-y-3">
          <h2 className="text-sm font-semibold">项目列表</h2>
          {isLoading && <SkeletonRows rows={3} />}
          {error && <ErrorState message={`后端未就绪：${String(error)}`} onRetry={() => refetch()} />}
          {projects && projects.length === 0 && (
            <EmptyState
              title="暂无项目"
              hint="对话式建项目页面上线后，在这里用自然语言描述剧情即可创建。"
            />
          )}
          {projects && projects.length > 0 && (
            <ul className="grid gap-2 md:grid-cols-2">
              {projects.map((p) => (
                <li key={p.id}>
                  <Card
                    className="cursor-pointer p-3 transition-colors hover:border-primary/50"
                    onClick={() => setProjectId(p.id)}
                  >
                    <div className="line-clamp-2 text-sm font-medium">{p.synopsis}</div>
                    <div className="mt-2 flex flex-wrap items-center gap-1.5">
                      <Badge tone="primary">
                        {p.mode === 'ONE_SHOT' ? '一镜到底' : '分镜'}
                      </Badge>
                      <Badge tone="outline">{p.aspectRatio}</Badge>
                      <Badge tone="outline">{p.videoModel}</Badge>
                      <Badge>{p.state}</Badge>
                    </div>
                  </Card>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </AppShell>
  )
}
