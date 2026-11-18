import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from '@/lib/api'
import { useProgressEvents, type ProgressEventMsg } from '@/hooks/useProgressEvents'
import DraftGate from '@/components/DraftGate'
import PromptFieldTools from '@/components/PromptFieldTools'
import WorkflowReadonlyView from '@/components/WorkflowReadonlyView'
import WorkflowParamPanel, {
  buildParams,
  type WorkflowParamValue,
} from '@/components/WorkflowParamPanel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Progress } from '@/components/ui/progress'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/states'
import type { ShotVersion } from '@/types/domain'

const KIND_LABEL: Record<string, string> = {
  FIRST_FRAME: '图片',
  VIDEO: '视频',
  TTS: '台词',
}

/**
 * 审核台（闸门②③）：某镜头的图片/视频版本列表 + 预览 + 通过/打回。
 * 生成进度经 WebSocket 实时更新；提交即返回，不阻塞页面。
 *
 * 生成参数模型：用户选择工作流（按 IMAGE/VIDEO 类型过滤）→ 面板按解析元数据
 * 渲染参数表单（默认值 = 导入时的原值）→ 提交 {workflow, params: {jsonPath: value}}，
 * 后端按 jsonPath 写回原始工作流并提交 ComfyUI。图生视频的图片地址由用户
 * 直接填进视频工作流的 LoadImage 参数，后端不推导。
 */
