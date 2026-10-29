# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Analysis](#project-analysis)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration Management](#configuration-management)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**ResortsLite** is a Java 8 / Spring Boot 2.7.x resort booking REST API. It uses:
- **Spring Boot Actuator** for health checks (`/actuator/health`)
- **Spring Session + Redis** for distributed HTTP session management (Amazon ElastiCache)
- **Spring Data Redis** for booking cache (Amazon ElastiCache)
- **H2 in-memory database** for local development
- **Spring Boot Starter JDBC** for database access

The application is containerised as a single Docker image and deployed to **AWS EKS** (Elastic Kubernetes Service).

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Build and run containers |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local development (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR access |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

### AWS IAM Permissions Required
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:DescribeRepositories
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
eks:DescribeCluster
eks:ListClusters
```

---

## Project Analysis

| Property | Value |
|----------|-------|
| Framework | Spring Boot 2.7.18 |
| Java Version | 8 |
| Build Tool | Maven |
| Package Type | JAR (executable) |
| Application Port | 8080 |
| Health Endpoint | `/actuator/health` |
| Builder Image | `maven:3.8.6-openjdk-8-slim` |
| Runtime Image | `eclipse-temurin:8-jre` |
| Session Store | Amazon ElastiCache (Redis) via Spring Session |
| Cache Store | Amazon ElastiCache (Redis) via RedisTemplate |

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Redis connection (use localhost for local Redis, or ElastiCache endpoint for AWS)
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379
SPRING_REDIS_PASSWORD=

# Payment service endpoint
PAYMENT_API_URL=http://payment-svc:9090/payments/charge

# Internal service endpoints
APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# File paths
APP_REPORT_BASE_PATH=/var/reports/
APP_BACKUP_PATH=/var/backups/
```

> **Note**: The application requires a Redis instance for Spring Session and booking cache.
> For local development, start a Redis container separately:
> ```bash
> docker run -d --name redis -p 6379:6379 redis:7-alpine
> ```

### 2. Build and Start the Application

```bash
# Build and start the application container
docker-compose up --build

# Run in background
docker-compose up --build -d

# View logs
docker-compose logs -f resortslite

# Stop the application
docker-compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking creation
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from repository root
./scripts/build-push.sh
```

The script will prompt for:
1. Registry type (AWS ECR or Docker Hub)
2. Registry credentials and details
3. Image tag (defaults to `latest`)

### Windows

```cmd
# Run from repository root
scripts\build-push.bat
```

### Manual Build (AWS ECR)

```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=123456789012
ECR_REPO=resortslite
IMAGE_TAG=1.0.0

# Authenticate with ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

# Create repository (if it doesn't exist)
aws ecr create-repository --repository-name $ECR_REPO --region $AWS_REGION

# Build and push
docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG} .
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}
```

---

## AWS EKS Deployment

### Step 1: Create or Connect to EKS Cluster

```bash
# Create a new EKS cluster (if needed)
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4

# Or connect to an existing cluster
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

### Step 2: Install AWS Load Balancer Controller

The Ingress resource uses the AWS Load Balancer Controller. Install it on your EKS cluster:

```bash
# Add the EKS chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the AWS Load Balancer Controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3: Set Up Amazon ElastiCache (Redis)

ResortsLite requires a Redis instance for Spring Session and booking cache:

```bash
# Create ElastiCache Redis cluster (via AWS Console or CLI)
aws elasticache create-cache-cluster \
  --cache-cluster-id resortslite-redis \
  --cache-node-type cache.t3.micro \
  --engine redis \
  --num-cache-nodes 1 \
  --region us-east-1
```

Note the ElastiCache primary endpoint for use in the deployment step.

### Step 4: Deploy to EKS

#### Using the Deployment Script (Recommended)

```bash
# Linux / macOS
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh

# Windows
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region and EKS cluster name
- Full Docker image URI (from ECR or Docker Hub)
- Redis/ElastiCache connection details
- Service endpoint URLs

#### Manual Deployment

```bash
# Set the image URI in deployment.yaml
IMAGE_URI="123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:1.0.0"

# Substitute placeholders
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_REDIS_HOST}}|your-elasticache-endpoint.cache.amazonaws.com|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_REDIS_PORT}}|6379|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_REDIS_PASSWORD}}||g" kubernetes/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|http://payment-svc:9090/payments/charge|g" kubernetes/deployment.yaml
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|http://payment-svc.internal:9090/charge|g" kubernetes/deployment.yaml
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|http://inventory-svc.internal:8081/rooms|g" kubernetes/deployment.yaml
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|http://notify.internal:7070/send|g" kubernetes/deployment.yaml

# Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# Verify
kubectl get pods,svc,ingress -n resortslite
```

### Step 5: Verify Deployment

```bash
# Check pod status
kubectl get pods -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Get ingress hostname
kubectl get ingress resortslite-ingress -n resortslite

# Test health endpoint (replace with actual ALB hostname)
curl http://<ALB_HOSTNAME>/actuator/health
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
Deploys 2 replicas of the ResortsLite container with:
- **Image**: Configured via `{{IMAGE_URI}}` placeholder
- **Resources**: 250m CPU / 512Mi memory (requests), 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, checks every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, checks every 15s
- **Environment Variables**: Redis connection, service endpoints, JVM options
- **Volumes**: EmptyDir volumes for `/var/reports` and `/var/backups`
- **Security**: Runs as non-root user (UID 1000)

