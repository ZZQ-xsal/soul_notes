//* 日记页: 分页列表 + 日期过滤 + 新建/详情/删除.

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type MouseEvent,
} from "react";
import { deleteDiary, listDiaries } from "../api/diary";
import { ApiError } from "../api/http";
import { useAlert } from "../context/AlertContext";
import { toast } from "../utils/toast";
import type { DiaryItem } from "../types";
import DiaryCard from "../components/diary/DiaryCard";
import DiaryEditor from "../components/diary/DiaryEditor";
import DiaryDetailModal from "../components/diary/DiaryDetailModal";

const PAGE_SIZE = 20;

interface DateFilter {
  startDate: string;
  endDate: string;
}

const openDatePicker = (e: MouseEvent<HTMLInputElement>): void => {
  const el = e.currentTarget;
  if (typeof el.showPicker === "function") {
    try {
      el.showPicker();
    } catch {}
  }
};

export default function DiaryView() {
  const { showAlert } = useAlert();
  const [items, setItems] = useState<DiaryItem[]>([]);
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState<DateFilter>({
    startDate: "",
    endDate: "",
  });
  const [draft, setDraft] = useState<DateFilter>({
    startDate: "",
    endDate: "",
  });
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<DiaryItem | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  //! 后端列表接口不返回总条数, 以本页是否满页推断是否还有下一页.
  const hasMoreRef = useRef(true);
  const mountedRef = useRef(true);
  //* 请求序号: 过滤条件变化/连续刷新时, 只采纳最后一次请求的结果, 防止过期响应覆盖新列表.
  const requestSeqRef = useRef(0);

  useEffect(() => {
    //! 必须在挂载时重置为 true: React 18 StrictMode 开发模式会"挂载→模拟卸载→再挂载",
    //! 若只在 cleanup 里置 false, 第二次挂载后该标记永久为 false, 所有列表响应都会被丢弃 (空列表).
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const load = useCallback(
    async (pageNum: number) => {
      const seq = ++requestSeqRef.current;
      setLoading(true);
      try {
        const list = await listDiaries({
          page: pageNum,
          size: PAGE_SIZE,
          startDate: filter.startDate || undefined,
          endDate: filter.endDate || undefined,
        });
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        setItems(list);
        hasMoreRef.current = list.length === PAGE_SIZE;
      } catch (err) {
        if (!mountedRef.current || seq !== requestSeqRef.current) return;
        toast(err instanceof ApiError ? err.message : "加载日记失败", "error");
      } finally {
        if (mountedRef.current && seq === requestSeqRef.current)
          setLoading(false);
      }
    },
    [filter],
  );

  useEffect(() => {
    void load(page);
  }, [page, load]);

  const applyFilter = (): void => {
    setFilter(draft);
    setPage(1);
  };

  const resetFilter = (): void => {
    setDraft({ startDate: "", endDate: "" });
    setFilter({ startDate: "", endDate: "" });
    setPage(1);
  };

  const handleDelete = async (diary: DiaryItem): Promise<void> => {
    try {
      await deleteDiary(diary.id);
      toast("日记已删除", "success");
      if (detail?.id === diary.id) setDetail(null);
      //* 本页删空且不在第一页时回退一页 (页码变化经 effect 触发加载), 避免停在空页上.
      if (items.length === 1 && page > 1) setPage(page - 1);
      else void load(page);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : "删除失败", "error");
    }
  };

  const handleCreated = (diary: DiaryItem): void => {
    setEditorOpen(false);
    toast("日记已记录", "success");
    //* 本地高危兜底: 分析结果为 RED 时即使预警 WS 未推送也弹出热线弹窗.
    if (diary.analysisResult?.warningLevel?.toUpperCase() === "RED") {
      showAlert("你的日记中检测到高危信号, 请立即寻求专业帮助。");
    }
    //* 若当前日期过滤会挡住新日记 (创建时间落在范围外), 重置过滤保证用户立刻能看到.
    const createdAt = new Date(diary.createdAt);
    const inFilter =
      (!filter.startDate ||
        createdAt >= new Date(filter.startDate + "T00:00:00")) &&
      (!filter.endDate || createdAt <= new Date(filter.endDate + "T23:59:59"));
    if (!inFilter) {
      setDraft({ startDate: "", endDate: "" });
      setFilter({ startDate: "", endDate: "" });
      toast("已重置日期过滤, 以显示刚保存的日记", "info");
    }
    setDetail(diary);
    if (page === 1) void load(1);
    else setPage(1);
  };

  return (
    <div className="diary-view">
      <div className="view-head">
        <div>
          <h1 className="view-title">
            心情日记
            {items.length > 0 && (
              <span className="view-count tabular">
                {" "}
                (本页 {items.length} 条)
              </span>
            )}
          </h1>
          <p className="view-sub">记录此刻, 让情绪被看见</p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn secondary"
            onClick={() => void load(page)}
            disabled={loading}
          >
            刷新
          </button>
          <button
            type="button"
            className="btn"
            onClick={() => setEditorOpen(true)}
          >
            ✏ 写日记
          </button>
        </div>
      </div>

      <div className="filter-row card">
        <label className="filter-field">
          <span className="label">开始日期</span>
          <input
            type="date"
            className="input"
            value={draft.startDate}
            onChange={(e) => setDraft({ ...draft, startDate: e.target.value })}
            onClick={openDatePicker}
          />
        </label>
        <label className="filter-field">
          <span className="label">结束日期</span>
          <input
            type="date"
            className="input"
            value={draft.endDate}
            onChange={(e) => setDraft({ ...draft, endDate: e.target.value })}
            onClick={openDatePicker}
          />
        </label>
        <div className="filter-actions">
          <button type="button" className="btn secondary" onClick={applyFilter}>
            查询日期
          </button>
          <button type="button" className="btn ghost" onClick={resetFilter}>
            重置日期
          </button>
        </div>
      </div>

      <div className={`diary-list${loading ? " is-loading-frame" : ""}`}>
        {items.length === 0 && !loading ? (
          <div className="empty-state card">
            <span className="empty-icon" aria-hidden="true">
              📖
            </span>
            <p>还没有日记, 写下第一篇吧</p>
            <button
              type="button"
              className="btn secondary"
              onClick={() => setEditorOpen(true)}
            >
              写日记
            </button>
          </div>
        ) : (
          items.map((d) => (
            <DiaryCard
              key={d.id}
              diary={d}
              onOpen={setDetail}
              onDelete={(diary) => void handleDelete(diary)}
            />
          ))
        )}
      </div>

      {items.length > 0 && (
        <div className="pager">
          <button
            type="button"
            className="btn secondary sm"
            disabled={page <= 1 || loading}
            onClick={() => setPage(page - 1)}
          >
            上一页
          </button>
          <span className="pager-info tabular">第 {page} 页</span>
          <button
            type="button"
            className="btn secondary sm"
            disabled={!hasMoreRef.current || loading}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </button>
        </div>
      )}

      {editorOpen && (
        <DiaryEditor
          onClose={() => setEditorOpen(false)}
          onCreated={handleCreated}
        />
      )}
      {detail && (
        <DiaryDetailModal diary={detail} onClose={() => setDetail(null)} />
      )}
    </div>
  );
}