export default function ReviewPanel({ shotId }: { shotId: number }) {
  const qc = useQueryClient()
  const [progress, setProgress] = useState<Record<number, number>>({})
  const connected = useProgressEvents((e: ProgressEventMsg) => {
    if (e.shotId !== shotId) return
    // 合成事件（compose_*）是项目维度，versionId 为 null；本面板只处理镜头生成事件
    const vid = e.versionId
    if (vid == null) return
    if (e.type === 'progress') {
      setProgress((p) => ({ ...p, [vid]: e.percent ?? 0 }))
    } else if (e.type === 'completed' || e.type === 'failed') {
      setProgress((p) => {
        const next = { ...p }
        delete next[vid]
        return next
      })
      invalidateAll()
    }
  })

  const { data: shot } = useQuery({
    queryKey: ['shot', shotId],
    queryFn: () => api.shots.get(shotId),
    refetchInterval: 5000,
  })
  const { data: versions, isLoading } = useQuery({
    queryKey: ['versions', shotId],
    queryFn: () => api.versions.list(shotId),
    refetchInterval: 5000, // 兜底轮询（WS 断开时也能更新）
  })

  function invalidateAll() {
    qc.invalidateQueries({ queryKey: ['versions', shotId] })
    qc.invalidateQueries({ queryKey: ['shot', shotId] })
    qc.invalidateQueries({ queryKey: ['shots'] })
  }

  // 工作流选择 + 参数表单（图片/视频各自独立）
  const [imageWf, setImageWf] = useState<WorkflowParamValue>({ workflow: '', fields: {} })
  const [videoWf, setVideoWf] = useState<WorkflowParamValue>({ workflow: '', fields: {} })
  const { data: imageDetail } = useQuery({
    queryKey: ['workflow-detail', imageWf.workflow],
    queryFn: () => api.workflows.get(imageWf.workflow),
    enabled: !!imageWf.workflow,
  })
  const { data: videoDetail } = useQuery({
    queryKey: ['workflow-detail', videoWf.workflow],
    queryFn: () => api.workflows.get(videoWf.workflow),
    enabled: !!videoWf.workflow,
  })

  const genImage = useMutation({
    mutationFn: () =>
      api.generate.image(shotId, imageWf.workflow, buildParams(imageDetail, imageWf.fields) ?? {}),
    onSuccess: invalidateAll,
  })
  const genVideo = useMutation({
    mutationFn: () =>
      api.generate.video(shotId, videoWf.workflow, buildParams(videoDetail, videoWf.fields) ?? {}),
    onSuccess: invalidateAll,
  })
  const approve = useMutation({
    mutationFn: (versionId: number) => api.review.approve(versionId),
    onSuccess: invalidateAll,
  })
  const reject = useMutation({
    mutationFn: (vars: { versionId: number; feedback: string }) =>
      api.review.reject(vars.versionId, vars.feedback),
    onSuccess: invalidateAll,
  })
  const [rejecting, setRejecting] = useState<number | null>(null)
  const [feedback, setFeedback] = useState('')

  const firstFrames = (versions ?? []).filter((v) => v.kind === 'FIRST_FRAME')
  const videos = (versions ?? []).filter((v) => v.kind === 'VIDEO')
  const anyGenerating = (versions ?? []).some((v) => v.genState === 'GENERATING')

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold">镜头 #{shot?.ord ?? shotId} · 审核台</h3>
        <Badge tone={connected ? 'success' : 'outline'}>
          {connected ? '● 进度已连接' : '○ 进度未连接（轮询兜底）'}
        </Badge>
        {shot && (
          <span className="text-2xs text-muted-foreground">
            {shot.state}
            {shot.firstFrameImage ? ` · 图片入库 ${shot.firstFrameImage}` : ''}
          </span>
        )}
      </div>

      {/* ──  提示词确认门（§6.3）── */}
      <DraftGate shotId={shotId} />

      {/* ── 闸门②：图片 ── */}
      <Card>
        <CardHeader>
          <CardTitle>① 图片（闸门②）</CardTitle>
          <Button
            variant="primary"
            disabled={genImage.isPending || anyGenerating || !imageWf.workflow}
            title={imageWf.workflow ? '' : '请先选择图片工作流'}
            onClick={() => genImage.mutate()}
          >
            {firstFrames.length === 0 ? '生成图片' : '重roll图片'}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <WorkflowParamPanel
            type="IMAGE"
            value={imageWf}
            onChange={setImageWf}
            renderFieldExtra={(f, g) =>
              g.classType === 'CLIPTextEncode' && f.kind === 'STRING' ? (
                <PromptFieldTools
                  value={typeof imageWf.fields[f.jsonPath] === 'string' ? (imageWf.fields[f.jsonPath] as string) : ''}
                  onChange={(v) =>
                    setImageWf((prev) => ({ ...prev, fields: { ...prev.fields, [f.jsonPath]: v } }))
                  }
                  shot={shot}
                  model={imageDetail?.model}
                  versions={firstFrames}
                />
              ) : null
            }
          />
          {genImage.isError && <ErrorState message={String(genImage.error)} />}
          {isLoading && <SkeletonRows rows={1} />}
          {!isLoading && firstFrames.length === 0 && (
            <EmptyState title="尚未生成图片" className="py-6" />
          )}
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
            {firstFrames.map((v: ShotVersion) => (
              <VersionCard
                key={v.id}
                v={v}
                progress={progress[v.id]}
                onApprove={() => approve.mutate(v.id)}
                approvePending={approve.isPending}
                onStartReject={() => {
                  setRejecting(v.id)
                  setFeedback('')
                }}
                onCancelReject={() => setRejecting(null)}
                rejecting={rejecting === v.id}
                feedback={feedback}
                setFeedback={setFeedback}
                onReject={() => {
                  reject.mutate({ versionId: v.id, feedback })
                  setRejecting(null)
                }}
                rejectPending={reject.isPending}
              />
            ))}
          </div>
          {approve.isError && <ErrorState className="mt-3" message={String(approve.error)} />}
        </CardContent>
      </Card>

      {/* ── 闸门③：视频 ─ */}
      <Card>
        <CardHeader>
          <CardTitle>② 视频（闸门③）</CardTitle>
          <Button
            variant="primary"
            disabled={genVideo.isPending || anyGenerating || !videoWf.workflow}
            title={videoWf.workflow ? '' : '请先选择视频工作流'}
            onClick={() => genVideo.mutate()}
          >
            {videos.length === 0 ? '提交视频生成' : '重roll视频'}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-2xs text-muted-foreground">
            图生视频：把已生成图片的路径直接填进视频工作流的图片参数（如 LoadImage.image）。
          </p>
          <WorkflowParamPanel
            type="VIDEO"
            value={videoWf}
            onChange={setVideoWf}
            renderFieldExtra={(f, g) =>
              g.classType === 'CLIPTextEncode' && f.kind === 'STRING' ? (
                <PromptFieldTools
                  value={typeof videoWf.fields[f.jsonPath] === 'string' ? (videoWf.fields[f.jsonPath] as string) : ''}
                  onChange={(v) =>
                    setVideoWf((prev) => ({ ...prev, fields: { ...prev.fields, [f.jsonPath]: v } }))
                  }
                  shot={shot}
                  model={videoDetail?.model}
                  versions={videos}
                />
              ) : null
            }
          />
          {genVideo.isError && <ErrorState message={String(genVideo.error)} />}
          <div className="space-y-3">
            {videos.map((v: ShotVersion) => (
              <VersionCard
                key={v.id}
                v={v}
                progress={progress[v.id]}
                onApprove={() => approve.mutate(v.id)}
                approvePending={approve.isPending}
                onStartReject={() => {
                  setRejecting(v.id)
                  setFeedback('')
                }}
                onCancelReject={() => setRejecting(null)}
                rejecting={rejecting === v.id}
                feedback={feedback}
                setFeedback={setFeedback}
                onReject={() => {
                  reject.mutate({ versionId: v.id, feedback })
                  setRejecting(null)
                }}
                rejectPending={reject.isPending}
              />
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

/** 单个版本卡片：进度条 / 图片 / 视频预览 + 通过/打回。 */
function VersionCard({
  v,
  progress,
  onApprove,
  approvePending,
  onStartReject,
  onCancelReject,
  rejecting,
  feedback,
  setFeedback,
  onReject,
  rejectPending,
}: {
  v: ShotVersion
  progress?: number
  onApprove: () => void
  approvePending: boolean
  onStartReject: () => void
  onCancelReject: () => void
  rejecting: boolean
  feedback: string
  setFeedback: (s: string) => void
  onReject: () => void
  rejectPending: boolean
}) {
  const [lightbox, setLightbox] = useState(false)
  const [showWf, setShowWf] = useState(false)
  const [copied, setCopied] = useState(false)

  const mediaUrl = v.artifactPath ? `/media/comfy/${v.artifactPath}` : ''

  async function copyPath() {
    const path = v.artifactPath ?? ''
    if (!path) return
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(path)
      } else {
        // 非安全上下文降级：临时 textarea + execCommand
        const ta = document.createElement('textarea')
        ta.value = path
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        document.body.removeChild(ta)
      }
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="rounded-md border bg-card p-2.5">
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-1.5">
          <span className="truncate text-2xs text-muted-foreground">
            {KIND_LABEL[v.kind] ?? v.kind} v{v.versionNo} · {v.backend}
            {v.seed != null && ` · seed ${v.seed}`}
          </span>
          {v.artifactPath && (
            <Button variant="ghost" size="xs" onClick={copyPath} title="复制 artifactPath">
              {copied ? '已复制' : '复制地址'}
            </Button>
          )}
          {v.paramsJson && (
            <Button variant="ghost" size="xs" onClick={() => setShowWf(true)} title="查看该版本工作流参数">
              显示工作流
            </Button>
          )}
        </div>
        <Badge
          tone={
            v.reviewState === 'APPROVED'
              ? 'success'
              : v.reviewState === 'REJECTED'
                ? 'destructive'
                : 'outline'
          }
        >
          {v.reviewState === 'APPROVED'
            ? '已通过'
            : v.reviewState === 'REJECTED'
              ? '已打回'
              : '待审'}
        </Badge>
      </div>

      {v.genState === 'GENERATING' && (
        <div className="mt-2 space-y-1">
          <Progress value={progress ?? 0} />
          <div className="text-2xs text-muted-foreground">生成中 {Math.round(progress ?? 0)}%</div>
        </div>
      )}

      {v.genState === 'FAILED' && (
        <ErrorState className="mt-2" message={`生成失败：${v.errorMessage}`} />
      )}

      {v.genState === 'DONE' && v.artifactPath && (
        <>
          {v.kind === 'FIRST_FRAME' ? (
            <img
              className="mt-2 max-h-56 w-full cursor-zoom-in rounded border object-contain bg-muted/40"
              src={mediaUrl}
              alt={`图片 v${v.versionNo}`}
              onClick={() => setLightbox(true)}
            />
          ) : (
            <video
              className="mt-2 max-h-56 w-full rounded border bg-black"
              controls
              src={mediaUrl}
            />
          )}
          {v.reviewState === 'PENDING' && !rejecting && (
            <div className="mt-2 flex gap-1.5">
              <Button
                variant="success"
                size="xs"
                disabled={approvePending}
                onClick={onApprove}
              >
                通过
              </Button>
              <Button variant="outline" size="xs" onClick={onStartReject}>
                打回
              </Button>
            </div>
          )}
          {v.reviewState === 'PENDING' && rejecting && (
            <div className="mt-2 space-y-1.5">
              <Textarea
                rows={2}
                placeholder="不满意的原因（反馈给 agent 重优化）"
                value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
              />
              <div className="flex gap-1.5">
                <Button
                  variant="destructive"
                  size="xs"
                  disabled={rejectPending || !feedback.trim()}
                  onClick={onReject}
                >
                  确认打回
                </Button>
                <Button variant="ghost" size="xs" onClick={onCancelReject}>
                  取消
                </Button>
              </div>
            </div>
          )}
          {v.reviewState === 'REJECTED' && v.rejectReason && (
            <p className="mt-2 text-2xs text-muted-foreground">打回意见：{v.rejectReason}</p>
          )}
        </>
      )}

      {lightbox && (
        <LightboxDialog url={mediaUrl} onClose={() => setLightbox(false)} />
      )}
      {showWf && (
        <WorkflowDialog paramsJson={v.paramsJson} onClose={() => setShowWf(false)} />
      )}
    </div>
  )
}

/**
 * 原尺寸图片预览弹框：img 不缩放（原始尺寸），但弹框容器最大宽/高不超过浏览器
 * 短边（min(100vw,100vh) 再留边距），图片超出部分在容器内滚动。Esc / 点遮罩关闭。
 */
function LightboxDialog({ url, onClose }: { url: string; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={onClose}
    >
      <div
        className="flex max-h-[calc(min(100vw,100vh)-2rem)] max-w-[calc(min(100vw,100vh)-2rem)] flex-col rounded-lg border bg-card shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-2 border-b px-3 py-2">
          <span className="text-xs font-medium">原始尺寸预览</span>
          <Button variant="ghost" size="xs" onClick={onClose}>
            关闭
          </Button>
        </div>
        <div className="overflow-auto bg-muted/40 p-2">
          <img src={url} alt="原始尺寸图片" className="block" />
        </div>
      </div>
    </div>
  )
}

/** 「显示工作流」弹框：按版本留档 paramsJson 只读展示该次生成的工作流参数。 */
function WorkflowDialog({
  paramsJson,
  onClose,
}: {
  paramsJson?: string | null
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[8vh]"
      onClick={onClose}
    >
      <div
        className="max-h-[84vh] w-full max-w-2xl overflow-y-auto rounded-lg border bg-card p-4 shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-sm font-semibold">该版本的工作流参数（只读）</h3>
          <Button variant="ghost" size="xs" onClick={onClose}>
            关闭
          </Button>
        </div>
        <WorkflowReadonlyView paramsJson={paramsJson} />
      </div>
    </div>
  )
}
