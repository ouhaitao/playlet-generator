import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type WorkflowImportReport, type WorkflowType } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/states'

const SELECT_CLS =
  'h-8 rounded-md border border-input bg-card px-2 text-sm ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50'

const TYPE_LABEL: Record<WorkflowType, string> = { IMAGE: '图片', VIDEO: '视频' }

/**
 * 工作流库页：列出内置/用户模板，上传导入 ComfyUI API 格式 JSON。
 * 导入管线在后端：按节点分组提取所有可人工填写的字面量参数（跳过连线）→
 * 原文落盘 <name>.json + 解析元数据落盘 <name>.meta.json（失败自动回滚）。
 * 纯 JSON 解析，不需要 ComfyUI 在线。
 */
export default function WorkflowLibrary() {
  const qc = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const [name, setName] = useState('')
  const [type, setType] = useState<WorkflowType>('IMAGE')
  const [model, setModel] = useState('')
  const [report, setReport] = useState<WorkflowImportReport | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  const { data: entries, isLoading, error, refetch } = useQuery({
    queryKey: ['workflows'],
    queryFn: () => api.workflows.list(),
  })

  const importMutation = useMutation({
    mutationFn: async (vars: { name: string; type: WorkflowType; model: string; content: string }) =>
      api.workflows.import(vars.name, vars.type, vars.model, vars.content),
    onSuccess: (r) => {
      setReport(r)
      setFormError(null)
      if (r.ok) qc.invalidateQueries({ queryKey: ['workflows'] })
    },
    onError: (e) => {
      setReport(null)
      setFormError(String((e as Error).message || e))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (n: string) => api.workflows.remove(n),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
    onError: (e) => setFormError(String((e as Error).message || e)),
  })

  async function onFileChosen(file: File | undefined) {
    if (!file) return
    setFormError(null)
    // 模型必填（提示词优化 agent 依赖它做模型级优化），未填先提示再清掉 input 便于重选
    if (!model.trim()) {
      setFormError('请先填写「模型」（该工作流使用的模型名）再选择文件')
      if (fileRef.current) fileRef.current.value = ''
      return
    }
    // 默认模板名 = 文件名（去 .json）
    if (!name.trim()) {
      setName(file.name.replace(/\.json$/i, '').replace(/[^A-Za-z0-9_/-]/g, '_'))
    }
    const content = await file.text()
    importMutation.mutate({
      name: name.trim() || file.name.replace(/\.json$/i, ''),
      type,
      model: model.trim(),
      content,
    })
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>导入工作流</CardTitle>
          <Badge tone="outline">离线解析</Badge>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-xs leading-relaxed text-muted-foreground">
            在 ComfyUI 里把想要的流程全部填好（模型、尺寸、提示词随意），开启 Dev Mode 后用
            「Save (API Format)」导出 JSON，再从这里上传并填写类型（图片/视频）与模型名。
            后端按节点分组提取所有可人工填写的字面量参数（跳过连线），生成时在审核台按元数据表单填值；
            模型名供提示词优化 agent 做模型级优化。
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1">
              <div className="text-2xs text-muted-foreground">类型</div>
              <select
                className={SELECT_CLS}
                value={type}
                onChange={(e) => setType(e.target.value as WorkflowType)}
              >
                <option value="IMAGE">图片</option>
                <option value="VIDEO">视频</option>
              </select>
            </label>
            <label className="space-y-1">
              <div className="text-2xs text-muted-foreground">
                模型 <span className="text-destructive">*</span>
              </div>
              <Input
                className="w-44"
                placeholder="如 anima / minimax_h3"
                title="该工作流使用的模型名，提示词优化 agent 据此做模型级优化"
                value={model}
                onChange={(e) => setModel(e.target.value)}
              />
            </label>
            <label className="space-y-1">
              <div className="text-2xs text-muted-foreground">
                模板名（可带一层目录，如 sd/myflow）
              </div>
              <Input
                className="w-56"
                placeholder="myflow"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <input
              ref={fileRef}
              type="file"
              accept=".json,application/json"
              className="block text-xs text-muted-foreground file:mr-2 file:rounded-md file:border-0 file:bg-secondary file:px-2.5 file:py-1.5 file:text-xs file:font-medium file:text-secondary-foreground hover:file:bg-secondary/80"
              onChange={(e) => onFileChosen(e.target.files?.[0])}
            />
            {importMutation.isPending && (
              <span className="text-2xs text-muted-foreground">导入中…</span>
            )}
          </div>
          {formError && <ErrorState message={formError} />}
        </CardContent>
      </Card>

      {report && (
        <Card className={report.ok ? 'border-success/50' : 'border-destructive/50'}>
          <CardHeader>
            <CardTitle className={report.ok ? 'text-success' : 'text-destructive'}>
              {report.ok ? `✅ 导入成功：${report.name}` : `❌ 导入被拒绝：${report.name}`}
            </CardTitle>
            <Button variant="ghost" size="xs" onClick={() => setReport(null)}>
              关闭
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {!report.ok && report.problems.length > 0 && (
              <ul className="list-disc space-y-1 pl-5 text-xs text-destructive">
                {report.problems.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            )}
            {report.ok && (
              <div className="flex flex-wrap items-center gap-1.5 text-xs">
                <Badge tone="primary">{TYPE_LABEL[report.type ?? 'IMAGE']}</Badge>
                {report.model && <Badge tone="outline">{report.model}</Badge>}
                <span className="text-muted-foreground">
                  提取到 {report.nodeCount} 个节点 / {report.fieldCount} 个可填参数
                </span>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>模板库</CardTitle>
          <span className="text-2xs text-muted-foreground">
            {entries ? `${entries.length} 个模板` : ''}
          </span>
        </CardHeader>
        <CardContent>
          {isLoading && <SkeletonRows rows={4} />}
          {error && <ErrorState message={`读取模板库失败：${String(error)}`} onRetry={() => refetch()} />}
          {entries && entries.length === 0 && <EmptyState title="暂无模板" />}
          {entries && entries.length > 0 && (
            <Table>
              <THead>
                <TR>
                  <TH className="w-[30%]">模板名</TH>
                  <TH className="w-[8%]">类型</TH>
                  <TH className="w-[14%]">模型</TH>
                  <TH className="w-[14%]">来源</TH>
                  <TH className="w-[18%]">导入时间</TH>
                  <TH className="w-[8%]">大小</TH>
                  <TH className="w-[8%]">操作</TH>
                </TR>
              </THead>
              <TBody>
                {entries.map((e) => (
                  <TR key={e.source + ':' + e.name}>
                    <TD className="font-mono text-xs">{e.name}</TD>
                    <TD>
                      {e.type ? (
                        <Badge tone={e.type === 'IMAGE' ? 'primary' : 'outline'}>
                          {TYPE_LABEL[e.type]}
                        </Badge>
                      ) : (
                        <span className="text-2xs text-muted-foreground">无元数据</span>
                      )}
                    </TD>
                    <TD className="font-mono text-2xs">
                      {e.model || <span className="text-muted-foreground">—</span>}
                    </TD>
                    <TD>
                      <Badge tone={e.source === 'builtin' ? 'outline' : 'primary'}>
                        {e.source === 'builtin' ? '内置' : '用户导入'}
                      </Badge>
                      {e.overridden && (
                        <Badge tone="warning" className="ml-1">
                          覆盖内置
                        </Badge>
                      )}
                    </TD>
                    <TD className="text-2xs text-muted-foreground">
                      {e.importedAt ? new Date(e.importedAt).toLocaleString() : '—'}
                    </TD>
                    <TD className="text-2xs text-muted-foreground">
                      {e.bytes ? `${(e.bytes / 1024).toFixed(1)} KB` : '—'}
                    </TD>
                    <TD>
                      {e.source === 'user' && (
                        <Button
                          variant="ghost"
                          size="xs"
                          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                          disabled={deleteMutation.isPending}
                          onClick={() => {
                            if (confirm(`删除用户模板 ${e.name}？`)) deleteMutation.mutate(e.name)
                          }}
                        >
                          删除
                        </Button>
                      )}
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
