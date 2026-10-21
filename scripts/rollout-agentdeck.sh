#!/usr/bin/env bash
# 把新版 agentdeck（带 Codex 中继）推到一台机器上：经 agentdeck 控制台下发 shell 任务。
# 新二进制先在原地跑通 version 再替换，旧版留作 agentdeck.<旧版本>.bak；
# 然后写入中继配置并延时重启 watch（任务本身就跑在 watch 里，不能同步重启自己）。
#
# 用法：scripts/rollout-agentdeck.sh <agentdeck 机器 id> <机器名> <linux-amd64|darwin-arm64>
set -euo pipefail

ID=$1 NAME=$2 ARCH=$3
ADMIN=$(cat /opt/agentdeck/data/admin_token)
TOKEN=$(awk -v n="$NAME" '$1==n{print $2}' /etc/me-machine-tokens)
SUM=$(awk -v f="agentdeck-$ARCH" '$2==f{print $1}' /var/lib/me/downloads/agentdeck/SHA256SUMS)
[ -n "$TOKEN" ] && [ -n "$SUM" ] || { echo "缺少 $NAME 的令牌或 $ARCH 的安装包" >&2; exit 1; }

if [ "$ARCH" = darwin-arm64 ]; then
  SHA="shasum -a 256"
  RESTART="(sleep 3; launchctl kickstart -k gui/\$(id -u)/dev.kenchy.agentdeck.sync) >/dev/null 2>&1 &"
else
  SHA="sha256sum"
  RESTART="systemd-run --user --on-active=3 systemctl --user restart agentdeck-watch.service"
fi

CMD=$(cat <<EOF
set -e
cd ~/.local/bin
curl -fsSL -o agentdeck.new https://me.ken-chy129.cn/download/agentdeck/agentdeck-$ARCH
echo "$SUM  agentdeck.new" | $SHA -c -
chmod 755 agentdeck.new
./agentdeck.new version
OLD=\$(./agentdeck version | awk '{print \$2}')
cp agentdeck "agentdeck.\$OLD.bak"
mv agentdeck.new agentdeck
./agentdeck codex-relay wss://me.ken-chy129.cn/codex/machine $TOKEN
$RESTART
echo "替换完成：\$OLD -> \$(./agentdeck version)"
EOF
)

node -e 'process.stdout.write(JSON.stringify({cmd: process.argv[1], wait_sec: 60, timeout_sec: 120}))' "$CMD" |
  curl -s -X POST -H "Authorization: Bearer $ADMIN" -H 'content-type: application/json' \
    "http://127.0.0.1:8480/api/admin/machines/$ID/shell" --data @- |
  node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);console.log(j.status);console.log((j.result||s).split("\n").filter(l=>!l.includes("codex-relay wss")).slice(1).join("\n"))})'
