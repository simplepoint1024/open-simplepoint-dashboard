package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
)

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  map[string]any  `json:"params"`
}

func main() {
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	encoder := json.NewEncoder(os.Stdout)
	for scanner.Scan() {
		var message request
		if json.Unmarshal(scanner.Bytes(), &message) != nil || len(message.ID) == 0 {
			continue
		}
		response := map[string]any{
			"jsonrpc": "2.0",
			"id":      message.ID,
		}
		result, code := dispatch(message)
		if code == 0 {
			response["result"] = result
		} else {
			response["error"] = map[string]any{
				"code":    code,
				"message": "Method not found",
			}
		}
		_ = encoder.Encode(response)
	}
}

func dispatch(message request) (map[string]any, int) {
	switch message.Method {
	case "initialize":
		return map[string]any{
			"protocolVersion": "2025-11-25",
			"capabilities": map[string]any{
				"tools": map[string]any{"listChanged": false},
			},
			"serverInfo": map[string]any{
				"name":    "simplepoint-stdio-smoke",
				"version": "1.0.0",
			},
		}, 0
	case "ping":
		return map[string]any{}, 0
	case "tools/list":
		return map[string]any{
			"tools": []map[string]any{{
				"name":        "echo",
				"title":       "Echo",
				"description": "Returns the supplied message.",
				"inputSchema": map[string]any{
					"type": "object",
					"properties": map[string]any{
						"message": map[string]any{"type": "string"},
					},
					"required": []string{"message"},
				},
			}},
		}, 0
	case "tools/call":
		return toolCall(message.Params), 0
	default:
		return nil, -32601
	}
}

func toolCall(params map[string]any) map[string]any {
	arguments, _ := params["arguments"].(map[string]any)
	message := fmt.Sprint(arguments["message"])
	return map[string]any{
		"content": []map[string]any{{
			"type": "text",
			"text": message,
		}},
		"isError": false,
	}
}
