#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate a local RedFox AI trend dashboard.

The script reads REDFOX_API_KEY from the process environment, project .env, or
~/.env. It keeps the key server-side and writes a self-contained HTML report.
"""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import ssl
import sys
import urllib.request
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
REPORT_DIR = ROOT / "reports" / "redfox"
HOTSPOT_API = "https://redfox.hk/story/api/hotSpot/getListByPlatformWithKeyword"
DOUYIN_WORK_API = "https://redfox.hk/story/api/dyData/queryWork"

PLATFORM_MAP = {
    "ks": ("快手", "ksList", 1),
    "dy": ("抖音", "dyList", 2),
    "wb": ("微博", "wbList", 5),
    "bd": ("百度", "bdList", 7),
    "bz": ("B站", "bzList", 8),
    "zh": ("知乎", "zhList", 9),
    "tt": ("头条", "ttList", 10),
}

DEFAULT_KEYWORDS = [
    "AI",
    "人工智能",
    "大模型",
    "Agent",
    "OpenAI",
    "Claude",
    "编程",
    "机器人",
    "高考AI",
    "AI应用",
]


@dataclass
class TrendItem:
    platform_code: str
    platform_name: str
    rank: int
    title: str
    url: str
    hot_count_raw: str
    hot_score: float
    created_at: str


def read_api_key() -> str:
    key = os.environ.get("REDFOX_API_KEY", "").strip()
    if key:
        return key
    for path in [ROOT / ".env", Path.home() / ".env"]:
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            name, value = stripped.split("=", 1)
            if name.strip() == "REDFOX_API_KEY":
                key = value.strip().strip('"').strip("'")
                if key:
                    return key
    raise SystemExit("Missing REDFOX_API_KEY. Add REDFOX_API_KEY=... to project .env or ~/.env.")


def post_json(url: str, payload: dict[str, Any], api_key: str) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-API-KEY": api_key,
        },
    )
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(request, timeout=45, context=context) as response:
        return json.loads(response.read().decode("utf-8"))


def parse_hot_score(value: Any) -> float:
    text = str(value or "").replace(",", "").strip()
    match = re.search(r"(\d+(?:\.\d+)?)", text)
    if not match:
        return 0.0
    number = float(match.group(1))
    if "亿" in text:
        return number * 100_000_000
    if "万" in text:
        return number * 10_000
    return number


def fmt_number(value: Any) -> str:
    try:
        number = float(value or 0)
    except (TypeError, ValueError):
        return str(value or "0")
    if number >= 100_000_000:
        return f"{number / 100_000_000:.1f}亿"
    if number >= 10_000:
        return f"{number / 10_000:.1f}万"
    return str(int(number))


def default_time_window() -> tuple[str, str]:
    end = datetime.now().replace(minute=0, second=0, microsecond=0)
    start = end - timedelta(hours=1)
    return start.strftime("%Y-%m-%d %H:%M:%S"), end.strftime("%Y-%m-%d %H:%M:%S")


def fetch_hotspots(api_key: str, args: argparse.Namespace) -> tuple[list[TrendItem], dict[str, Any]]:
    platform_codes = [p.strip() for p in args.platforms.split(",") if p.strip()]
    platform_enums = [PLATFORM_MAP[p][2] for p in platform_codes if p in PLATFORM_MAP]
    keywords = [k.strip() for k in args.keywords.split(",") if k.strip()]
    start_date, end_date = args.start_date, args.end_date
    if not start_date or not end_date:
        start_date, end_date = default_time_window()

    payload = {
        "source": args.source,
        "platforms": platform_enums,
        "keywords": keywords,
        "startDate": start_date,
        "endDate": end_date,
    }
    result = post_json(HOTSPOT_API, payload, api_key)
    if result.get("code") != 2000:
        raise SystemExit(f"Hotspot API failed: {result.get('msg') or result}")
    data = result.get("data") or {}
    items: list[TrendItem] = []
    for code in platform_codes:
        if code not in PLATFORM_MAP:
            continue
        platform_name, list_key, _ = PLATFORM_MAP[code]
        for raw in data.get(list_key, []) or []:
            title = str(raw.get("title") or "").strip()
            if not title:
                continue
            hot_count = raw.get("hotCount") or raw.get("hotValue") or "0"
            items.append(
                TrendItem(
                    platform_code=code,
                    platform_name=platform_name,
                    rank=int(raw.get("index") or raw.get("rank") or len(items) + 1),
                    title=title,
                    url=str(raw.get("url") or ""),
                    hot_count_raw=str(hot_count),
                    hot_score=parse_hot_score(hot_count),
                    created_at=str(raw.get("gmtCreate") or ""),
                )
            )
    meta = {
        "source": args.source,
        "startDate": start_date,
        "endDate": end_date,
        "keywords": keywords,
        "platforms": platform_codes,
        "apiCode": result.get("code"),
    }
    return items, meta


def fetch_douyin_details(api_key: str, work_urls: list[str], work_ids: list[str]) -> list[dict[str, Any]]:
    details: list[dict[str, Any]] = []
    for work_url in work_urls:
        details.append(fetch_one_douyin(api_key, {"workUrl": work_url}))
    for work_id in work_ids:
        details.append(fetch_one_douyin(api_key, {"workId": work_id}))
    return [item for item in details if item]


def fetch_one_douyin(api_key: str, payload: dict[str, str]) -> dict[str, Any]:
    result = post_json(DOUYIN_WORK_API, payload, api_key)
    if result.get("code") != 2000:
        return {
            "error": result.get("msg") or "抖音详情接口返回失败",
            "input": payload,
        }
    return result.get("data") or {}


def opportunity_score(item: TrendItem, max_hot: float) -> int:
    base = (item.hot_score / max_hot * 58) if max_hot > 0 else 0
    platform_weight = {"dy": 18, "wb": 15, "zh": 13, "bz": 12, "bd": 10, "tt": 9, "ks": 9}.get(item.platform_code, 8)
    keyword_weight = 0
    lowered = item.title.lower()
    for token in ["ai", "人工智能", "大模型", "agent", "openai", "claude", "编程", "机器人"]:
        if token in lowered:
            keyword_weight += 3
    return min(100, int(base + platform_weight + min(keyword_weight, 18) + 12))


def title_words(items: list[TrendItem]) -> list[tuple[str, int]]:
    stop = {"AI", "人工智能", "大模型", "热点", "今天", "一个", "什么", "如何", "真的"}
    counter: Counter[str] = Counter()
    for item in items:
        for word in re.findall(r"[A-Za-z][A-Za-z0-9.+#-]{1,}|[\u4e00-\u9fff]{2,}", item.title):
            if word in stop:
                continue
            counter[word] += 1
    return counter.most_common(18)


def json_for_html(data: Any) -> str:
    return html.escape(json.dumps(data, ensure_ascii=False), quote=False)


def render_dashboard(items: list[TrendItem], details: list[dict[str, Any]], meta: dict[str, Any]) -> str:
    max_hot = max([item.hot_score for item in items] or [1])
    ranked = sorted(items, key=lambda x: opportunity_score(x, max_hot), reverse=True)
    platforms = Counter(item.platform_name for item in items)
    words = title_words(items)
    payload = {
        "meta": meta,
        "items": [
            {
                "platformCode": item.platform_code,
                "platformName": item.platform_name,
                "rank": item.rank,
                "title": item.title,
                "url": item.url,
                "hotCount": item.hot_count_raw,
                "hotScore": item.hot_score,
                "createdAt": item.created_at,
                "score": opportunity_score(item, max_hot),
            }
            for item in ranked
        ],
        "platforms": [{"name": name, "count": count} for name, count in platforms.most_common()],
        "words": [{"word": word, "count": count} for word, count in words],
        "douyinDetails": details,
    }
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>RedFox AI Trend Radar</title>
  <style>
    :root {{
      --bg: #f6f7f4;
      --surface: #ffffff;
      --ink: #1f2421;
      --muted: #68706a;
      --line: #dfe3dc;
      --accent: #d94f32;
      --green: #2f7d5b;
      --amber: #b98022;
      --blue: #3f6c8f;
      --shadow: 0 14px 34px rgba(31,36,33,.08);
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      background: var(--bg);
      color: var(--ink);
      font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      letter-spacing: 0;
    }}
    a {{ color: inherit; text-decoration: none; }}
    .shell {{ max-width: 1420px; margin: 0 auto; padding: 24px; }}
    .topbar {{
      display: grid;
      grid-template-columns: minmax(0, 1.3fr) minmax(340px, .7fr);
      gap: 18px;
      align-items: stretch;
      margin-bottom: 18px;
    }}
    .hero {{
      min-height: 270px;
      padding: 28px;
      background: linear-gradient(135deg, #243129, #4a3427 58%, #7a3f2c);
      color: #fff;
      border-radius: 8px;
      box-shadow: var(--shadow);
      display: grid;
      align-content: space-between;
      overflow: hidden;
      position: relative;
    }}
    .hero::after {{
      content: "";
      position: absolute;
      right: -80px;
      top: -120px;
      width: 310px;
      height: 310px;
      border: 1px solid rgba(255,255,255,.22);
      transform: rotate(24deg);
    }}
    .eyebrow {{ font-size: 12px; text-transform: uppercase; opacity: .78; font-weight: 700; }}
    h1 {{ margin: 10px 0 0; font-size: 42px; line-height: 1.08; max-width: 780px; }}
    .subtitle {{ margin: 14px 0 0; color: rgba(255,255,255,.76); max-width: 760px; line-height: 1.7; }}
    .meta-row {{ display: flex; flex-wrap: wrap; gap: 10px; margin-top: 22px; }}
    .pill {{ border: 1px solid rgba(255,255,255,.24); border-radius: 999px; padding: 7px 11px; font-size: 13px; color: rgba(255,255,255,.85); }}
    .panel {{
      background: var(--surface);
      border: 1px solid var(--line);
      border-radius: 8px;
      box-shadow: var(--shadow);
      padding: 18px;
    }}
    .stat-grid {{ display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 12px; }}
    .stat {{ border-top: 3px solid var(--accent); background: #fafbf8; padding: 14px; min-height: 92px; }}
    .stat:nth-child(2) {{ border-color: var(--green); }}
    .stat:nth-child(3) {{ border-color: var(--amber); }}
    .stat:nth-child(4) {{ border-color: var(--blue); }}
    .stat strong {{ display: block; font-size: 28px; margin-bottom: 7px; }}
    .stat span {{ color: var(--muted); font-size: 13px; }}
    .section-title {{ margin: 0 0 12px; font-size: 19px; }}
    .grid-main {{ display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 18px; }}
    .toolbar {{ display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }}
    .button {{
      border: 1px solid var(--line);
      background: #fff;
      color: var(--ink);
      border-radius: 6px;
      padding: 9px 12px;
      cursor: pointer;
      font-weight: 650;
    }}
    .button.active {{ background: var(--ink); color: #fff; border-color: var(--ink); }}
    .trend-list {{ display: grid; gap: 10px; }}
    .trend {{
      display: grid;
      grid-template-columns: 72px minmax(0, 1fr) 110px;
      gap: 14px;
      align-items: center;
      border: 1px solid var(--line);
      border-radius: 8px;
      padding: 14px;
      background: #fff;
    }}
    .score {{
      width: 58px;
      height: 58px;
      border-radius: 50%;
      display: grid;
      place-items: center;
      background: conic-gradient(var(--accent) calc(var(--score) * 1%), #e8ebe4 0);
      font-weight: 800;
      position: relative;
    }}
    .score::after {{ content: ""; position: absolute; inset: 7px; background: #fff; border-radius: 50%; }}
    .score span {{ position: relative; z-index: 1; font-size: 15px; }}
    .trend h3 {{ margin: 0; font-size: 17px; line-height: 1.4; }}
    .trend p {{ margin: 7px 0 0; color: var(--muted); font-size: 13px; }}
    .hot {{ font-weight: 800; color: var(--accent); text-align: right; }}
    .bar-row {{ display: grid; grid-template-columns: 72px minmax(0,1fr) 34px; gap: 10px; align-items: center; margin: 12px 0; }}
    .bar-track {{ height: 10px; background: #e9ece7; border-radius: 999px; overflow: hidden; }}
    .bar-fill {{ height: 100%; background: var(--green); width: var(--w); }}
    .word-cloud {{ display: flex; flex-wrap: wrap; gap: 8px; }}
    .word {{ background: #f3efe6; border: 1px solid #e5d8be; border-radius: 999px; padding: 7px 10px; font-size: 13px; }}
    .detail {{ border-top: 1px solid var(--line); padding: 14px 0; }}
    .detail:first-of-type {{ border-top: 0; }}
    .detail h3 {{ margin: 0 0 8px; font-size: 16px; }}
    .metric-row {{ display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 8px; margin-top: 10px; }}
    .metric {{ background: #f7f8f4; padding: 9px; border-radius: 6px; }}
    .metric b {{ display: block; }}
    .metric span {{ color: var(--muted); font-size: 12px; }}
    .empty {{ color: var(--muted); line-height: 1.7; }}
    @media (max-width: 980px) {{
      .topbar, .grid-main {{ grid-template-columns: 1fr; }}
      h1 {{ font-size: 32px; }}
      .trend {{ grid-template-columns: 60px minmax(0,1fr); }}
      .hot {{ grid-column: 2; text-align: left; }}
    }}
    @media (max-width: 560px) {{
      .shell {{ padding: 12px; }}
      .hero {{ padding: 20px; }}
      .stat-grid, .metric-row {{ grid-template-columns: 1fr; }}
    }}
  </style>
</head>
<body>
  <main class="shell">
    <section class="topbar">
      <div class="hero">
        <div>
          <div class="eyebrow">RedFox AI Trend Radar</div>
          <h1>AI 热点到内容机会的可视化工作台</h1>
          <p class="subtitle">聚合全网热点，筛选 AI 相关信号，并把抖音作品详情变成可复盘的数据卡片。生成时间：{html.escape(generated_at)}</p>
          <div class="meta-row">
            <span class="pill">时间窗 {html.escape(str(meta.get("startDate")))} - {html.escape(str(meta.get("endDate")))}</span>
            <span class="pill">关键词 {html.escape(" / ".join(meta.get("keywords", [])))}</span>
            <span class="pill">平台 {html.escape(" / ".join(meta.get("platforms", [])))}</span>
          </div>
        </div>
      </div>
      <aside class="panel">
        <h2 class="section-title">概览</h2>
        <div class="stat-grid">
          <div class="stat"><strong id="statItems">0</strong><span>AI 相关热点</span></div>
          <div class="stat"><strong id="statPlatforms">0</strong><span>覆盖平台</span></div>
          <div class="stat"><strong id="statScore">0</strong><span>最高机会分</span></div>
          <div class="stat"><strong id="statDouyin">0</strong><span>抖音详情卡</span></div>
        </div>
      </aside>
    </section>

    <section class="grid-main">
      <div class="panel">
        <h2 class="section-title">热点机会榜</h2>
        <div class="toolbar" id="platformFilters"></div>
        <div class="trend-list" id="trendList"></div>
      </div>
      <aside>
        <div class="panel">
          <h2 class="section-title">平台分布</h2>
          <div id="platformBars"></div>
        </div>
        <div class="panel" style="margin-top:18px">
          <h2 class="section-title">高频词</h2>
          <div class="word-cloud" id="wordCloud"></div>
        </div>
        <div class="panel" style="margin-top:18px">
          <h2 class="section-title">抖音详情</h2>
          <div id="douyinDetails"></div>
        </div>
      </aside>
    </section>
  </main>

  <script id="dashboard-data" type="application/json">{json_for_html(payload)}</script>
  <script>
    const data = JSON.parse(document.getElementById('dashboard-data').textContent);
    let activePlatform = 'all';
    const $ = (id) => document.getElementById(id);
    const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}}[c]));
    const fmt = (n) => {{
      const x = Number(n || 0);
      if (x >= 100000000) return (x / 100000000).toFixed(1) + '亿';
      if (x >= 10000) return (x / 10000).toFixed(1) + '万';
      return String(Math.round(x));
    }};
    function renderStats() {{
      $('statItems').textContent = data.items.length;
      $('statPlatforms').textContent = data.platforms.length;
      $('statScore').textContent = Math.max(0, ...data.items.map(x => x.score));
      $('statDouyin').textContent = data.douyinDetails.length;
    }}
    function renderFilters() {{
      const platforms = ['all', ...new Set(data.items.map(x => x.platformName))];
      $('platformFilters').innerHTML = platforms.map(p => `<button class="button ${{p === activePlatform ? 'active' : ''}}" data-platform="${{esc(p)}}">${{p === 'all' ? '全部' : esc(p)}}</button>`).join('');
      document.querySelectorAll('[data-platform]').forEach(btn => {{
        btn.addEventListener('click', () => {{
          activePlatform = btn.dataset.platform;
          render();
        }});
      }});
    }}
    function renderTrends() {{
      const items = data.items.filter(x => activePlatform === 'all' || x.platformName === activePlatform);
      $('trendList').innerHTML = items.length ? items.map(item => `
        <article class="trend">
          <div class="score" style="--score:${{item.score}}"><span>${{item.score}}</span></div>
          <div>
            <h3>${{item.url ? `<a href="${{esc(item.url)}}" target="_blank" rel="noreferrer">${{esc(item.title)}}</a>` : esc(item.title)}}</h3>
            <p>${{esc(item.platformName)}} · 排名 ${{esc(item.rank)}} · ${{esc(item.createdAt || data.meta.endDate)}}</p>
          </div>
          <div class="hot">${{esc(item.hotCount || fmt(item.hotScore))}}</div>
        </article>
      `).join('') : '<p class="empty">当前筛选下没有热点。</p>';
    }}
    function renderBars() {{
      const max = Math.max(1, ...data.platforms.map(x => x.count));
      $('platformBars').innerHTML = data.platforms.map(p => `
        <div class="bar-row">
          <strong>${{esc(p.name)}}</strong>
          <div class="bar-track"><div class="bar-fill" style="--w:${{Math.round(p.count / max * 100)}}%"></div></div>
          <span>${{p.count}}</span>
        </div>
      `).join('');
    }}
    function renderWords() {{
      $('wordCloud').innerHTML = data.words.length ? data.words.map(w => `<span class="word">${{esc(w.word)}} · ${{w.count}}</span>`).join('') : '<p class="empty">暂无可提取词。</p>';
    }}
    function renderDouyin() {{
      $('douyinDetails').innerHTML = data.douyinDetails.length ? data.douyinDetails.map(d => {{
        if (d.error) return `<div class="detail"><h3>详情获取失败</h3><p class="empty">${{esc(d.error)}}</p></div>`;
        return `
          <div class="detail">
            <h3>${{d.workUrl ? `<a href="${{esc(d.workUrl)}}" target="_blank" rel="noreferrer">${{esc(d.title || '未命名作品')}}</a>` : esc(d.title || '未命名作品')}}</h3>
            <p class="empty">${{esc(d.accountName || '')}} · ${{esc(d.publishTime || '')}}</p>
            <div class="metric-row">
              <div class="metric"><b>${{fmt(d.playCount)}}</b><span>播放</span></div>
              <div class="metric"><b>${{fmt(d.likeCount)}}</b><span>点赞</span></div>
              <div class="metric"><b>${{fmt(d.commentCount)}}</b><span>评论</span></div>
              <div class="metric"><b>${{fmt(d.shareCount)}}</b><span>分享</span></div>
              <div class="metric"><b>${{fmt(d.collectCount)}}</b><span>收藏</span></div>
              <div class="metric"><b>${{fmt(d.followerCount)}}</b><span>粉丝</span></div>
            </div>
          </div>`;
      }}).join('') : '<p class="empty">还没有传入抖音作品链接。下次运行可加：--work-url "https://..."</p>';
    }}
    function render() {{
      renderStats();
      renderFilters();
      renderTrends();
      renderBars();
      renderWords();
      renderDouyin();
    }}
    render();
  </script>
</body>
</html>
"""


