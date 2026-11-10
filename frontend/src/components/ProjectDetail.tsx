import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import ReviewPanel from '@/components/ReviewPanel'
import type { Shot } from '@/types/domain'

const STATE_LABEL: Record<string, string> = {
  PENDING: '待生成',
  FIRST_FRAME_GENERATING: '首帧生成中',
  FIRST_FRAME_REVIEW: '首帧待审',
  VIDEO_GENERATING: '视频生成中',
  VIDEO_REVIEW: '视频待审',
  TTS_GENERATING: '台词生成中',
  TTS_REVIEW: '台词待审',
  APPROVED: '已通过',
  ROLLED_BACK: '已回退',
}

/** 项目详情：分镜列表 + 选中镜头的审核台。 */
export default function ProjectDetail({
  projectId,
  onBack,
}: {
  projectId: number
  onBack: () => void
}) {
  const { data: project } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.projects.get(projectId),
  })
  const { data: shots } = useQuery({
    queryKey: ['shots', projectId],
    queryFn: () => api.shots.list(projectId),
    refetchInterval: 5000,
  })
  const [selected, setSelected] = useState<number | null>(null)

  return (
    <div className="grid grid-cols-[320px_1fr] gap-6">
      <aside className="space-y-2">
        <button className="mb-2 text-sm text-primary" onClick={onBack}>
          ← 项目列表
        </button>
        {project && (
          <div className="rounded-lg border p-3 text-sm">
            <div className="font-medium">{project.synopsis.slice(0, 80)}</div>
            <div className="mt-1 text-xs text-muted-foreground">
              {project.mode === 'ONE_SHOT' ? '一镜到底' : '分镜'} · {project.aspectRatio} ·{' '}
              {project.videoModel}
            </div>
          </div>
        )}
        <h3 className="pt-2 text-sm font-semibold">分镜</h3>
        {(shots ?? []).map((s: Shot) => (
          <button
            key={s.id}
            onClick={() => setSelected(s.id)}
            className={`block w-full rounded-lg border p-3 text-left text-sm transition ${
              selected === s.id ? 'border-primary bg-primary/5' : 'hover:bg-muted'
            }`}
          >
            <div className="flex justify-between">
              <span className="font-medium">#{s.ord}</span>
              <span className="text-xs text-muted-foreground">
                {STATE_LABEL[s.state] ?? s.state}
              </span>
            </div>
            <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">
              {s.visualPrompt || '（提示词待确认）'}
            </div>
          </button>
        ))}
        {(shots ?? []).length === 0 && (
          <p className="text-xs text-muted-foreground">暂无分镜。</p>
        )}
      </aside>

      <main>
        {selected != null ? (
          <ReviewPanel shotId={selected} />
        ) : (
          <p className="text-sm text-muted-foreground">选择左侧镜头进行审核。</p>
        )}
      </main>
    </div>
  )
}
