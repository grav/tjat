# Tjat

App is in the `app` dir

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
$ AWS_PROFILE=mikkel aws s3 cp public/js/main.js s3://betafunk.dk/tjat/js/ --acl public-read
```