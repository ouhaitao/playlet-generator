import { useEffect, useMemo, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type WorkflowDetail, type WorkflowFieldParam, type WorkflowNodeGroup, type WorkflowType } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Input, Textarea } from '@/components/ui/input'
import { ErrorState, SkeletonRows } from '@/components/ui/states'

const SELECT_CLS =
  'h-8 w-full rounded-md border border-input bg-card px-2 text-sm ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50'

/** 提示词节点类型：展示时排最前、输入框占满整行（审核台按钮区也挂在它下面）。 */
export const CLIP_TEXT_ENCODE = 'CLIPTextEncode'

export function isClipTextGroup(g: WorkflowNodeGroup): boolean {
  return g.classType === CLIP_TEXT_ENCODE
}

/** CLIPTextEncode 节点组排到最前（多个之间保持原相对顺序，其余组顺序不变）。 */
export function sortGroupsClipFirst(groups: WorkflowNodeGroup[]): WorkflowNodeGroup[] {
  return [...groups.filter(isClipTextGroup), ...groups.filter((g) => !isClipTextGroup(g))]
}

/** 受控值：选中的工作流 + 用户填写的参数（jsonPath → 原始字符串/布尔）。 */
export interface WorkflowParamValue {
  workflow: string
  /** jsonPath → 值；提交时由 buildParams 按 kind 转成 JSON 类型 */
  fields: Record<string, string | boolean>
}

/**
 * 把表单值转成提交给后端的 params（jsonPath → String/Number/Boolean）。
 * 空字符串的 NUMBER/STRING 字段跳过 = 保留工作流原文默认值。
 */
export function buildParams(
  detail: WorkflowDetail | undefined,
  fields: Record<string, string | boolean>,
): Record<string, unknown> | null {
  if (!detail) return null
  const params: Record<string, unknown> = {}
  for (const g of detail.groups) {
    for (const f of g.fields) {
      const v = fields[f.jsonPath]
      if (typeof v === 'boolean') {
        params[f.jsonPath] = v
      } else if (v !== undefined && v !== '') {
        params[f.jsonPath] = f.kind === 'NUMBER' ? Number(v) : v
      }
    }
  }
  return params
}

/**
 * 工作流选择 + 参数表单（审核台内嵌）：
 * 下拉框按类型（IMAGE/VIDEO）过滤工作流库 → 选中后 GET /api/workflows/{name}
 * 拉取解析元数据 → 按节点分组渲染所有可填参数（defaultValue 预填）。
 * 用户填完后父组件经 buildParams 收集 {jsonPath: value} 提交生成。
 *
 * <p>CLIPTextEncode（提示词）节点组排在最前、输入框占满整行；`renderFieldExtra`
 * 允许父组件（审核台）在每个提示词字段下方注入按钮区（填入分镜提示词/翻译/生成）。
 */
