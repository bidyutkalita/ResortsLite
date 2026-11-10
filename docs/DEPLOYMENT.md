# ResortsLite — Deployment Guide

## Overview

**ResortsLite** is a Java 8 / Spring Boot 2.7.x resort booking REST API. It uses:
- **Spring Boot Actuator** for health checks (`/actuator/health`)
- **Spring Session + Redis (Amazon ElastiCache)** for distributed session management
- **H2 in-memory database** (development) / configurable JDBC datasource (production)
- **AWS EKS** as the target Kubernetes deployment platform

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Kubernetes Deployment](#kubernetes-deployment)
8. [Environment Variables Reference](#environment-variables-reference)
9. [Health Checks and Monitoring](#health-checks-and-monitoring)
10. [Scaling and Rolling Updates](#scaling-and-rolling-updates)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 24.x+ | Container runtime |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local build (optional if using Docker) |
| Maven | 3.8+ | Local build (optional if using Docker) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR interaction |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.160+ | EKS cluster provisioning (optional) |

---

## Project Structure

```
ResortsLite/
├── Dockerfile                    # Multi-stage Docker build (Java 8)
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── RedisSessionConfig.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS EKS deploy
│   └── deploy-image.bat          # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Create a `.env` file (optional overrides)

```bash
# .env
REDIS_HOST=localhost
REDIS_PORT=6379
SESSION_TIMEOUT_SECONDS=1800
PAYMENT_API_URL=http://payment-service:9090/payments/charge
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly
```

> **Note:** For local development, you need a Redis instance. You can run one separately:
> ```bash
> docker run -d --name redis -p 6379:6379 redis:7-alpine
> ```

### 2. Build and start the application

```bash
# From repository root
docker compose up --build
```

### 3. Verify the application is running

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-06-01&checkOut=2024-06-05"
```

### 4. Stop the application

```bash
docker compose down
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from repository root
./scripts/build-push.sh
```

The script will prompt for:
1. Registry type (AWS ECR or Docker Hub)
2. Registry credentials / AWS account details
3. Image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (Advanced)

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest <AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest

# Authenticate with ECR
aws ecr get-login-password --region <REGION> | \
  docker login --username AWS --password-stdin <AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com

# Push
docker push <AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest
```

---

## AWS EKS Prerequisites

### 1. IAM Permissions

Ensure your AWS IAM user/role has the following permissions:
- `eks:DescribeCluster`
- `eks:UpdateKubeconfig`
- `ecr:GetAuthorizationToken`
- `ecr:BatchCheckLayerAvailability`
- `ecr:GetDownloadUrlForLayer`
- `ecr:BatchGetImage`
- `ecr:CreateRepository`
- `ecr:PutImage`

### 2. Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 3. Verify AWS CLI access

```bash
aws sts get-caller-identity
```

### 4. Install kubectl

```bash
# Linux
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# macOS
brew install kubectl

# Windows
choco install kubernetes-cli
```

---

## EKS Cluster Setup

### Option A: Use Existing Cluster

```bash
# Configure kubectl to use your EKS cluster
aws eks update-kubeconfig --region <REGION> --name <CLUSTER_NAME>

# Verify connectivity
kubectl cluster-info
kubectl get nodes
```

### Option B: Create New EKS Cluster (eksctl)

```bash
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed
```

### Install AWS Load Balancer Controller (for Ingress)

```bash
# Add Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

---

## Kubernetes Deployment

### Automated Deployment (Recommended)

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region and EKS cluster name
- Full Docker image URI (with tag)
- Application environment variables (Redis host, payment API URL, etc.)

### Manual Deployment

```bash
# 1. Create namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Update deployment.yaml with your image URI
# Replace {{IMAGE_URI}} and other placeholders manually or with sed:
sed -i 's|{{IMAGE_URI}}|<YOUR_IMAGE_URI>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|<YOUR_REDIS_HOST>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{SESSION_TIMEOUT_SECONDS}}|1800|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/var/reports|g' kubernetes/deployment.yaml
sed -i 's|{{BACKUP_PATH}}|/var/backups/nightly|g' kubernetes/deployment.yaml

# 3. Apply manifests
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 4. Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# 5. Verify
kubectl get pods,svc,ingress -n resortslite
```

---

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `REDIS_HOST` | `localhost` | Amazon ElastiCache primary endpoint |
| `REDIS_PORT` | `6379` | ElastiCache port |
| `SESSION_TIMEOUT_SECONDS` | `1800` | HTTP session max-inactive interval (seconds) |
| `PAYMENT_API_URL` | `http://payment-service:9090/payments/charge` | Payment service endpoint |
| `REPORT_BASE_PATH` | `/var/reports` | Base directory for report files |
| `BACKUP_PATH` | `/var/backups/nightly` | Base directory for backup files |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM startup options |

### Amazon ElastiCache (Redis) Setup

The application requires a Redis-compatible endpoint for session storage. For AWS EKS:

1. Create an ElastiCache Redis cluster in the same VPC as your EKS cluster
2. Configure the security group to allow inbound port 6379 from EKS node security group
3. Set `REDIS_HOST` to the ElastiCache primary endpoint
4. Use IRSA (IAM Roles for Service Accounts) for credential-free access

---

## Health Checks and Monitoring

### Health Endpoint

The application exposes Spring Boot Actuator health endpoint:

```
GET /actuator/health
```

Example response:
```json
{
  "status": "UP",
  "components": {
    "redis": { "status": "UP" },
    "db": { "status": "UP" }
  }
}
```

### Kubernetes Probes

The deployment is configured with:
- **Liveness probe**: `GET /actuator/health` — restarts pod if unhealthy
- **Readiness probe**: `GET /actuator/health` — removes pod from load balancer if not ready
- **Initial delay**: 60s (liveness), 30s (readiness) — allows JVM startup time

### Check Pod Status

```bash
# List pods
kubectl get pods -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Describe pod (events, resource usage)
kubectl describe pod <POD_NAME> -n resortslite
```

---

## Scaling and Rolling Updates

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

### Rolling Update (New Image)

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<NEW_IMAGE_URI> \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite --to-revision=<N> -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod events
kubectl describe pod <POD_NAME> -n resortslite

# Check logs
kubectl logs <POD_NAME> -n resortslite --previous
```

**Common causes:**
- `ImagePullBackOff`: ECR authentication issue or wrong image URI
- `CrashLoopBackOff`: Application startup failure (check logs for Redis connection errors)
- `OOMKilled`: Increase memory limits in `kubernetes/deployment.yaml`

### Redis Connection Failure

```bash
# Verify REDIS_HOST is set correctly
kubectl exec -it <POD_NAME> -n resortslite -- env | grep REDIS

# Test Redis connectivity from pod
kubectl exec -it <POD_NAME> -n resortslite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

**Common causes:**
- ElastiCache security group not allowing inbound from EKS nodes
- Wrong `REDIS_HOST` value (use primary endpoint, not reader endpoint)
- ElastiCache cluster in different VPC or subnet

### Ingress Not Accessible

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system deployment/aws-load-balancer-controller
```

**Common causes:**
- AWS Load Balancer Controller not installed
- Missing IAM permissions for ALB creation
- Subnet tags missing (`kubernetes.io/role/elb: 1`)

### Application Returns 503

```bash
# Check if pods are ready
kubectl get pods -n resortslite

# Check service endpoints
kubectl get endpoints resortslite-service -n resortslite
```

---

## Security Considerations

1. **Secrets Management**: Store sensitive values (Redis auth tokens, API keys) in AWS Secrets Manager and inject via Kubernetes External Secrets Operator — never hardcode in manifests.

2. **IRSA (IAM Roles for Service Accounts)**: Use IRSA for credential-free access to AWS services (ElastiCache, S3, etc.) from EKS pods.

3. **Network Policies**: Restrict pod-to-pod communication using Kubernetes NetworkPolicy resources.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities in the container image.

5. **Non-root Container**: The Dockerfile runs the application as a non-root user (`appuser`) for reduced attack surface.

6. **TLS/HTTPS**: Configure the ALB Ingress with HTTPS by adding the following annotations:
   ```yaml
   alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:<REGION>:<ACCOUNT>:certificate/<CERT_ID>
   ```

7. **Resource Limits**: CPU and memory limits are set to prevent resource exhaustion attacks.

8. **Dependency Vulnerabilities**: The project currently includes known vulnerable dependencies (log4j 2.14.1 CVE-2021-44228, commons-collections 3.2.1 CVE-2015-6420). **Update these before production deployment.**

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m        # Initial heap size
-Xmx512m        # Maximum heap size
-XX:+UseContainerSupport    # Container-aware memory detection
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for JVM
```

Adjust these values in `kubernetes/deployment.yaml` under the `JAVA_OPTS` environment variable based on your workload.

### Spring Boot Startup Time

Java 8 + Spring Boot 2.7.x typically takes 15–30 seconds to start. The Kubernetes probes are configured with:
- `initialDelaySeconds: 60` for liveness (generous buffer for JVM startup)
- `initialDelaySeconds: 30` for readiness

If your pods are being killed before startup completes, increase `initialDelaySeconds`.

### Spring Profiles

The `SPRING_PROFILES_ACTIVE=docker` environment variable activates the Docker profile. Create `application-docker.properties` or `application-docker.yml` for Docker/Kubernetes-specific overrides.
