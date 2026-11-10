import { useEffect, useRef, useState } from 'react'

export interface ProgressEventMsg {
  type: 'started' | 'progress' | 'completed' | 'failed'
  versionId: number
  shotId: number
  comfyPromptId: string
  percent?: number
  message?: string
  artifactPath?: string
}

/**
 * 订阅后端 /ws/events 生成进度。自动重连。
 * onEvent 回调收到 ProgressEventMsg。
 */
export function useProgressEvents(onEvent: (e: ProgressEventMsg) => void) {
  const cbRef = useRef(onEvent)
  cbRef.current = onEvent
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    let ws: WebSocket | null = null
    let retry = 2000
    let closed = false

    const connect = () => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      ws = new WebSocket(`${proto}://${location.host}/ws/events`)
      ws.onopen = () => {
        setConnected(true)
        retry = 2000
      }
      ws.onmessage = (ev) => {
        try {
          cbRef.current(JSON.parse(ev.data) as ProgressEventMsg)
        } catch {
          // ignore malformed
        }
      }
      ws.onclose = () => {
        setConnected(false)
        if (!closed) setTimeout(connect, retry)
        retry = Math.min(retry * 2, 30000)
      }
    }
    connect()
    return () => {
      closed = true
      ws?.close()
    }
  }, [])

  return connected
}
