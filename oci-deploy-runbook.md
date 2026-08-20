# OCI Deploy Runbook — Apollo Agent
> All commands below have been validated locally. Paste them in order once the OCI VM exists.  
> Placeholders you must replace are written as `<ANGLE_BRACKET_LABELS>`.

---

## 0. Pre-flight: have these ready before you start

| Item | Status |
|------|--------|
| OCI VM public IP | `<PUBLIC_IP>` — from OCI Console |
| SSH key (private) | `~/.ssh/id_rsa` (or your actual path) |
| Ollama model name | `qwen2.5:72b-instruct-q4_K_M` (production) |
| nginx auth token | generate locally: `openssl rand -hex 32` → paste below |
| Docker installed on VM | confirmed via steps below |

Generate and **save** your nginx token now:
```bash
# Run this on your LOCAL machine. Save the output — you'll need it twice.
openssl rand -hex 32
# Example output (yours will differ): a3f9c2e8d1b47f0a6e5c3d9271b4f8a0e7c2d6b1a9f3e0c8d5b2a7f4e1c6d3b0
```

---

## 1. Connect to the OCI VM

```bash
ssh -i ~/.ssh/id_rsa opc@<PUBLIC_IP>
```

---

## 2. Install Docker on the OCI VM (Oracle Linux)

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker opc
# Log out and back in to pick up the group
exit
ssh -i ~/.ssh/id_rsa opc@<PUBLIC_IP>
# Verify
docker run --rm hello-world
```

---

## 3. Install Ollama + systemd service override

```bash
# Install Ollama (official script)
curl -fsSL https://ollama.com/install.sh | sh

# Make sure Ollama listens on all interfaces (not just loopback),
# so nginx can proxy to it
sudo mkdir -p /etc/systemd/system/ollama.service.d
sudo tee /etc/systemd/system/ollama.service.d/override.conf <<'EOF'
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now ollama

# Verify Ollama responds
curl http://localhost:11434/api/tags
# Expected: {"models":[]} — empty model list before first pull
```

---

## 4. Pull the production model

```bash
# This takes 10–40 min depending on network speed; pipe through tee to log it
ollama pull qwen2.5:72b-instruct-q4_K_M 2>&1 | tee ~/ollama-pull.log

# Verify the model loaded
curl http://localhost:11434/api/tags | python3 -m json.tool
# Expected: model entry with name "qwen2.5:72b-instruct-q4_K_M"

# Quick smoke-test (will take ~30s on first run)
curl http://localhost:11434/api/generate \
  -d '{"model":"qwen2.5:72b-instruct-q4_K_M","prompt":"test","stream":false}'
# Expected: JSON with "response" field containing text
```

---

## 5. Install nginx + auth proxy config

```bash
sudo dnf install -y nginx

# Write the site config
sudo tee /etc/nginx/conf.d/ollama-proxy.conf <<'NGINX'
server {
    listen 80;
    server_name <PUBLIC_IP>;

    # ── Ollama auth proxy ────────────────────────────────────────────────────
    location /ollama/ {
        # Block requests missing the correct X-Api-Key header
        if ($http_x_api_key != "<YOUR_NGINX_TOKEN>") {
            return 401 "Unauthorized\n";
        }

        # Strip the /ollama prefix and proxy to local Ollama
        rewrite ^/ollama/(.*)$ /$1 break;
        proxy_pass         http://127.0.0.1:11434;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }

    # ── Apollo MCP / health endpoint ─────────────────────────────────────────
    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_read_timeout 300s;
    }
}
NGINX

# Test config syntax
sudo nginx -t
# Expected: "syntax is ok" and "test is successful"

sudo systemctl enable --now nginx
```

---

## 6. Validate the nginx auth proxy (equivalent of Section 3 local test)

```bash
# ── Test 1: No auth header → must get 401 ────────────────────────────────────
curl -s -o /dev/null -w "%{http_code}" \
  http://<PUBLIC_IP>/ollama/api/tags
# Expected: 401

# ── Test 2: Wrong token → must get 401 ───────────────────────────────────────
curl -s -o /dev/null -w "%{http_code}" \
  -H "X-Api-Key: wrongtoken" \
  http://<PUBLIC_IP>/ollama/api/tags
# Expected: 401

# ── Test 3: Correct token → must get 200 + model list ────────────────────────
curl -s \
  -H "X-Api-Key: <YOUR_NGINX_TOKEN>" \
  http://<PUBLIC_IP>/ollama/api/tags | python3 -m json.tool
