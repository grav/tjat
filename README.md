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

## Running the randomllm service locally

The randomllm service provides a mock LLM API for testing purposes.

```bash
cd randomllm
source venv/bin/activate
pip install -r requirements.txt
python randomllm.py
```

The service will run on port 8042 by default, or you can specify a port:
```bash
python randomllm.py 8080
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

## CI / Deployment

A GitHub Actions workflow is provided at `.github/workflows/deploy.yml` that builds the app and runs `deploy.sh` on pushes to `main` or when triggered manually.

Required repository secrets:
- `S3_ENDPOINT_URL`: The S3-compatible endpoint used by `aws` (for example an S3 gateway or AWS endpoint).
- `S3_BUCKET`: The bucket to upload the release files to.

The workflow installs the Clojure CLI and Node.js, runs `npm install` and `npx shadow-cljs release :app` via `deploy.sh`, then uploads built assets to S3.