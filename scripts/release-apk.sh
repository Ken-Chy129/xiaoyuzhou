#!/usr/bin/env bash
# 编译并发布 App：版本号自动加 1，APK 放到下载目录，写 latest.json 让 App 内更新能发现它。
# 用法：scripts/release-apk.sh "这次更新了什么"
set -euo pipefail

NOTES=${1:-""}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DL=${ME_DOWNLOADS:-/var/lib/me/downloads}
CUR=$(node -e 'try{console.log(require(process.argv[1]).versionCode||0)}catch{console.log(0)}' "$DL/latest.json")
CODE=$((CUR + 1))
NAME="0.$((CODE / 10)).$((CODE % 10))"

cd "$ROOT/android"
/opt/gradle/gradle-8.11.1/bin/gradle assembleRelease --console=plain -q -PversionCode=$CODE -PversionName=$NAME

APK="me-$NAME.apk"
install -m 644 app/build/outputs/apk/release/app-release.apk "$DL/$APK"
ln -sf "$APK" "$DL/me.apk"
SUM=$(sha256sum "$DL/$APK" | cut -d' ' -f1)
node -e '
const [code, name, file, sum, notes] = process.argv.slice(1);
process.stdout.write(JSON.stringify({ versionCode: +code, versionName: name, url: "/download/" + file, sha256: sum, notes, releasedAt: new Date().toISOString() }, null, 2));
' "$CODE" "$NAME" "$APK" "$SUM" "$NOTES" > "$DL/latest.json.tmp"
chmod 644 "$DL/latest.json.tmp"
mv "$DL/latest.json.tmp" "$DL/latest.json"
echo "已发布 $NAME（versionCode $CODE）：https://me.ken-chy129.cn/download/$APK"
