import { cn } from '@/lib/utils'

/** 细进度条（生成中）。 */
export function Progress({
  value,
  className,
}: {
  value: number
  className?: string
}) {
  const pct = Math.max(0, Math.min(100, value))
  return (
    <div
      className={cn('h-1.5 w-full overflow-hidden rounded-full bg-muted', className)}
      role="progressbar"
      aria-valuenow={pct}
    >
      <div
        className="h-full rounded-full bg-primary transition-[width] duration-300"
        style={{ width: `${pct}%` }}
      />
    </div>
  )
}
