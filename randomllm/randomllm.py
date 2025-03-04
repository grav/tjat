from flask import Flask, request, jsonify
import random
import time
import uuid
import sys
from flask_cors import CORS

app = Flask(__name__)
# Enable CORS for all routes
CORS(app, resources={r"/*": {"origins": "*"}})

# List of possible random responses
random_responses = [
    "I'm feeling quite random today!",
    "Did you know that randomness is a complex concept?",
    "Here's your random response as requested.",
    "Generating randomness is surprisingly difficult for computers.",
    "Random fact: honey never spoils.",
    "In a truly random sequence, patterns can still appear by chance.",
    "Random thought: what if clouds had consciousness?",
    "Sometimes the most random responses are the most interesting.",
    "Randomness is the spice of artificial intelligence.",
    "Beep boop. Random response activated."
]

@app.route('/v1/chat/completions', methods=['POST', 'OPTIONS'])
def chat_completions():
    # Handle preflight OPTIONS request
    if request.method == 'OPTIONS':
        response = app.make_default_options_response()
        response.headers.add('Access-Control-Allow-Headers', 'Content-Type,Authorization')
        response.headers.add('Access-Control-Allow-Methods', 'POST,OPTIONS')
        return response

    data = request.json

    # Extract some data from the request to make the response format match OpenAI's
    model = data.get('model', 'random-response-model')

    # Generate a random response
    response_content = random.choice(random_responses)

    # Create response in OpenAI format
    response = {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": response_content
                },
                "finish_reason": "stop"
            }
        ],
        "usage": {
            "prompt_tokens": 0,
            "completion_tokens": len(response_content.split()),
            "total_tokens": len(response_content.split())
        }
    }

    return jsonify(response)

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=sys.argv[1] if len(sys.argv) >1 else "8042")
