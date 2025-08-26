# Tjat

A simple, fully browser-based LLM completion client featuring:
- integration to multiple providers and models
- chat history via [InstantDB](https://instantdb.com) 

## FAQ
Q: Does this app steal my API keys?
A: No, keys are stored loaclly, and only submitted to model providers
Q: Can people read my chat history
A: Only if they can guess your InstantDB app-id

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

## Deploying to S3 / Cloudfront
```bash
AWS_PROFILE=mikkel-r2 aws s3api put-object \ 
  --endpoint-url [s3 or cloudfront bucket endpoint] \
  --bucket betafunk --key tjat/js/main.js --body public/js/main.js \
  --key tjat/index.html --body public/index.html \
  --acl public-read
  ```
