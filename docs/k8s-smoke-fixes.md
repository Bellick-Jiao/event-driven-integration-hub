# Kubernetes Smoke Deploy — Troubleshooting Notes

> Real debugging notes from getting the CI `kind` smoke job green
> (commit `5982d2b`, run #11). Every issue below was reproduced locally on a
> kind cluster and fixed in `k8s/` — the point is not just "it works now",
> but *why each failure happened* and how to diagnose it next time.
>
> Context: the M4 milestone added a `smoke` job to GitHub Actions: boot a kind
> cluster, `kubectl apply -k k8s/`, create Kafka topics, wait for all
> rollouts, then prove the secured end-to-end path (Keycloak JWT → POST →
> 202). The first run failed in the **Deploy manifests** step (`exit code 1`)
> and GitHub log download requires admin rights, so diagnosis was done by
> **reproducing the exact job locally**.

## Method: reproduce, don't guess

```bash
kind create cluster --name hub-smoke          # same as helm/kind-action@v1
kubectl apply -k k8s/                          # apply the same manifests
kubectl -n hub rollout status statefulset/kafka --timeout=240s
kubectl -n hub rollout status deployment/keycloak --timeout=300s
```

Then inspect in this order:

```bash
kubectl -n hub get pods -o wide            # CrashLoopBackOff / CreateContainerConfigError?
kubectl -n hub get events --sort-by=.lastTimestamp   # the actual kubelet error
kubectl -n hub logs <pod> --tail=30        # app-level root cause
kubectl -n hub describe pod <pod>          # container config errors
```

Rule of thumb: `kubectl apply` almost never fails on content; the
**rollout status** times out because a pod never becomes Ready. Look at the
pod's *reason* first (events), not the logs.

## Issue 1 — `runAsNonRoot` vs official images

**Symptom**

```
Warning  Failed  pod/postgres-xxx  Error: container has runAsNonRoot and image will run as root
Warning  Failed  pod/kafka-0       Error: container has runAsNonRoot and image has non-numeric user (appuser)
```

**Root cause**

- `postgres:16-alpine` starts as `root` and switches to the `postgres` user
  itself (standard Docker pattern). With `runAsNonRoot: true` the kubelet
  refuses to start the container.
- `apache/kafka:4.0.0` declares `USER appuser` — a *non-numeric* user. K8s
  cannot verify at runtime that the resolved UID is non-zero, so it refuses
  even though the image actually runs as a normal user.

**Fix**

Remove `runAsNonRoot` from those two workloads (keep `seccompProfile`), and
document in the manifest why — production would use a hardened image or an
operator (CloudNativePG / Strimzi) instead.

**Interview angle**

"K8s image security has three layers: the image's own USER, the pod
securityContext, and admission policies. `runAsNonRoot` requires a numeric UID
to verify against — official images that drop privileges via gosu or a
non-numeric USER break that check. You verify images by their Dockerfile, not
by trust."

## Issue 2 — K8s `command` fully overrides the image entrypoint

**Symptom**

```
Error: failed to create containerd task: exec: "start-dev": executable file not found in $PATH
```

**Root cause**

`docker compose` appends `command` to the image `ENTRYPOINT`, but Kubernetes
`command` **replaces** the entrypoint entirely. Keycloak's entrypoint is
`/opt/keycloak/bin/kc.sh`, so the correct form is
`command: ["/opt/keycloak/bin/kc.sh", "start-dev", "--import-realm"]`.

**Fix**

Full path to the start script in `k8s/keycloak.yaml`.

## Issue 3 — Kafka (KRaft) startup DNS deadlock

**Symptom**

```
WARN ... Error connecting to node kafka:9093 ... java.net.UnknownHostException: kafka
```
then the broker exits; the pod never becomes Ready; `rollout status` times out.

**Root cause — a genuine chicken-and-egg cycle**

1. Kafka is a `StatefulSet` behind a **headless** Service (`clusterIP: None`).
2. A headless Service only publishes DNS A records for **Ready** pods.
3. The pod's readiness probe runs `kafka-topics.sh --list --bootstrap-server
   localhost:9092` — which works locally, but the broker's metadata then
   advertises `PLAINTEXT://kafka:9092`, and the probe client resolves that
   name → `UnknownHostException` → probe fails → pod stays NotReady.
4. Meanwhile the broker itself resolves its own controller quorum voter
   (`1@kafka:9093`) via the same not-yet-existing DNS record during startup
   and exits.

Not Ready → no DNS → cannot become Ready. Classic bootstrap deadlock.

**Fix (single-broker demo)**

- Controller quorum: `KAFKA_CONTROLLER_QUORUM_VOTERS=1@127.0.0.1:9093` — the
  broker is its own controller, so loopback is correct and DNS-independent.
- Advertised listener: `PLAINTEXT://$(POD_IP):9092` via the downward API —
  clients reach the broker directly by pod IP, no Service DNS involved.
- `hostAliases` was tried first but is unreliable when the kubelet restarts a
  failing container (the hosts file is not re-applied); the pod-IP approach
  removes the dependency entirely.

**Interview angle**

"Headless Service endpoints only exist for Ready pods, so any startup check
that resolves the Service name deadlocks. Break the cycle by advertising a
DNS-independent address (pod IP) and keeping the probe on loopback."

## Issue 4 — Keycloak token `iss` vs the validated issuer

**Symptom**

The API returns `401` even with a valid token. Health check passes; the token
decodes fine and contains `realm_access.roles: [BANKER]`.

**Root cause**

Keycloak derives the JWT `iss` claim from the **request Host header**. The
smoke job reaches Keycloak through `kubectl port-forward svc/keycloak
8088:8080`, so the token's issuer becomes:

```
iss: http://localhost:8088/realms/hub
```

while `integration-api` is configured to validate

```
http://keycloak:8080/realms/hub
```

Issuer mismatch → resource server rejects every token.

**Fix**

Pin Keycloak's frontend hostname so `iss` is stable regardless of how clients
connect:

```
--hostname=http://keycloak:8080 --hostname-strict=false
```

Two gotchas along the way (KC 26):

- `--hostname=keycloak:8080` (host:port form) is rejected — the value must be
  a plain hostname **or** a complete URL.
- `--hostname-strict-https` no longer exists in KC 26; use `--hostname-strict`
  and the URL form to control the scheme.

**Interview angle**

"Issuer validation is string-equality on the `iss` claim — but the JWKS is
fetched from that same issuer, so the issuer must also be *reachable* from the
service. Port-forwards and ingress rewrite the Host header, which silently
changes `iss`. Pinning the realm hostname is the standard fix; in production
the issuer URL would be set once per environment (DNS/ingress host)."

## Issue 5 — Smoke data violating the API validation rule

**Symptom**

After the security chain was fixed, POST returned `400` with
`application/problem+json`:

```json
{"status":400,"detail":"customerId: customerId must match CUS-XXXX", ...}
```

**Root cause**

The API validates `customerId` with the `CUS-XXXX` pattern (4 digits). The
smoke script used `CUS-SMOKE-1`, which fails validation — a test-data bug,
not a code bug.

**Fix**

`CUS-SMOKE-1` → `CUS-9001` in `.github/workflows/ci.yml`. The diagnostic
technique worth remembering: print the **full problem+json body** (not just
the status code) when a Spring API returns 4xx.

## Operational notes picked up along the way

1. **Stuck StatefulSet rolling updates**: after `kubectl apply` changes a
   StatefulSet pod template, a pod stuck in `CreateContainerConfigError`/`Error`
   can block the update (partitioned rollout waits for the old pod). Fix:
   `kubectl -n hub delete pod kafka-0` to force recreation with the new spec.
2. **`endpoints` is the ground truth for headless DNS**: `kubectl get
   endpoints kafka` showing no IPs explains every DNS-based failure at once.
3. **Deployment progress deadline**: a Deployment that never becomes Ready is
   marked failed after ~5 min; `kubectl rollout restart deployment/x` (not
   `apply`) is the right way to retry consumers whose dependencies came up
   later — CrashLoopBackOff pods do not auto-restart on dependency recovery.
4. **CI log access**: GitHub REST log download needs admin rights; for public
   repos the web UI shows annotations (`exit code 1` + step name) without a
   login, which is enough to point you at the failing step before reproducing
   locally.

## Final verification (run #11)

```
build:               success   (18 tests, Testcontainers Postgres/Kafka)
docker (x3):         success   (images pushed to GHCR)
smoke:               success   (kind: apply → topics → JWT → POST → 202; no-token → 401)
```

Local end-to-end before pushing: token decode shows `iss:
http://keycloak:8080/realms/hub`, `roles: BANKER`; `POST /api/v1/customers`
with token → `202 Accepted`; without token → `401`; `GET /customers/CUS-9001`
→ `200`.
