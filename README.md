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

## Running local models via Ollama

To make Ollama accept requests from the browser, you need to allow
additional origins when starting Ollama, eg:
```
$ OLLAMA_ORIGINS=https://betafunk.dk ollama serve
```

and then start your model, eg:

```
$ ollama run qwen3:8b
```

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

### Deploying

Check out the `deploy.sh` script.