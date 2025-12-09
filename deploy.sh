#!/usr/bin/env bash

set -euo pipefail
set -x
dir="${1:-tjat}"

export AWS_PAGER=""


pushd app

[ -z "$(git status --porcelain)" ] || ( >&2 echo Boom; exit 1 )

npm i

GIT_SHA="$(git rev-parse HEAD)" npx shadow-cljs release :app

# Create favicon using ImageMagick. Prefer `magick` (ImageMagick 7+), fall back to `convert` (ImageMagick 6).
if command -v magick >/dev/null 2>&1; then
  echo "Using magick to create favicon"
  magick tjat_logo.png -define icon:auto-resize=16,32,48,64,128,256 public/favicon.ico
elif command -v convert >/dev/null 2>&1; then
  echo "Using convert to create favicon"
  convert tjat_logo.png -define icon:auto-resize=16,32,48,64,128,256 public/favicon.ico
else
  echo "ERROR: ImageMagick not found (magick/convert). Aborting deploy." >&2
  exit 1
fi

aws s3api put-object \
    --endpoint-url "$S3_ENDPOINT_URL" \
    --bucket "$S3_BUCKET" \
    --key "${dir}/favicon.ico" --body public/favicon.ico \
    --acl public-read \
    --checksum-algorithm CRC32

aws s3api put-object \
  --endpoint-url "$S3_ENDPOINT_URL" \
  --bucket "$S3_BUCKET" \
  --key "${dir}/js/main.js" --body public-release/js/main.js  \
  --acl public-read \
  --checksum-algorithm CRC32

index_file="$(mktemp).html"

cachebuster=$(uuidgen)

sed 's/main.js/main.js?'"$cachebuster"'/g' public/index.html > "$index_file"

aws s3api put-object \
  --endpoint-url "$S3_ENDPOINT_URL" \
  --bucket "$S3_BUCKET" \
  --key "${dir}/index.html" --body "$index_file" \
  --acl public-read \

popd
