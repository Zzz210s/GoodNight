#!/usr/bin/env bash
# 构建并发布 release,资产命名为 GoodNight-<versionName>.apk
# 用法: bash scripts/release.sh [版本名(默认取 app/build.gradle.kts)]
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)
TAG="v$V"
echo "==> 构建 release $TAG"
./gradlew.bat :app:assembleRelease --console=plain -q
OUT="app/build/outputs/apk/release/app-release.apk"
ASSET="GoodNight-$V.apk"
cp "$OUT" "/tmp/$ASSET"
echo "==> 创建/更新 GitHub release $TAG"
gh release upload "$TAG" "/tmp/$ASSET" --clobber
echo "==> 清理旧 app-release.apk 资产"
gh api "repos/Zzz210s/GoodNight/releases/tags/$TAG" --jq '.assets[] | select(.name=="app-release.apk") | .id' | while read -r id; do
  gh api -X DELETE "repos/Zzz210s/GoodNight/releases/assets/$id"
done
rm -f "/tmp/$ASSET"
echo "==> 完成:资产 = $ASSET"
