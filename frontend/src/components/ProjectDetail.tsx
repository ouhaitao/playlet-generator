import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { api } from '@/lib/api'
import ReviewPanel from '@/components/ReviewPanel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { EmptyState, SkeletonRows } from '@/components/ui/states'
import type { Shot, ShotState } from '@/types/domain'
import { cn } from '@/lib/utils'

const STATE_LABEL: Record<ShotState, string> = {
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

const STATE_TONE: Record<ShotState, 'neutral' | 'primary' | 'success' | 'warning'> = {
  PENDING: 'neutral',
  FIRST_FRAME_GENERATING: 'primary',
  FIRST_FRAME_REVIEW: 'warning',
  VIDEO_GENERATING: 'primary',
  VIDEO_REVIEW: 'warning',
  TTS_GENERATING: 'primary',
  TTS_REVIEW: 'warning',
  APPROVED: 'success',
  ROLLED_BACK: 'neutral',
}

/** 项目详情：分镜列表（左）+ 选中镜头的审核台（右）。 */
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
  const { data: shots, isLoading } = useQuery({
    queryKey: ['shots', projectId],
    queryFn: () => api.shots.list(projectId),
    refetchInterval: 5000,
  })
  const [selected, setSelected] = useState<number | null>(null)

  return (
    <div className="grid grid-cols-[280px_1fr] gap-4">
      <aside className="space-y-2">
        <Button variant="ghost" size="xs" onClick={onBack}>
          <ArrowLeft size={13} /> 项目列表
        </Button>
        {project && (
          <Card className="p-3">
            <div className="line-clamp-3 text-xs leading-relaxed">{project.synopsis}</div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              <Badge tone="primary">
                {project.mode === 'ONE_SHOT' ? '一镜到底' : '分镜'}
              </Badge>
              <Badge tone="outline">{project.aspectRatio}</Badge>
              <Badge tone="outline">{project.videoModel}</Badge>
            </div>
          </Card>
        )}
        <div className="pt-1 text-2xs font-medium uppercase tracking-wide text-muted-foreground">
          分镜
        </div>
        {isLoading && <SkeletonRows rows={4} />}
        {(shots ?? []).map((s: Shot) => (
          <button
            key={s.id}
            onClick={() => setSelected(s.id)}
            className={cn(
              'block w-full rounded-md border px-2.5 py-2 text-left transition-colors',
              selected === s.id
                ? 'border-primary/60 bg-primary/10'
                : 'border-transparent hover:bg-muted',
            )}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-semibold">#{s.ord}</span>
              <Badge tone={STATE_TONE[s.state] ?? 'neutral'}>
                {STATE_LABEL[s.state] ?? s.state}
              </Badge>
            </div>
            <div className="mt-1 line-clamp-2 text-2xs leading-relaxed text-muted-foreground">
              {s.visualPrompt || '（提示词待确认）'}
            </div>
          </button>
        ))}
        {(shots ?? []).length === 0 && !isLoading && (
          <EmptyState title="暂无分镜" className="py-6" />
        )}
      </aside>

      <main>
        {selected != null ? (
          <ReviewPanel shotId={selected} />
        ) : (
          <EmptyState title="选择左侧镜头进入审核台" className="mt-8" />
        )}
      </main>
    </div>
  )
}