# Expected: JSON model list

# ── Test 4: Full generate through proxy ──────────────────────────────────────
curl -s \
  -H "X-Api-Key: <YOUR_NGINX_TOKEN>" \
  -d '{"model":"qwen2.5:72b-instruct-q4_K_M","prompt":"test","stream":false}' \
  http://<PUBLIC_IP>/ollama/api/generate
# Expected: JSON with "response" field
```

---

## 7. OCI Security List & Firewall — required ingress rules

In the OCI Console → Networking → Virtual Cloud Networks → your VCN → Security Lists, add these Ingress Rules:

| Direction | Protocol | Source CIDR | Port | Description |
|-----------|----------|-------------|------|-------------|
| Ingress   | TCP      | `<YOUR_IP>/32` or `0.0.0.0/0` | 80   | nginx (Ollama proxy + Apollo MCP) |
| Ingress   | TCP      | `<YOUR_IP>/32` or `0.0.0.0/0` | 22   | SSH |

> **CRITICAL WARNING:** Your actual OCI deployment might fail if the Source CIDR doesn't precisely match your current public IP (use `curl ifconfig.me` to check) or `0.0.0.0/0` if you want it globally open. 
> **Do NOT** open port 11434 directly in the Security List — Ollama must be reachable only through nginx.

Also run the OS firewall commands (Oracle Linux uses both firewalld and iptables by default). The original deployment failed due to missing iptables rules, so you MUST run all of these:
```bash
sudo firewall-cmd --permanent --add-service=http
sudo firewall-cmd --permanent --add-service=ssh
# Oracle Linux often requires explicit iptables rules for container port forwarding:
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8080 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 11434 -j ACCEPT
sudo netfilter-persistent save || sudo iptables-save | sudo tee /etc/sysconfig/iptables
sudo firewall-cmd --reload
```

---

## 8. Build and deploy Apollo Docker container

```bash
# On your LOCAL machine: build and save the image
docker build -t apollo-agent:prod .
docker save apollo-agent:prod | gzip > apollo-agent.tar.gz

# Transfer to OCI VM
scp -i ~/.ssh/id_rsa apollo-agent.tar.gz opc@<PUBLIC_IP>:~/

# On the OCI VM: load and run
ssh -i ~/.ssh/id_rsa opc@<PUBLIC_IP>
docker load < ~/apollo-agent.tar.gz

# Create production .env on the VM (paste your actual values)
cat > ~/apollo.env <<'EOF'
LLM_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434/v1
OLLAMA_API_KEY=
OLLAMA_MODEL=qwen2.5:72b-instruct-q4_K_M
SANDBOX_DOCKER_ENABLED=false
MAX_RETRY_COUNT=3
EOF

# Run in server (MCP) mode
docker run -d \
  --name apollo-mcp \
  --restart unless-stopped \
  -p 8080:8080 \
  --env-file ~/apollo.env \
  apollo-agent:prod \
  bin/apollo-agent --server

# Verify container is running
docker logs apollo-mcp --tail 20
# Expected: "[Apollo-MCP] Starting Apollo Streamable HTTP MCP Server on port 8080..."
```

---

## 9. End-to-end smoke test from local machine

```bash
# From YOUR LOCAL machine, confirm MCP server is reachable through nginx
curl -s http://<PUBLIC_IP>/   
# Expected: Apollo banner text or MCP JSON-RPC handshake

# Full migration test (replace with actual project path on the VM if needed)
curl -s -X POST http://<PUBLIC_IP>/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"migrate_java_project","arguments":{"projectPath":"sample-legacy"}}}'
```

---

## What genuinely cannot be validated locally

| Item | Why local can't prove it |
|------|--------------------------|
| Inference speed at 72B model | Local CPU has no baseline; OCI's multi-OCPU throughput only measurable in prod |
| Ollama GPU acceleration (if OCI GPU shape used) | No GPU locally |
| Real-network latency Apollo→nginx→Ollama | Localhost proxy masks this |
| `OLLAMA_HOST=0.0.0.0` binding on Oracle Linux's SELinux/firewalld | SELinux policy may block inter-process TCP even on localhost — test on the actual VM |
| Docker image pull time over OCI's default registry | Simulated via local `docker save/load` |
| Nginx keepalive under 300s production requests | Local curl stays alive; real 72B inference may exceed proxy timeout |
