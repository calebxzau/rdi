#!/usr/bin/env bash
# Publishes the jars and source archives of a successful ffmpeg-natives run as a GitHub Release.
# A tag that already exists makes this fail: a published version never changes.
#
#   publish-natives.sh <run id>
#
# Afterwards, put the new version and hashes from SHA256SUMS in client/gradle/ffmpeg-natives.gradle.kts.
set -euo pipefail

REPO=calebxzau/rdi
RUN=${1:?run id}

read -r WORKFLOW CONCLUSION COMMIT < <(
    gh run view "$RUN" -R "$REPO" --json workflowName,conclusion,headSha -q '"\(.workflowName) \(.conclusion) \(.headSha)"'
)
if [ "$WORKFLOW" != ffmpeg-natives ] || [ "$CONCLUSION" != success ]; then
    echo "Run $RUN is $WORKFLOW with conclusion $CONCLUSION, not a successful ffmpeg-natives run" >&2
    exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
gh run download "$RUN" -R "$REPO" -D "$WORK/artifacts" -p 'ffmpeg-natives-*'
mkdir "$WORK/release"
find "$WORK/artifacts" -type f -exec mv -t "$WORK/release" {} +
cd "$WORK/release"

JARS=$(find . -name '*.jar' | wc -l)
SOURCES=$(find . -name '*.tar.*' | wc -l)
if [ "$JARS" != 3 ] || [ "$SOURCES" != 4 ]; then
    echo "Expected 3 jars and 4 source archives, found $JARS and $SOURCES" >&2
    exit 1
fi

VERSION=$(find . -name '*-windows-x86_64-gpl.jar' -printf '%f' | sed -E 's/^ffmpeg-(.+)-windows-x86_64-gpl\.jar$/\1/')
TAG=ffmpeg-natives-$VERSION
sha256sum -- * > SHA256SUMS
cat SHA256SUMS

gh release create "$TAG" -R "$REPO" --target "$COMMIT" --prerelease --title "FFmpeg natives $VERSION" --notes "$(cat <<EOF
Slim FFmpeg libraries for the RDI launcher, from ffmpeg-natives run $RUN.

- The jars hold FFmpeg in its LGPL configuration with dav1d, libaom and libopus linked statically, plus the unchanged JNI glue of bytedeco's FFmpeg presets. The \`-gpl\` in the file names only matches bytedeco's platform names.
- The source archives are the exact inputs of this build.
- Build script: \`client/mediaproc/ffmpeg/build-natives.sh\` at this tag. Each jar lists its configure flags and license texts under \`META-INF/rdi-ffmpeg/\`.
EOF
)" -- *
