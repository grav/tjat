# Tjat

## Pre-requisites
```
# in root dir
ln -s ../allem
```

```
# in `app`
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
$ AWS_PROFILE=mikkel aws s3 cp public/js/main.js s3://betafunk.dk/tjat/js/ --acl public-read
```
