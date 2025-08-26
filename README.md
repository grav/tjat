# Tjat

A simple, fully browser-based LLM completion client featuring:
- integration to multiple LLMs
- chat history via [InstantDB](https://instantdb.com) 

## Pre-requisites

```
cd app
npm i
```

## Developing

```bash
$ npx shadow-cljs server
```

In a repl:

```clojure
(shadow/watch :app)
```


## Releasing

```bash 
$ npx shadow-cljs release :app 
```

## Deploying
```bash
AWS_PROFILE=mikkel-r2 aws s3api put-object \
  --bucket betafunk --key tjat/index.html --body public/index.html \
  --endpoint-url https://8d6fbb5b106463216bf16c0430e7d284.eu.r2.cloudflarestorage.com \
  --acl public-read
  
AWS_PROFILE=mikkel-r2 aws s3api put-object \
  --bucket betafunk --key tjat/js/main.js --body public/js/main.js \
  --endpoint-url https://8d6fbb5b106463216bf16c0430e7d284.eu.r2.cloudflarestorage.com \
  --acl public-read
  ```
