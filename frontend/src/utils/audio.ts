//* Web Audio 转码: 任意浏览器可解码的音频 (webm/mp3/wav/ogg) → 16kHz 单声道 PCM16 WAV.
//* 后端 /voice/upload 严格校验 RIFF/WAVE 头且仅接受 16kHz 单声道 PCM16;
//* MediaRecorder 只能产出 webm 等容器, 永远录不出原始 WAV, 故须经此转换.

const TARGET_SAMPLE_RATE = 16000
const BYTES_PER_SAMPLE = 2
const BITS_PER_SAMPLE = 16

/** 浏览器 AudioContext 构造器 (iOS Safari 旧版需 webkit 前缀) */
function resolveAudioContextCtor(): typeof AudioContext {
  const Ctor = window.AudioContext
  if (Ctor) return Ctor
  const webkit = (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  if (webkit) return webkit
  throw new Error('当前浏览器不支持 Web Audio, 无法处理语音')
}

/** Float32 采样 (-1~1) → PCM16 小端字节 */
function pcm16FromF32(samples: Float32Array, view: DataView, offset: number): void {
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(offset + i * BYTES_PER_SAMPLE, s < 0 ? s * 0x8000 : s * 0x7fff, true)
  }
}

/** ASCII 写入 RIFF 头块标识 */
function writeTag(view: DataView, offset: number, tag: string): void {
  for (let i = 0; i < tag.length; i++) view.setUint8(offset + i, tag.charCodeAt(i))
}

/** 16kHz 单声道 Float32 → 44 字节 RIFF 头 + PCM16 数据的 WAV Blob */
function encodeWav(samples: Float32Array): Blob {
  const dataSize = samples.length * BYTES_PER_SAMPLE
  const buffer = new ArrayBuffer(44 + dataSize)
  const view = new DataView(buffer)

  writeTag(view, 0, 'RIFF')
  view.setUint32(4, 36 + dataSize, true) //* 文件总长 - 8 (小端)
  writeTag(view, 8, 'WAVE')
  writeTag(view, 12, 'fmt ')
  view.setUint32(16, 16, true) //* fmt 块长度 (PCM 固定 16)
  view.setUint16(20, 1, true) //* 编码格式: 1 = PCM
  view.setUint16(22, 1, true) //* 声道数: 单声道
  view.setUint32(24, TARGET_SAMPLE_RATE, true)
  view.setUint32(28, TARGET_SAMPLE_RATE * BYTES_PER_SAMPLE, true) //* 字节率
  view.setUint16(32, BYTES_PER_SAMPLE, true) //* 块对齐
  view.setUint16(34, BITS_PER_SAMPLE, true)
  writeTag(view, 36, 'data')
  view.setUint32(40, dataSize, true)
  pcm16FromF32(samples, view, 44)

  return new Blob([buffer], { type: 'audio/wav' })
}

/** 任意浏览器可解码音频 → 16kHz 单声道 PCM16 WAV (解码失败抛 Error, 由调用方回落) */
export async function blobToWav16kMono(input: Blob): Promise<Blob> {
  const Ctor = resolveAudioContextCtor()
  const ctx = new Ctor()
  try {
    //* 1. 解码为 Float32 采样 (源采样率/声道数不限, 浏览器统一处理).
    const decoded = await ctx.decodeAudioData(await input.arrayBuffer())
    //* 2. 以目标采样率渲染: 单声道输出自动混音, 内部重采样带抗锯齿.
    const targetLen = Math.ceil(decoded.duration * TARGET_SAMPLE_RATE)
    if (targetLen === 0) throw new Error('音频内容为空, 无法转写')
    const offline = new OfflineAudioContext(1, targetLen, TARGET_SAMPLE_RATE)
    const source = offline.createBufferSource()
    source.buffer = decoded
    source.connect(offline.destination)
    source.start()
    const rendered = await offline.startRendering()
    //* 3. Float32 → PCM16 WAV.
    return encodeWav(rendered.getChannelData(0))
  } catch (err) {
    throw err instanceof Error ? err : new Error('音频解码失败')
  } finally {
    //* 解码用 AudioContext 不接输出设备, 用毕即关, 避免占用浏览器音频资源.
    void ctx.close()
  }
}