export default function WorkflowParamPanel({
  type,
  value,
  onChange,
  renderFieldExtra,
}: {
  type: WorkflowType
  value: WorkflowParamValue
  onChange: (v: WorkflowParamValue) => void
  /** 在某字段编辑器下方追加自定义内容（审核台提示词工具按钮区）。 */
  renderFieldExtra?: (f: WorkflowFieldParam, group: WorkflowNodeGroup) => ReactNode
}) {
  const { data: entries } = useQuery({
    queryKey: ['workflows', type],
    queryFn: () => api.workflows.list(type),
  })
  const { data: detail, isLoading, error } = useQuery({
    queryKey: ['workflow-detail', value.workflow],
    queryFn: () => api.workflows.get(value.workflow),
    enabled: !!value.workflow,
  })

  // 切换工作流时用 defaultValue 重置表单
  useEffect(() => {
    if (!detail || detail.name !== value.workflow) return
    const fields: Record<string, string | boolean> = {}
    for (const g of detail.groups) {
      for (const f of g.fields) {
        fields[f.jsonPath] =
          typeof f.defaultValue === 'boolean' ? f.defaultValue : String(f.defaultValue ?? '')
      }
    }
    // 仅在已选工作流的元数据首次就绪时初始化（避免覆盖用户正在编辑的值）
    if (Object.keys(value.fields).length === 0) {
      onChange({ workflow: value.workflow, fields })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail?.name])

  const options = useMemo(() => entries ?? [], [entries])
  /** CLIPTextEncode 组排最前展示（不改变 detail.groups 本身，避免影响 buildParams 顺序无关逻辑）。 */
  const orderedGroups = useMemo(
    () => (detail ? sortGroupsClipFirst(detail.groups) : []),
    [detail],
  )

  function setField(path: string, v: string | boolean) {
    onChange({ workflow: value.workflow, fields: { ...value.fields, [path]: v } })
  }

  function resetToDefaults() {
    if (!detail) return
    const fields: Record<string, string | boolean> = {}
    for (const g of detail.groups) {
      for (const f of g.fields) {
        fields[f.jsonPath] =
          typeof f.defaultValue === 'boolean' ? f.defaultValue : String(f.defaultValue ?? '')
      }
    }
    onChange({ workflow: value.workflow, fields })
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <select
          className={`${SELECT_CLS} w-64`}
          value={value.workflow}
          onChange={(e) => onChange({ workflow: e.target.value, fields: {} })}
        >
          <option value="">— 选择工作流 —</option>
          {options.map((w) => (
            <option key={w.name} value={w.name}>
              {w.model ? `${w.name}（${w.model}）` : w.name}
            </option>
          ))}
        </select>
        {detail && (
          <>
            {detail.model && <Badge tone="primary">{detail.model}</Badge>}
            <Badge tone="outline">{detail.groups.length} 节点</Badge>
            <Badge tone="outline">
              {detail.groups.reduce((n, g) => n + g.fields.length, 0)} 参数
            </Badge>
            <button className="text-2xs text-muted-foreground underline" onClick={resetToDefaults}>
              恢复默认值
            </button>
          </>
        )}
      </div>

      {options.length === 0 && (
        <p className="text-2xs text-muted-foreground">
          暂无{type === 'IMAGE' ? '图片' : '视频'}工作流，请先在「工作流库」导入。
        </p>
      )}
      {error && <ErrorState message={`读取工作流元数据失败：${String(error)}`} />}
      {isLoading && value.workflow && <SkeletonRows rows={3} />}

      {detail && detail.groups.length === 0 && (
        <p className="text-2xs text-muted-foreground">该工作流没有可人工填写的参数。</p>
      )}

      {detail && orderedGroups.length > 0 && (
        <div className="max-h-96 space-y-3 overflow-y-auto rounded-md border bg-muted/20 p-2.5">
          {orderedGroups.map((g) => {
            const isClip = isClipTextGroup(g)
            return (
              <details key={g.nodeId} open={isClip || g.fields.length <= 6}>
                <summary className="cursor-pointer text-xs font-medium">
                  <span className="font-mono text-2xs text-muted-foreground">[{g.nodeId}]</span>{' '}
                  {g.nodeTitle}
                  {g.nodeTitle !== g.classType && (
                    <span className="ml-1 font-mono text-2xs text-muted-foreground">
                      {g.classType}
                    </span>
                  )}
                </summary>
                {/* 提示词节点单列全宽，其余节点两列紧凑 */}
                <div className={isClip ? 'mt-1.5 grid grid-cols-1 gap-2' : 'mt-1.5 grid grid-cols-1 gap-2 md:grid-cols-2'}>
                  {g.fields.map((f) => (
                    <FieldEditor
                      key={f.jsonPath}
                      f={f}
                      value={value.fields[f.jsonPath]}
                      onChange={(v) => setField(f.jsonPath, v)}
                      fullwidth={isClip}
                      extra={renderFieldExtra?.(f, g)}
                    />
                  ))}
                </div>
              </details>
            )
          })}
        </div>
      )}
    </div>
  )
}

/** 单个参数编辑器：按 kind 选控件；长文本/提示词用多行。
 *  fullwidth=true（CLIPTextEncode）时控件占满整行；extra 渲染在控件下方（按钮区）。 */
function FieldEditor({
  f,
  value,
  onChange,
  fullwidth = false,
  extra,
}: {
  f: WorkflowFieldParam
  value: string | boolean | undefined
  onChange: (v: string | boolean) => void
  fullwidth?: boolean
  extra?: ReactNode
}) {
  const label = (
    <div className="text-2xs text-muted-foreground">
      <span className="font-mono">{f.inputName}</span>
      <span className="ml-1 opacity-60">{f.kind}</span>
    </div>
  )

  if (f.kind === 'BOOLEAN') {
    return (
      <div className={extra ? 'space-y-1' : undefined}>
        <label className="flex items-center gap-2 self-center text-xs">
          <input
            type="checkbox"
            checked={value === true}
            onChange={(e) => onChange(e.target.checked)}
          />
          <span className="font-mono text-2xs">{f.inputName}</span>
        </label>
        {extra}
      </div>
    )
  }

  const longText =
    f.kind === 'STRING' &&
    (/prompt|text/i.test(f.inputName) ||
      String(value ?? '').length > 60 ||
      String(value ?? '').includes('\n'))

  return (
    <div className={extra ? 'block space-y-1' : 'block space-y-0.5'}>
      <label className="block space-y-0.5">
        {label}
        {longText ? (
          <Textarea
            rows={fullwidth ? 5 : 3}
            className="w-full text-xs"
            value={typeof value === 'string' ? value : ''}
            onChange={(e) => onChange(e.target.value)}
          />
        ) : (
          <Input
            type={f.kind === 'NUMBER' ? 'number' : 'text'}
            step={f.kind === 'NUMBER' ? 'any' : undefined}
            className="h-7 w-full text-xs"
            title={f.jsonPath}
            value={typeof value === 'string' ? value : ''}
            onChange={(e) => onChange(e.target.value)}
          />
        )}
      </label>
      {extra}
    </div>
  )
}
