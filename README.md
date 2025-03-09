# Tjat

A simple, fully browser-based LLM completion client featuring:
- integration to multiple providers and models
- chat history via [InstantDB](https://instantdb.com) 

Try it here: [https://betafunk.dk/tjat](http://betafunk.dk/tjat)

## FAQ
Q: Does this app steal my API keys?

A: No, keys are stored locally, and only submitted to model providers

Q: How do I save my chat history?

A: Go to [InstantDB](https://instantdb.com), create a new app, and copy the app-id into Tjat's settings.

Q: Can people read my chat history?

A: Only if they can guess your InstantDB app-id!



## Developing

### Pre-requisites

```
cd app
npm i
```

### Building and running
```bash
$ npx shadow-cljs server
```

In a repl:

```clojure
(shadow/watch :app)
```

Visit http://localhost:8000

### Releasing

```bash 
$ npx shadow-cljs release :app 
```

### Deploying

The release-build consists of a single javascript file,
in the `public-release` dir.

To deploy to eg S3 or Cloudflare, do:

```bash
AWS_PROFILE=[profile] aws s3api put-object \ 
  --endpoint-url [s3 or cloudflare bucket endpoint] \
  --bucket [bucket] --key tjat/js/main.js --body public-release/js/main.js \
  --acl public-read
  
# If you also updated the html:
  
AWS_PROFILE=[profile] aws s3api put-object \ 
  --endpoint-url [s3 or cloudflare bucket endpoint] \
  --key tjat/index.html --body public/index.html \
  --acl public-read
  
  ```
