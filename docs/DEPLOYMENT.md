# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Environment Variables Reference](#environment-variables-reference)
9. [Health Checks and Monitoring](#health-checks-and-monitoring)
10. [Scaling and Management](#scaling-and-management)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Package Type**: Executable JAR  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)

ResortsLite is a legacy resort booking REST API modernised for cloud-native deployment on AWS EKS. It uses Spring Session backed by Amazon ElastiCache (Redis) for distributed session management.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container build and run |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8+ | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR operations |
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
eks:DescribeCluster
eks:ListClusters
```

---

## Project Structure

```
testapp/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # AWS ALB ingress
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

### 1. Configure Environment Variables

Create a `.env` file in the project root:

```env
# Redis (use a local Redis container or point to ElastiCache)
REDIS_HOST=redis
REDIS_PORT=6379

# External service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge

# File paths
REPORT_BASE_PATH=/reports
BACKUP_PATH=/backups
SERVER_PORT=8080
```

> **Note**: The `docker-compose.yml` only starts the application container. You must provide Redis and other external services separately or update the `.env` to point to existing instances.

### 2. Build and Start the Application

```bash
# Build and start
docker-compose up --build

# Start in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type (AWS ECR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest

# Push to ECR
aws ecr get-login-password --region <REGION> | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com
docker push <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### Step 2: Create or Connect to EKS Cluster

**Create a new cluster (if needed):**
```bash
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4
```

**Connect to existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name <CLUSTER_NAME>
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller (for Ingress)

```bash
# Add EKS chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install AWS Load Balancer Controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Run the Deployment Script

**Linux / macOS:**
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

**Windows:**
```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS Region
- EKS Cluster Name
- Docker image URI (full path with tag)
- Environment variable values (REDIS_HOST, REDIS_PORT, PAYMENT_API_URL, etc.)

### Step 5: Manual Deployment (Alternative)

```bash
# 1. Apply namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Update image URI in deployment.yaml
sed -i 's|{{IMAGE_URI}}|<YOUR_IMAGE_URI>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|<REDIS_HOST>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/reports|g' kubernetes/deployment.yaml
sed -i 's|{{BACKUP_PATH}}|/backups|g' kubernetes/deployment.yaml

# 3. Apply manifests
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 4. Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# 5. Get ingress URL
kubectl get ingress resortslite-ingress -n resortslite
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (high availability)
- **Image**: Pulled from `{{IMAGE_URI}}` placeholder (replaced at deploy time)
- **Resources**: 250m CPU / 512Mi memory (requests); 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, every 15s
- **Graceful Shutdown**: 30-second termination grace period

### service.yaml
- **Type**: ClusterIP (internal cluster access only)
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Controller**: AWS ALB (Application Load Balancer)
- **Scheme**: internet-facing
- **Health Check Path**: `/actuator/health`
- **Host**: `resortslite.example.com` (update to your actual domain)

---

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `REDIS_HOST` | `localhost` | Redis/ElastiCache hostname |
| `REDIS_PORT` | `6379` | Redis/ElastiCache port |
| `PAYMENT_API_URL` | `http://payment-service:9090/payments/charge` | Payment service endpoint |
| `REPORT_BASE_PATH` | `/reports` | Report file storage path |
| `BACKUP_PATH` | `/backups` | Backup file storage path |
| `JAVA_OPTS` | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xms256m -Xmx512m` | JVM options |
| `TZ` | `UTC` | Container timezone |

### Setting Environment Variables in EKS

**Using Kubernetes ConfigMap:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: resortslite-config
  namespace: resortslite
data:
  REDIS_HOST: "my-elasticache.abc123.ng.0001.use1.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REPORT_BASE_PATH: "/reports"
  BACKUP_PATH: "/backups"
```

**Using Kubernetes Secret (for sensitive values):**
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: resortslite-secrets
  namespace: resortslite
type: Opaque
stringData:
  PAYMENT_API_URL: "https://payment-service.internal/charge"
```

---

## Health Checks and Monitoring

### Spring Boot Actuator Endpoints

| Endpoint | URL | Description |
|----------|-----|-------------|
| Health | `GET /actuator/health` | Application health status |
| Info | `GET /actuator/info` | Application information |

### Kubernetes Health Probes

The deployment configures:
- **Liveness Probe**: Restarts the container if `/actuator/health` fails 3 consecutive times
- **Readiness Probe**: Removes the pod from service load balancing if not ready

### JVM Monitoring

The application is configured with container-aware JVM settings:
```
-XX:+UseContainerSupport        # Respect container memory limits
-XX:MaxRAMPercentage=75.0       # Use 75% of container memory for heap
-XX:+UnlockExperimentalVMOptions
-Xms256m                        # Initial heap size
-Xmx512m                        # Maximum heap size
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite

# Check scaling status
kubectl get pods -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

```bash
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
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <POD_NAME> -n resortslite

# View pod logs
kubectl logs <POD_NAME> -n resortslite

# View previous container logs (if crashed)
kubectl logs <POD_NAME> -n resortslite --previous
```

### Common Issues

**1. ImagePullBackOff**
- Verify the image URI is correct
- Ensure ECR permissions are configured for the EKS node role
- Check: `kubectl describe pod <POD_NAME> -n resortslite`

**2. CrashLoopBackOff**
- Check application logs: `kubectl logs <POD_NAME> -n resortslite`
- Verify environment variables are set correctly (especially REDIS_HOST)
- Ensure Redis/ElastiCache is reachable from the EKS cluster

**3. Health Check Failures**
- JVM startup can take 30-60 seconds — increase `initialDelaySeconds` if needed
- Verify `/actuator/health` returns HTTP 200
- Check if Redis connection is healthy (Spring Session requires Redis)

**4. Ingress Not Getting External IP**
- Verify AWS Load Balancer Controller is installed
- Check controller logs: `kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller`
- Ensure EKS nodes have proper IAM permissions for ALB

**5. Redis Connection Errors**
- Verify `REDIS_HOST` and `REDIS_PORT` environment variables
- Ensure ElastiCache security group allows inbound on port 6379 from EKS node security group
- Test connectivity: `kubectl run redis-test --image=redis:alpine --rm -it -- redis-cli -h <REDIS_HOST> ping`

### Useful Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Execute shell in running pod
kubectl exec -it <POD_NAME> -n resortslite -- /bin/sh

# View resource usage
kubectl top pods -n resortslite
```

---

## Security Considerations

1. **Non-root Container**: The application runs as `appuser` (non-root) inside the container.

2. **Secrets Management**: 
   - Store sensitive values (database passwords, API keys) in Kubernetes Secrets or AWS Secrets Manager
   - Never hardcode credentials in environment variables or config files
   - Use AWS IAM roles for service accounts (IRSA) for AWS service access

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region <REGION>
   ```

5. **Resource Limits**: CPU and memory limits are set to prevent resource exhaustion.

6. **TLS/HTTPS**: Configure HTTPS on the ALB ingress for production:
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:<REGION>:<ACCOUNT>:certificate/<CERT_ID>
   ```

7. **Dependency Vulnerabilities**: The application currently includes vulnerable dependencies (log4j 2.14.1, commons-collections 3.2.1). **Upgrade these before production deployment**:
   - log4j-core → 2.17.2+
   - commons-collections → 3.2.2+

---

## Java-Specific Notes

- **Spring Boot 2.7.18** is the last 2.x release; consider upgrading to Spring Boot 3.x with Java 17+
- **Spring Session Redis** requires a running Redis instance; the application will fail to start without it
- **H2 In-Memory Database** is used for development; replace with a persistent database (RDS) for production
- **JVM Startup Time**: Java 8 applications may take 30-60 seconds to start; health probe `initialDelaySeconds` is set accordingly
- **Container Support**: `-XX:+UseContainerSupport` ensures the JVM respects container memory limits (available in Java 8u191+)
