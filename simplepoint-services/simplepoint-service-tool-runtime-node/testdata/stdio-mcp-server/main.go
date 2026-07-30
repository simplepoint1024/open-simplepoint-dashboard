package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
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
				"tools":     map[string]any{"listChanged": false},
				"prompts":   map[string]any{"listChanged": false},
				"resources": map[string]any{"listChanged": false, "subscribe": false},
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
	case "prompts/list":
		return map[string]any{
			"prompts": []map[string]any{{
				"name":        "welcome",
				"title":       "Welcome",
				"description": "Creates a concise welcome prompt.",
				"arguments": []map[string]any{{
					"name":        "name",
					"description": "Name to welcome.",
					"required":    false,
				}},
			}},
		}, 0
	case "prompts/get":
		return promptGet(message.Params), 0
	case "resources/list":
		return map[string]any{
			"resources": []map[string]any{{
				"uri":         "simplepoint://status",
				"name":        "Runtime status",
				"title":       "SimplePoint runtime status",
				"description": "Static status exposed by the managed MCP fixture.",
				"mimeType":    "application/json",
			}},
		}, 0
	case "resources/templates/list":
		return map[string]any{
			"resourceTemplates": []map[string]any{{
				"uriTemplate": "document://{documentId}",
				"name":        "Document",
				"title":       "Document by identifier",
				"description": "Reads one fixture document by identifier.",
				"mimeType":    "text/plain",
			}},
		}, 0
	case "resources/read":
		return resourceRead(message.Params), 0
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
		"isError":           false,
		"structuredContent": map[string]any{"message": message},
	}
}

func promptGet(params map[string]any) map[string]any {
	arguments, _ := params["arguments"].(map[string]any)
	name := strings.TrimSpace(fmt.Sprint(arguments["name"]))
	if name == "" || name == "<nil>" {
		name = "SimplePoint"
	}
	return map[string]any{
		"description": "A concise welcome prompt.",
		"messages": []map[string]any{{
			"role": "user",
			"content": map[string]any{
				"type": "text",
				"text": "Welcome " + name + " to the MCP workbench.",
			},
		}},
	}
}

func resourceRead(params map[string]any) map[string]any {
	uri := fmt.Sprint(params["uri"])
	text := "managed MCP runtime is ready"
	mimeType := "application/json"
	if strings.HasPrefix(uri, "document://") {
		documentID := strings.TrimPrefix(uri, "document://")
		text = "Document " + documentID + " from the managed MCP runtime."
		mimeType = "text/plain"
	}
	return map[string]any{
		"contents": []map[string]any{{
			"uri":      uri,
			"mimeType": mimeType,
			"text":     text,
		}},
	}
}
