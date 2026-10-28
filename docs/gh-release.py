#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub Release 资产上传工具（二进制不进源码库时的出口）

用法
  python docs/gh-release.py list
  python docs/gh-release.py upload <tag> <本地文件> [--name 远端名] [--title 标题] [--notes 说明]
  python docs/gh-release.py body <tag> <说明文件>     # 增补（不覆盖）已有 release 说明

为什么要有它：apk / hap 这类二进制不进源码库，成品统一挂 Releases；
本机 git push 时好时坏（22 端口常被拦），而上传走 api.github.com，路径不同。

注意
  1. 上传端点不在 api.github.com 而是 uploads.github.com，同一个 token
  2. 同名资产不能覆盖，必须先删再传
  3. token 从 git credential 取，不落盘
"""
import json
import mimetypes
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

OWNER, REPO = "sansimon2001", "CAOJ"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def token() -> str:
    p = subprocess.run(["git", "credential", "fill"],
                       input="host=github.com\nprotocol=https\n\n",
                       capture_output=True, text=True, check=True)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line[9:].strip()
    raise SystemExit("git credential 里没有 github.com 的 token")


TOK = token()


def api(method, path, body=None, raw=None, ctype=None, host=API):
    url = path if path.startswith("http") else host + path
    if raw is not None:
        data = raw
    elif body is not None:
        data = json.dumps(body).encode()
    else:
        data = None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in {
        "Authorization": "Bearer " + TOK,
        "User-Agent": "caoji-release",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }.items():
        req.add_header(k, v)
    if data is not None:
        req.add_header("Content-Type", ctype or "application/json")
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            b = r.read()
            return r.status, (json.loads(b) if b and b[:1] in b"{[" else b)
    except urllib.error.HTTPError as e:
        # 上传端点在大文件时可能 302 到对象存储：保持 POST、去掉 auth 头重发
        if e.code in (301, 302, 307, 308):
            loc = e.headers.get("Location")
            if not loc:
                return e.code, e.read().decode("utf-8", "replace")
            req2 = urllib.request.Request(loc, data=raw, method="POST")
            req2.add_header("Content-Type", ctype or "application/octet-stream")
            req2.add_header("Content-Length", str(len(raw or b"")))
            with urllib.request.urlopen(req2, timeout=600) as r2:
                b2 = r2.read()
                return r2.status, (json.loads(b2) if b2 and b2[:1] in b"{[" else b2)
        return e.code, e.read().decode("utf-8", "replace")


def get_release(tag):
    st, d = api("GET", f"/repos/{OWNER}/{REPO}/releases/tags/{urllib.parse.quote(tag)}")
    return d if st == 200 else None


def cmd_list():
    st, d = api("GET", f"/repos/{OWNER}/{REPO}/releases")
    if st != 200:
        raise SystemExit(f"list {st}: {d}")
    for r in d:
        print(f"{r['tag_name']:<18} {r['name']}")
        for a in r["assets"]:
            print(f"    {a['name']:<46} {a['size']:>10,} B  {a['download_count']} 次下载")


def cmd_body(tag, notes_file):
    """把说明文件内容**追加**到已有 release 的 body（不覆盖原说明）"""
    rel = get_release(tag)
    if not rel:
        raise SystemExit(f"没有 tag 为 {tag} 的 release")
    with open(notes_file, encoding="utf-8") as f:
        add = f.read().strip()
    old = (rel.get("body") or "").rstrip()
    if add in old:
        print("说明已包含该段落，跳过")
        return
    new = (old + "\n\n" + add).strip() + "\n"
    st, d = api("PATCH", f"/repos/{OWNER}/{REPO}/releases/{rel['id']}", {"body": new})
    if st != 200:
        raise SystemExit(f"patch body {st}: {d}")
    print(f"已更新 {tag} 的说明（{len(old)} → {len(new)} 字符）")


def cmd_upload(tag, path, name=None, title=None, notes=None, prerelease=False):
    if not os.path.isfile(path):
        raise SystemExit(f"文件不存在：{path}")
    name = name or os.path.basename(path)
    size = os.path.getsize(path)

    rel = get_release(tag)
    if rel:
        print(f"复用已有 release {tag}（id={rel['id']}）")
    else:
        body = ""
        if notes and os.path.isfile(notes):
            with open(notes, encoding="utf-8") as f:
                body = f.read()
        st, rel = api("POST", f"/repos/{OWNER}/{REPO}/releases", {
            "tag_name": tag,
            "name": title or tag,
            "body": body,
            "draft": False,
            "prerelease": prerelease,
        })
        if st != 201:
            raise SystemExit(f"create release {st}: {rel}")
        print(f"新建 release {tag}（id={rel['id']}）")

    for a in rel.get("assets", []):
        if a["name"] == name:
            st, d = api("DELETE", f"/repos/{OWNER}/{REPO}/releases/assets/{a['id']}")
            if st not in (204, 200):
                raise SystemExit(f"删除旧资产 {st}: {d}")
            print(f"已删除同名旧资产 {name}")

    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    with open(path, "rb") as f:
        raw = f.read()
    st, d = api("POST",
                f"/repos/{OWNER}/{REPO}/releases/{rel['id']}/assets?name={urllib.parse.quote(name)}",
                raw=raw, ctype=ctype, host=UPLOADS)
    if st not in (200, 201):
        raise SystemExit(f"upload {st}: {d}")
    print(f"上传成功：{name}  {size:,} B")
    print(f"下载地址：{d['browser_download_url']}")


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] == "list":
        cmd_list()
    elif a[0] == "body" and len(a) >= 3:
        cmd_body(a[1], a[2])
    elif a[0] == "upload" and len(a) >= 3:
        kw = {}
        rest = a[3:]
        i = 0
        while i < len(rest):
            if rest[i] == "--name":
                kw["name"] = rest[i + 1]; i += 2
            elif rest[i] == "--title":
                kw["title"] = rest[i + 1]; i += 2
            elif rest[i] == "--notes":
                kw["notes"] = rest[i + 1]; i += 2
            elif rest[i] == "--prerelease":
                kw["prerelease"] = True; i += 1
            else:
                raise SystemExit(f"未知参数 {rest[i]}")
        cmd_upload(a[1], a[2], **kw)
    else:
        print(__doc__)
        raise SystemExit(1)
