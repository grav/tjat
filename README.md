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

Q: Inserting my AWS / Cloudflare keys into the browser, are you nuts?

A: Still only stored locally in the browser.

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

## Using Cloudflare R2 or AWS S3 for storing attachments

To be able to reproduce a request with attachments, you can
add credentials for R2 or S3 in the settings.

Once enabled, attachments will be uploaded to the configured bucket.

You'll need to add CORS rules to your bucket to allow access from wherever you've
deployed Tjat. The necessary rules look like this for Cloudflare R2:
```json
[
  {
    "AllowedOrigins": [
      "https://mytjattywebsite.ay"
    ],
    "AllowedMethods": [
      "GET",
      "PUT",
      "HEAD"
    ],
    "AllowedHeaders": [
      "Content-Type"
    ]
  }
]
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

