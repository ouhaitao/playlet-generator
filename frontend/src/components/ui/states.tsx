import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** 加载骨架：紧凑行状。 */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded bg-muted', className)} />
}

export function SkeletonRows({ rows = 3, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn('space-y-2', className)}>
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="h-8 w-full" />
      ))}
    </div>
  )
}

/** 空状态：统一图标位 + 文案 + 可选动作。 */
export function EmptyState({
  title,
  hint,
  action,
  className,
}: {
  title: string
  hint?: string
  action?: ReactNode
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-1.5 rounded-lg border border-dashed px-4 py-8 text-center',
        className,
      )}
    >
      <div className="text-sm text-muted-foreground">{title}</div>
      {hint && <div className="max-w-md text-xs text-muted-foreground/80">{hint}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}

/** 错误状态：红字 + 可选重试。 */
export function ErrorState({
  message,
  onRetry,
  className,
}: {
  message: string
  onRetry?: () => void
  className?: string
}) {
  return (
    <div
      className={cn(
        'flex items-center justify-between gap-3 rounded-lg border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-destructive',
        className,
      )}
    >
      <span className="break-all">{message}</span>
      {onRetry && (
        <button className="shrink-0 underline" onClick={onRetry}>
          重试
        </button>
      )}
    </div>
  )
}
