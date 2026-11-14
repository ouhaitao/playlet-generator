import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Clapperboard } from 'lucide-react'
import { api } from '@/lib/api'
import { useProgressEvents, type ProgressEventMsg } from '@/hooks/useProgressEvents'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { ErrorState } from '@/components/ui/states'
import type { Shot } from '@/types/domain'

const STAGE_LABEL: Record<string, string> = {
  concat: '拼接分镜',
  mix: '三层混音',
  subtitle: '烧录字幕',
}

/**
 * 合成成片面板（BACKLOG ②「合成 E2E + 入口」）。
 *
 * 门控：多镜全 APPROVED 才可一键出片（与后端 submit 校验一致，前端提前禁用给提示）。
 * 进度复用 WS /ws/events 的 compose_* 事件；完成后播放器指向 /media/store/<finalVideoPath>。
 */
export default function ComposePanel({
  projectId,
  shots,
  finalVideoPath,
  composedAt,
  composeError,
}: {
  projectId: number
  shots: Shot[]
  finalVideoPath?: string
  composedAt?: string
  composeError?: string
}) {
  const qc = useQueryClient()
  const [percent, setPercent] = useState(0)
  const [stage, setStage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [justComposed, setJustComposed] = useState(false)

  const connected = useProgressEvents((e: ProgressEventMsg) => {
    if (e.projectId !== projectId) return
    if (e.type === 'compose_started') {
      setBusy(true)
      setPercent(0)
      setStage(null)
    } else if (e.type === 'compose_progress') {
      setBusy(true)
      setPercent(e.percent ?? 0)
      setStage(e.message ?? null)
    } else if (e.type === 'compose_completed' || e.type === 'compose_failed') {
      setBusy(false)
      setStage(null)
      if (e.type === 'compose_completed') {
        setPercent(100)
        setJustComposed(true)
      }
      // 成品路径/失败原因落库后回读项目
      qc.invalidateQueries({ queryKey: ['project', projectId] })
    }
  })

  // 组件挂载时若已有成品，视为已完成态（不显示进行中的进度条）
  useEffect(() => {
    if (finalVideoPath && !busy) setPercent(100)
  }, [finalVideoPath, busy])

  const total = shots.length
  const approved = shots.filter((s) => s.state === 'APPROVED').length
  const allApproved = total > 0 && approved === total
  const playable = finalVideoPath ? `/media/store/${finalVideoPath}` : null

  const startCompose = useMutation({
    mutationFn: () => api.projects.compose(projectId),
    onMutate: () => {
      setBusy(true)
      setPercent(0)
      setJustComposed(false)
    },
    onError: () => setBusy(false),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5">
          <Clapperboard size={15} /> ③ 合成成片
        </CardTitle>
        <Badge tone={connected ? 'success' : 'outline'}>
          {connected ? '● 进度已连接' : '○ 进度未连接（轮询兜底）'}
        </Badge>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-2xs text-muted-foreground">
            分镜审核进度：{approved}/{total} 已通过
          </span>
          {!allApproved && total > 0 && (
            <span className="text-2xs text-warning">
              需全部分镜 APPROVED 后才能一键出片
            </span>
          )}
          {total === 0 && (
            <span className="text-2xs text-muted-foreground">尚无分镜</span>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="primary"
            disabled={!allApproved || busy || startCompose.isPending}
            onClick={() => startCompose.mutate()}
            title={allApproved ? '' : '需先让所有镜头通过审核'}
          >
            {finalVideoPath ? '重新合成成片' : '合成成片'}
          </Button>
          {composedAt && !busy && (
            <span className="text-2xs text-muted-foreground">上次合成：{composedAt}</span>
          )}
          {playable && !busy && (
            <a
              href={playable}
              download
              className="text-2xs text-primary underline underline-offset-2"
            >
              下载成片
            </a>
          )}
        </div>

        {startCompose.isError && (
          <ErrorState message={String(startCompose.error)} />
        )}
        {!startCompose.isError && composeError && !busy && (
          <ErrorState message={`合成失败：${composeError}`} />
        )}

        {busy && (
          <div className="space-y-1">
            <Progress value={percent} />
            <div className="text-2xs text-muted-foreground">
              合成中 {Math.round(percent)}%
              {stage ? ` · ${STAGE_LABEL[stage] ?? stage}` : ''}
            </div>
          </div>
        )}

        {playable && !busy && (
          <div className="space-y-1.5">
            {justComposed && (
              <div className="text-2xs text-success">✓ 合成完成，可播放成片</div>
            )}
            <video
              className="max-h-[420px] w-full rounded-md border bg-black"
              controls
              src={playable}
            />
          </div>
        )}
      </CardContent>
    </Card>
  )
}
