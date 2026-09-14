//* 日记接口: /api/v1/diaries/*

import { api } from './http'
import type { DiaryItem, WeatherDay } from '../types'

//! 用 type 而非 interface: 对象字面量类型别名可赋给 http 层的 Record 索引签名参数.
export type DiaryListParams = {
  page?: number
  size?: number
  startDate?: string
  endDate?: string
}

export function listDiaries(params: DiaryListParams = {}): Promise<DiaryItem[]> {
  return api<DiaryItem[]>('/diaries', { params })
}

export interface DiaryCreatePayload {
  content: string | null
  audioData: string | null
  sourceType: 'TEXT' | 'VOICE'
}

export function createDiary(payload: DiaryCreatePayload): Promise<DiaryItem> {
  return api<DiaryItem>('/diaries', { method: 'POST', body: payload })
}

export function getDiary(id: number): Promise<DiaryItem> {
  return api<DiaryItem>(`/diaries/${id}`)
}

export function deleteDiary(id: number): Promise<void> {
  return api<void>(`/diaries/${id}`, { method: 'DELETE' })
}

/** 情绪天气预报: 按日聚合, startDate/endDate 为 "YYYY-MM-DD" */
export function getWeather(startDate: string, endDate: string): Promise<WeatherDay[]> {
  return api<WeatherDay[]>('/diaries/weather', { params: { startDate, endDate } })
}