def write_report(html_text: str, raw: dict[str, Any], output: Path | None) -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    if output is None:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        output = REPORT_DIR / f"redfox-ai-dashboard-{stamp}.html"
    output.write_text(html_text, encoding="utf-8")
    raw_path = output.with_suffix(".json")
    raw_path.write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding="utf-8")
    return output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate RedFox AI trend dashboard")
    parser.add_argument("--keywords", default=",".join(DEFAULT_KEYWORDS), help="Comma-separated hotspot keywords")
    parser.add_argument("--platforms", default="dy,wb,zh,bz,bd,tt,ks", help="Comma-separated platform codes")
    parser.add_argument("--source", default="全平台热点事件-GitHub", help="RedFox hotspot source")
    parser.add_argument("--start-date", help="Start datetime, e.g. 2026-06-08 14:00:00")
    parser.add_argument("--end-date", help="End datetime, e.g. 2026-06-08 15:00:00")
    parser.add_argument("--work-url", action="append", default=[], help="Douyin work URL for detail cards; can repeat")
    parser.add_argument("--work-id", action="append", default=[], help="Douyin work ID for detail cards; can repeat")
    parser.add_argument("--output", type=Path, help="Output HTML path")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    api_key = read_api_key()
    items, meta = fetch_hotspots(api_key, args)
    details = fetch_douyin_details(api_key, args.work_url, args.work_id)
    html_text = render_dashboard(items, details, meta)
    output = write_report(
        html_text,
        {
            "meta": meta,
            "items": [item.__dict__ for item in items],
            "douyinDetails": details,
        },
        args.output,
    )
    print(output)


if __name__ == "__main__":
    main()
