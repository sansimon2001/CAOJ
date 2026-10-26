#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub 推送 / 对齐工具（git push 被代理封掉时的兜底）

子命令
  push    把本地 HEAD 的内容推到远端 main（Git Data API），并让本地与远端 **提交对象完全一致**
  status  打印本地 / 远端 head 与 tree，判断是否内容一致

关键点：先用 POST /git/commits 带上 `author` / `committer`（含精确 ISO 时间）建提交，
再用 `git commit-tree` 在本地用同样字段重建 —— 两者 sha 应当相同，
于是最后 `git update-ref refs/heads/main <sha>` 就能真正对齐，不再出现「ahead 1」的假分叉。
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

OWNER, REPO, BRANCH = "sansimon2001", "CAOJ", "main"
API = "https://api.github.com"


def token() -> str:
    p = subprocess.run(["git", "credential", "fill"],
                       input="host=github.com\nprotocol=https\n\n",
                       capture_output=True, text=True, check=True)
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line[9:].strip()
    raise SystemExit("no token")


TOK = token()


def api(method, path, body=None):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in {
        "Authorization": "Bearer " + TOK,
        "User-Agent": "caoji-push",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }.items():
        req.add_header(k, v)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            b = r.read()
            return r.status, (json.loads(b) if b else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def git(*a, binary=False):
    p = subprocess.run(["git", *a], capture_output=True, check=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def remote_head():
    st, ref = api("GET", f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}")
    if st != 200:
        raise SystemExit(f"get ref {st}: {ref}")
    sha = ref["object"]["sha"]
    st, c = api("GET", f"/repos/{OWNER}/{REPO}/git/commits/{sha}")
    if st != 200:
        raise SystemExit(f"get commit {st}: {c}")
    return sha, c["tree"]["sha"]


def local_files():
    out = {}
    for line in git("-c", "core.quotepath=false", "ls-tree", "-r", "HEAD").splitlines():
        meta, path = line.split("\t", 1)
        mode, _t, sha = meta.split()
        out[path] = (mode, sha)
    return out


def cmd_status():
    rsha, rtree = remote_head()
    ltree = git("rev-parse", "HEAD^{tree}").strip()
    print("远端 head:", rsha, "tree:", rtree)
    print("本地 head:", git("rev-parse", "HEAD").strip(), "tree:", ltree)
    print("内容一致:", rtree == ltree)


def cmd_push():
    rsha, rtree = remote_head()
    ltree = git("rev-parse", "HEAD^{tree}").strip()

    if rtree == ltree:
        # 内容已一致，只需把本地 ref 对齐到远端提交（若对象在本地可重建）
        print("内容已是同一 tree，尝试对齐本地 main …")
        st, rc = api("GET", f"/repos/{OWNER}/{REPO}/git/commits/{rsha}")
        if st == 200 and align_local(rc):
            return
        print("（无法在本地重建该提交对象，保留名义差异，下次 git fetch 会自动消除）")
        return

    local = local_files()
    st, rt = api("GET", f"/repos/{OWNER}/{REPO}/git/trees/{rtree}?recursive=1")
    if st != 200:
        raise SystemExit(f"get tree {st}: {rt}")
    rpaths = {e["path"]: e["sha"] for e in rt.get("tree", []) if e["type"] == "blob"}

    entries = []
    changed = [(p, m, s) for p, (m, s) in local.items() if rpaths.get(p) != s]
    stale = [p for p in rpaths if p not in local]
    print(f"远端 {rsha[:7]}；上传 {len(changed)} / 删除 {len(stale)}")
    for p, _m, _s in changed:
        print("  ~", p)
        content = git("cat-file", "blob", _s, binary=True)
        st, blob = api("POST", f"/repos/{OWNER}/{REPO}/git/blobs",
                       {"content": __import__("base64").b64encode(content).decode(),
                        "encoding": "base64"})
        if st != 201:
            raise SystemExit(f"blob {p} {st}: {blob}")
        entries.append({"path": p, "mode": _m, "type": "blob", "sha": blob["sha"]})
    for p in stale:
        print("  -", p)
        entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})

    st, nt = api("POST", f"/repos/{OWNER}/{REPO}/git/trees",
                 {"base_tree": rtree, "tree": entries})
    if st != 201:
        raise SystemExit(f"tree {st}: {nt}")

    # ===== 关键：用本地提交的 author / committer / message 建远端提交，便于本地复现 sha =====
    msg = git("log", "-1", "--pretty=%B").rstrip("\n") + "\n"
    a_name = git("log", "-1", "--pretty=%an").strip()
    a_mail = git("log", "-1", "--pretty=%ae").strip()
    a_date = git("log", "-1", "--pretty=%aI").strip()
    c_date = git("log", "-1", "--pretty=%cI").strip()
    who = {"name": a_name, "email": a_mail, "date": a_date}
    st, nc = api("POST", f"/repos/{OWNER}/{REPO}/git/commits", {
        "message": msg, "tree": nt["sha"], "parents": [rsha],
        "author": who, "committer": {"name": a_name, "email": a_mail, "date": c_date},
    })
    if st != 201:
        raise SystemExit(f"commit {st}: {nc}")

    st, out = api("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
                  {"sha": nc["sha"], "force": False})
    if st != 200:
        raise SystemExit(f"patch ref {st}: {out}")
    print("远端已更新:", nc["sha"])

    if try_align(nc["sha"], nt["sha"], rsha, msg, a_name, a_mail, a_date, c_date):
        print("本地 main 已对齐（提交对象一致）")


