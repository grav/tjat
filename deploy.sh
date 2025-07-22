#!/usr/bin/env bash

set -euo pipefail
set -x
dir="${1:-tjat}"

export AWS_PAGER=""


pushd app

npm i

npx shadow-cljs release :app

magick tjat_logo.png -define icon:auto-resize=16,32,48,64,128,256 public/favicon.ico 

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
