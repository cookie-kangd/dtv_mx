#!/usr/bin/env python3
"""dtv_mx 直播源入口只读冒烟检查（仅标准库，无凭据、不写任何文件）。

用途：在发版前或每日巡检时，快速发现「平台公开接口整体不可达 / 返回结构变了」
这类问题。它只请求各平台的公开列表/分类入口，不做签名、不取播放地址、
不打印或存储任何 Cookie 内容。若需要覆盖播放地址链路，请在真机上做回归，
或在项目内用已实现的签名逻辑扩展 CHECKS。

用法：
    python scripts/source_smoke_check.py
    python scripts/source_smoke_check.py --json

接入 CI（按需，本项目默认不启用定时任务）：
    - name: Live source smoke check
      run: python scripts/source_smoke_check.py
      # 想启用每日巡检再加：
      # on:
      #   schedule:
      #     - cron: '0 1 * * *'
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
TIMEOUT = 10

CHECKS = [
    ("斗鱼", "分类目录", "https://m.douyu.com/api/cate/list"),
    ("斗鱼", "房间信息入口", "https://www.douyu.com/betard/1"),
    ("虎牙", "直播列表", "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&tagAll=0&page=1"),
    ("抖音", "直播间首页", "https://live.douyin.com/"),
    ("B站", "分区列表", "https://api.live.bilibili.com/room/v1/Area/getList"),
]


def check(name: str, label: str, url: str) -> dict:
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "*/*", "Accept-Language": "zh-CN,zh;q=0.9"},
        method="GET",
    )
    result = {"platform": name, "entry": label, "url": url, "ok": False}
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = resp.read(64 * 1024)
            set_cookie = resp.headers.get_all("Set-Cookie") or []
            result.update(
                http_status=resp.status,
                bytes=len(body),
                has_ttwid=any("ttwid=" in c for c in set_cookie),
                ok=200 <= resp.status < 400 and len(body) > 0,
                note="ok" if len(body) > 0 else "empty response body",
            )
    except urllib.error.HTTPError as e:
        result.update(http_status=e.code, note=f"HTTP {e.code}")
    except Exception as e:  # noqa: BLE001 - 巡检脚本需要把任何网络异常都记为失败
        result.update(note=f"{type(e).__name__}: {e}")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="dtv_mx live source smoke check")
    parser.add_argument("--json", action="store_true", help="输出 JSON 而非表格")
    args = parser.parse_args()

    results = [check(*c) for c in CHECKS]
    failed = [r for r in results if not r["ok"]]

    if args.json:
        print(json.dumps({"results": results, "failed": len(failed)}, ensure_ascii=False, indent=2))
    else:
        print("平台  入口            状态    HTTP   字节   备注")
        print("-" * 68)
        for r in results:
            print(
                f"{r['platform']:<4}  {r['entry']:<12}  "
                f"{'OK' if r['ok'] else 'FAIL':<5}  "
                f"{str(r.get('http_status', '-')):<5}  "
                f"{str(r.get('bytes', '-')):<6}  {r.get('note', '')}"
            )
        print("-" * 68)
        print(f"{len(results) - len(failed)}/{len(results)} 通过")
        if failed:
            print("失败项：" + "、".join(f"{r['platform']}/{r['entry']}" for r in failed))

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