def try_align(sha, tree, parent, msg, name, mail, adate, cdate) -> bool:
    """用**本地**的 author/committer 时间字符串重建提交，比对 sha 后同步引用

    坑：API 返回的 commit 对象里日期是 UTC（`...Z`），拿它去 commit-tree 会得到
    `+0000` 的时区偏移，字节不同 → sha 不同。必须用本地 `%aI` / `%cI` 的原样字符串
    （带 +0800），才能和 GitHub 建出来的对象逐字节相同。
    """
    env = dict(os.environ)
    env.update({
        "GIT_AUTHOR_NAME": name, "GIT_AUTHOR_EMAIL": mail, "GIT_AUTHOR_DATE": adate,
        "GIT_COMMITTER_NAME": name, "GIT_COMMITTER_EMAIL": mail, "GIT_COMMITTER_DATE": cdate,
    })
    p = subprocess.run(["git", "commit-tree", tree, "-p", parent],
                       input=msg.encode(), capture_output=True, env=env)
    if p.returncode != 0:
        print("commit-tree 失败:", p.stderr.decode()[:300])
        return False
    local = p.stdout.decode().strip()
    if local != sha:
        print(f"sha 仍未一致（本地 {local[:7]} / 远端 {sha[:7]}），内容已同步，后续 git fetch 可消除名义差异")
        return False
    subprocess.run(["git", "update-ref", f"refs/heads/{BRANCH}", sha], check=True)
    subprocess.run(["git", "update-ref", f"refs/remotes/origin/{BRANCH}", sha], check=True)
    return True


def align_local(rc) -> bool:
    """用与远端提交完全相同的字段在本地重建提交；成功则把 main 指过去"""
    env = dict(os.environ)
    env.update({
        "GIT_AUTHOR_NAME": rc["author"]["name"],
        "GIT_AUTHOR_EMAIL": rc["author"]["email"],
        "GIT_AUTHOR_DATE": rc["author"]["date"],
        "GIT_COMMITTER_NAME": rc["committer"]["name"],
        "GIT_COMMITTER_EMAIL": rc["committer"]["email"],
        "GIT_COMMITTER_DATE": rc["committer"]["date"],
    })
    args = ["git", "commit-tree", rc["tree"]["sha"]]
    # 注意：commit 对象里的 parents 是对象数组（含 sha/url），不是字符串数组
    for parent in rc["parents"]:
        args += ["-p", parent["sha"]]
    p = subprocess.run(args, input=rc["message"].encode(),
                       capture_output=True, env=env)
    if p.returncode != 0:
        print("commit-tree 失败:", p.stderr.decode()[:300])
        return False
    sha = p.stdout.decode().strip()
    if sha != rc["sha"]:
        print(f"sha 不一致：本地 {sha[:7]} / 远端 {rc['sha'][:7]}")
        return False
    subprocess.run(["git", "update-ref", f"refs/heads/{BRANCH}", sha], check=True)
    subprocess.run(["git", "update-ref", f"refs/remotes/origin/{BRANCH}", sha], check=True)
    return True


def cmd_resync():
    """内容已一致、但提交对象不同时用：用**可控字段**重建一个提交，让两边 sha 也一致

    做法：以当前远端 head 的父提交为 parent（通常是同一个 v1.24 基线），
    tree 用本地 HEAD 的 tree，author/committer/message 全部取自本地 HEAD。
    这样 API 建出来的提交能在本地用 commit-tree 精确复现，最后强制把 main 指过去。
    """
    rsha, rtree = remote_head()
    st, rc = api("GET", f"/repos/{OWNER}/{REPO}/git/commits/{rsha}")
    parent = rc["parents"][0]["sha"]

    msg = git("log", "-1", "--pretty=%B").rstrip("\n") + "\n"
    name = git("log", "-1", "--pretty=%an").strip()
    mail = git("log", "-1", "--pretty=%ae").strip()
    adate = git("log", "-1", "--pretty=%aI").strip()
    cdate = git("log", "-1", "--pretty=%cI").strip()
    tree = git("rev-parse", "HEAD^{tree}").strip()

    st, nc = api("POST", f"/repos/{OWNER}/{REPO}/git/commits", {
        "message": msg, "tree": tree, "parents": [parent],
        "author": {"name": name, "email": mail, "date": adate},
        "committer": {"name": name, "email": mail, "date": cdate},
    })
    if st != 201:
        raise SystemExit(f"commit {st}: {nc}")
    print("新提交:", nc["sha"])

    st, out = api("PATCH", f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}",
                  {"sha": nc["sha"], "force": True})
    if st != 200:
        raise SystemExit(f"patch ref {st}: {out}")
    if try_align(nc["sha"], tree, parent, msg, name, mail, adate, cdate):
        print("已完全对齐（本地 main == 远端 main）")
    else:
        raise SystemExit("未能对齐，请改用 git fetch && git rebase")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "push"
    {"status": cmd_status, "push": cmd_push, "resync": cmd_resync}.get(cmd, cmd_push)()