### service.yaml
Creates a `ClusterIP` service exposing port 80 → container port 8080.

### ingress.yaml
Creates an AWS ALB Ingress with:
- Internet-facing scheme
- IP target type (for EKS with VPC CNI)
- Health check path: `/actuator/health`
- Host: `resortslite.example.com` (update to your actual domain)

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SPRING_REDIS_HOST` | ElastiCache primary endpoint | `localhost` |
| `SPRING_REDIS_PORT` | ElastiCache port | `6379` |
| `SPRING_REDIS_PASSWORD` | ElastiCache auth token | _(empty)_ |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-svc:9090/payments/charge` |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | `http://payment-svc.internal:9090/charge` |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | `http://inventory-svc.internal:8081/rooms` |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | `http://notify.internal:7070/send` |
| `APP_REPORT_BASE_PATH` | Report file directory | `/var/reports/` |
| `APP_BACKUP_PATH` | Backup file directory | `/var/backups/` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` |

### Using Kubernetes Secrets for Sensitive Values

```bash
# Create a secret for Redis password
kubectl create secret generic resortslite-secrets \
  --from-literal=SPRING_REDIS_PASSWORD=your-redis-password \
  -n resortslite

# Reference in deployment.yaml
# env:
#   - name: SPRING_REDIS_PASSWORD
#     valueFrom:
#       secretKeyRef:
#         name: resortslite-secrets
#         key: SPRING_REDIS_PASSWORD
```

### Using AWS Secrets Manager with IRSA

```bash
# Create IAM role for service account (IRSA)
eksctl create iamserviceaccount \
  --name resortslite-sa \
  --namespace resortslite \
  --cluster resortslite-cluster \
  --attach-policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite \
  --approve
```

---

## Scaling and Management

### Horizontal Pod Autoscaling

```bash
# Create HPA based on CPU utilization
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

# Check HPA status
kubectl get hpa -n resortslite
```

### Rolling Updates

```bash
# Update the image
kubectl set image deployment/resortslite \
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:2.0.0 \
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
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

### Scaling Replicas

```bash
# Scale to 4 replicas
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod events
kubectl describe pod -l app=resortslite -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --previous

# Check resource constraints
kubectl top pods -n resortslite
```

### Redis Connection Issues

```bash
# Verify Redis environment variables
kubectl exec -it $(kubectl get pod -l app=resortslite -n resortslite -o jsonpath='{.items[0].metadata.name}') \
  -n resortslite -- env | grep SPRING_REDIS

# Check if ElastiCache is reachable from the pod
kubectl exec -it $(kubectl get pod -l app=resortslite -n resortslite -o jsonpath='{.items[0].metadata.name}') \
  -n resortslite -- sh -c "cat /dev/tcp/$SPRING_REDIS_HOST/$SPRING_REDIS_PORT && echo 'Redis reachable'"
```

### Health Check Failures

```bash
# Check actuator health endpoint
kubectl exec -it $(kubectl get pod -l app=resortslite -n resortslite -o jsonpath='{.items[0].metadata.name}') \
  -n resortslite -- wget -qO- http://localhost:8080/actuator/health

# Check liveness/readiness probe events
kubectl describe pod -l app=resortslite -n resortslite | grep -A 5 "Liveness\|Readiness"
```

### Ingress / ALB Issues

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ALB target group health
aws elbv2 describe-target-health \
  --target-group-arn <TARGET_GROUP_ARN> \
  --region us-east-1
```

### JVM Memory Issues

```bash
# Check container memory usage
kubectl top pods -n resortslite

# Increase memory limits in deployment.yaml if OOMKilled
# resources:
#   limits:
#     memory: "2Gi"
```

---

## Security Considerations

1. **Non-root Container**: The application runs as UID 1000 (non-root) inside the container.

2. **Secrets Management**: Use Kubernetes Secrets or AWS Secrets Manager for sensitive values (Redis password, API keys). Never hardcode credentials.

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities in the container image.
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA instead of static AWS credentials for ElastiCache and other AWS service access.

6. **TLS/HTTPS**: Update the Ingress to use HTTPS with ACM certificates:
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789012:certificate/xxx
   ```

7. **Dependency Vulnerabilities**: The project contains known vulnerable dependencies (log4j 2.14.1 CVE-2021-44228, commons-collections 3.2.1 CVE-2015-6420). Upgrade these before production deployment.

8. **Read-only Root Filesystem**: Consider adding `readOnlyRootFilesystem: true` to the container security context (requires volume mounts for writable paths).

---

## Java-Specific Notes

### JVM Container Awareness
The application uses `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` to ensure the JVM respects container memory limits rather than using host memory as the basis for heap sizing.

### Spring Boot Actuator
Health checks use `/actuator/health` which is exposed via `management.endpoints.web.exposure.include=health,info` in `application.properties`. The Kubernetes liveness and readiness probes both use this endpoint.

### Spring Session Redis
All HTTP session data is stored in Amazon ElastiCache (Redis) via Spring Session. This enables:
- Session sharing across all pod replicas
- Session persistence across container restarts
- Horizontal scaling without sticky sessions

### Graceful Shutdown
The JVM is started with `exec java ...` to ensure proper signal handling (SIGTERM) for graceful shutdown. The `terminationGracePeriodSeconds: 30` in the deployment gives the application 30 seconds to complete in-flight requests.
