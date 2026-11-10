import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '@/lib/api'
import { useProgressEvents, type ProgressEventMsg } from '@/hooks/useProgressEvents'
import type { ShotVersion } from '@/types/domain'

/**
 * 审核台（闸门②③）：某镜头的版本列表 + 视频预览 + 通过/重生成。
 * 生成进度经 WebSocket 实时更新；提交即返回，不阻塞页面。
 */
export default function ReviewPanel({ shotId }: { shotId: number }) {
  const qc = useQueryClient()
  const [progress, setProgress] = useState<Record<number, number>>({})
  const connected = useProgressEvents((e: ProgressEventMsg) => {
    if (e.shotId !== shotId) return
    if (e.type === 'progress') {
      setProgress((p) => ({ ...p, [e.versionId]: e.percent ?? 0 }))
    } else if (e.type === 'completed' || e.type === 'failed') {
      setProgress((p) => {
        const next = { ...p }
        delete next[e.versionId]
        return next
      })
      qc.invalidateQueries({ queryKey: ['versions', shotId] })
      qc.invalidateQueries({ queryKey: ['shots'] })
    }
  })

  const { data: versions } = useQuery({
    queryKey: ['versions', shotId],
    queryFn: () => api.versions.list(shotId),
    refetchInterval: 5000, // 兜底轮询（WS 断开时也能更新）
  })

  const approve = useMutation({
    mutationFn: (versionId: number) => api.review.approve(versionId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['versions', shotId] })
      qc.invalidateQueries({ queryKey: ['shots'] })
    },
  })

  const regenerate = useMutation({
    mutationFn: () => api.generate.video(shotId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['versions', shotId] }),
  })

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <h3 className="font-semibold">镜头 {shotId} · 版本审核</h3>
        <span className={`text-xs ${connected ? 'text-green-600' : 'text-muted-foreground'}`}>
          {connected ? '● 进度已连接' : '○ 进度未连接（轮询兜底）'}
        </span>
        <button
          className="rounded bg-primary px-3 py-1 text-sm text-primary-foreground disabled:opacity-50"
          disabled={regenerate.isPending}
          onClick={() => regenerate.mutate()}
        >
          {regenerate.isPending ? '提交中…' : '重新生成'}
        </button>
      </div>

      {regenerate.isError && (
        <p className="text-sm text-destructive">{String(regenerate.error)}</p>
      )}

      <ul className="space-y-3">
        {(versions ?? []).map((v: ShotVersion) => (
          <li key={v.id} className="rounded-lg border p-3">
            <div className="flex items-center justify-between">
              <div className="text-sm">
                v{v.versionNo} · {v.backend} · {v.genState}
                {v.seed != null && ` · seed ${v.seed}`}
              </div>
              <div className="text-xs text-muted-foreground">{v.reviewState}</div>
            </div>

            {v.genState === 'GENERATING' && (
              <div className="mt-2">
                <div className="h-2 w-full overflow-hidden rounded bg-muted">
                  <div
                    className="h-full bg-primary transition-all"
                    style={{ width: `${progress[v.id] ?? 0}%` }}
                  />
                </div>
                <div className="mt-1 text-xs text-muted-foreground">
                  生成中 {Math.round(progress[v.id] ?? 0)}%
                </div>
              </div>
            )}

            {v.genState === 'FAILED' && (
              <p className="mt-2 text-xs text-destructive">{v.errorMessage}</p>
            )}

            {v.genState === 'DONE' && v.artifactPath && (
              <>
                <video
                  className="mt-2 max-h-64 w-full rounded bg-black"
                  controls
                  src={`/media/comfy/${v.artifactPath}`}
                />
                <div className="mt-2 flex gap-2">
                  <button
                    className="rounded bg-green-600 px-3 py-1 text-sm text-white disabled:opacity-50"
                    disabled={v.reviewState === 'APPROVED' || approve.isPending}
                    onClick={() => approve.mutate(v.id)}
                  >
                    {v.reviewState === 'APPROVED' ? '已通过' : '通过'}
                  </button>
                </div>
              </>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
