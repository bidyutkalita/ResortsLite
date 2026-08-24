# ResortsLite – AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build & Push Docker Image](#build--push-docker-image)
6. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
7. [ECS Task Definition Explained](#ecs-task-definition-explained)
8. [ECS Service Configuration](#ecs-service-configuration)
9. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
10. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
11. [ECS Fargate Scaling & Management](#ecs-fargate-scaling--management)
12. [Configuration Management](#configuration-management)
13. [Security Considerations](#security-considerations)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: AWS ECS Fargate  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS ECS Fargate. It exposes a REST API for booking management and integrates with external payment, inventory, and notification services via environment-variable-driven endpoints.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9.x | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | Interact with AWS services |
| Docker | 24.x+ | Build and push images |
| Python 3 | 3.8+ | Used by deploy-image.sh for JSON manipulation |

---

## Project Structure

```
ResortsLite/
├── Dockerfile                    # Multi-stage build (Maven builder + JRE runtime)
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Files excluded from Docker build context
├── pom.xml                       # Maven project descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json      # ECS Fargate task definition
│   └── service-definition.json   # ECS Fargate service definition
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push script
│   ├── build-push.bat            # Windows build & push script
│   ├── deploy-image.sh           # Linux/macOS ECS deployment script
│   └── deploy-image.bat          # Windows ECS deployment script
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Build and start the application

```bash
# From the project root
docker compose up --build
```

### 2. Verify the application is running

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check booking status
curl http://localhost:8080/api/bookings/status/BK-XXXXXXXX
```

### 3. Override environment variables

Create a `.env` file in the project root:

```env
PAYMENT_API_URL=http://your-payment-service:9090/payments/charge
APP_INVENTORY_ENDPOINT=http://your-inventory-service:8081/rooms
REDIS_HOST=your-redis-host
REDIS_PORT=6379
```

Then run:
```bash
docker compose --env-file .env up
```

### 4. Stop the application

```bash
docker compose down
```

---

## Build & Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials and details
3. Specify an image tag (defaults to `latest`)

The script automatically:
- Sanitises the image name to lowercase with hyphens
- Creates the ECR repository if it does not exist (ECR only)
- Builds the Docker image using the project `Dockerfile`
- Pushes the image to the selected registry

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 2. VPC and Networking

Ensure you have:
- A VPC with at least **2 public or private subnets** in different Availability Zones
- A **Security Group** that allows:
  - Inbound TCP on port **8080** (from ALB or direct access)
  - Outbound TCP on all ports (for external service calls)

```bash
# List available VPCs
aws ec2 describe-vpcs --query "Vpcs[*].{ID:VpcId,CIDR:CidrBlock}" --output table

# List subnets
aws ec2 describe-subnets --query "Subnets[*].{ID:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock}" --output table

# Create security group (if needed)
aws ec2 create-security-group \
  --group-name resortsLite-sg \
  --description "ResortsLite ECS Security Group" \
  --vpc-id vpc-XXXXXXXXX

# Allow inbound on port 8080
aws ec2 authorize-security-group-ingress \
  --group-id sg-XXXXXXXXX \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles

#### ECS Task Execution Role (required)
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (optional – for task-level AWS API access)
```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'
```

### 4. CloudWatch Log Group

```bash
aws logs create-log-group --log-group-name /ecs/resortsLite --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures how ECS runs the container:

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `resortsLite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | ECR pull + CloudWatch logs |
| `containerPort` | `8080` | Application port |

### Valid Fargate CPU/Memory Combinations

| CPU | Memory Options |
|-----|---------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024**, 2048, 3072, 4096 MB |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Logging Configuration

All container logs are sent to CloudWatch Logs:
- **Log Group**: `/ecs/resortsLite`
- **Stream Prefix**: `ecs`
- **Driver**: `awslogs`

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) controls how ECS manages running tasks:

| Field | Value | Notes |
|-------|-------|-------|
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for high availability |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for public subnet access |
| `maximumPercent` | `200` | Allow double tasks during rolling deploy |
| `minimumHealthyPercent` | `50` | Keep at least 1 task running |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the Docker image

```bash
# Linux/macOS
./scripts/build-push.sh

# Windows
scripts\build-push.bat
```

Note the full image URI output (e.g., `123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest`).

### Step 2: Run the deployment script

```bash
# Linux/macOS
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh

# Windows
scripts\deploy-image.bat
```

The script will prompt for:
- AWS Region
- ECS Cluster name
- ECR Image URI
- VPC ID
- Subnet IDs (comma-separated)
- Security Group ID
- Whether to create an Application Load Balancer

### Step 3: Verify the deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1

# List running tasks
aws ecs list-tasks \
  --cluster resortsLite-cluster \
  --service-name resortsLite-service \
  --region us-east-1

# View logs
aws logs tail /ecs/resortsLite --follow --region us-east-1
```

### Step 4: Test the application

```bash
# If using ALB (replace with your ALB DNS name)
curl http://your-alb-dns.us-east-1.elb.amazonaws.com/actuator/health

# If using direct task IP (get from ECS console or CLI)
curl http://TASK_PUBLIC_IP:8080/actuator/health
```

---

## ECS-Specific Troubleshooting

### Task fails to start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks TASK_ARN \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopReason:stoppedReason,Containers:containers[*].{Name:name,Reason:reason,ExitCode:exitCode}}"
```

Common causes:
- **Image pull failure**: Verify ECR permissions on `ecsTaskExecutionRole`
- **OOM killed**: Increase `memory` in task definition (use valid Fargate combination)
- **Port conflict**: Ensure `containerPort` matches `server.port` in `application.properties`

### Network connectivity issues

```bash
# Verify security group allows inbound on port 8080
aws ec2 describe-security-groups \
  --group-ids sg-XXXXXXXXX \
  --query "SecurityGroups[0].IpPermissions"

# Check task ENI
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks TASK_ARN \
  --query "tasks[0].attachments"
```

### CloudWatch logs not appearing

```bash
# Verify log group exists
aws logs describe-log-groups --log-group-name-prefix /ecs/resortsLite

# Check execution role has CloudWatch permissions
aws iam get-role-policy --role-name ecsTaskExecutionRole --policy-name CloudWatchLogs
```

### JVM memory issues

If the container is being OOM-killed, increase the task memory or tune JVM flags:

```json
// In task-definition.json environment section:
{ "name": "JAVA_OPTS", "value": "-Xms256m -Xmx768m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" }
```

Then update the task definition and redeploy.

### Health check failures

The ALB health check uses `/actuator/health`. Ensure:
1. Spring Actuator is on the classpath (`spring-boot-starter-actuator`)
2. `management.endpoints.web.exposure.include=health` is set
3. The security group allows the ALB to reach port 8080

---

## ECS Fargate Scaling & Management

### Manual scaling

```bash
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortsLite-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }'
```

### Blue/Green Deployment with CodeDeploy

For zero-downtime deployments, configure CodeDeploy with ECS:

1. Create a CodeDeploy application and deployment group targeting the ECS service
2. Use `EXTERNAL` deployment controller in the service definition
3. Trigger deployments via `aws deploy create-deployment`

### Rolling Update (default)

The current configuration uses ECS rolling updates:
- `maximumPercent: 200` – allows 4 tasks during deployment (2 old + 2 new)
- `minimumHealthyPercent: 50` – keeps at least 1 task running

---

## Configuration Management

### Environment Variables

All application configuration is driven by environment variables. Update them in `ecs/task-definition.json` under the `environment` array:

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `REPORT_BASE_PATH` | `/reports` | Report file storage path |
| `BACKUP_PATH` | `/backups/nightly` | Backup storage path |
| `PAYMENT_API_URL` | `http://payment-service.internal:9090/payments/charge` | Payment service URL |
| `APP_PAYMENT_ENDPOINT` | `http://payment-svc.internal:9090/charge` | Payment endpoint |
| `APP_INVENTORY_ENDPOINT` | `http://inventory-svc.internal:8081/rooms` | Inventory service URL |
| `APP_NOTIFICATION_ENDPOINT` | `http://notify.internal:7070/send` | Notification service URL |
| `REDIS_HOST` | `localhost` | Redis host for session caching |
| `REDIS_PORT` | `6379` | Redis port |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM startup options |
| `TZ` | `UTC` | Container timezone |

### AWS Secrets Manager (recommended for sensitive values)

For sensitive configuration (database passwords, API keys), use AWS Secrets Manager:

```bash
# Store a secret
aws secretsmanager create-secret \
  --name /resortsLite/production/db-password \
  --secret-string "your-secure-password"
```

Reference in task definition:
```json
"secrets": [
  {
    "name": "DB_PASSWORD",
    "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:/resortsLite/production/db-password"
  }
]
```

---

## Security Considerations

1. **Non-root container user**: The Dockerfile creates and uses `appuser` (non-root) for runtime
2. **No sensitive data in images**: All credentials are injected via environment variables or Secrets Manager
3. **Minimal runtime image**: Uses `eclipse-temurin:8-jdk` – no unnecessary tools installed
4. **Security Group**: Restrict inbound access to only required ports (8080)
5. **IAM least privilege**: Task execution role has only ECR pull and CloudWatch write permissions
6. **VPC isolation**: Deploy in private subnets with NAT Gateway for production workloads
7. **HTTPS**: Configure ALB with an ACM certificate for TLS termination
8. **Log retention**: Set CloudWatch log retention policy to control costs and compliance

```bash
# Set log retention to 30 days
aws logs put-retention-policy \
  --log-group-name /ecs/resortsLite \
  --retention-in-days 30
```

---

## Java-Specific Notes

### JVM Container Awareness

The Dockerfile sets the following JVM flags for optimal container behaviour:

```
-XX:+UseContainerSupport       # Respect container CPU/memory limits
-XX:MaxRAMPercentage=75.0      # Use 75% of container memory for heap
-Xms256m                       # Initial heap size
-Xmx512m                       # Maximum heap size
-Djava.security.egd=file:/dev/./urandom  # Faster random number generation
```

### Spring Boot Actuator Endpoints

| Endpoint | URL | Purpose |
|----------|-----|---------|
| Health | `GET /actuator/health` | Liveness/readiness probe |
| Info | `GET /actuator/info` | Application metadata |

### Spring Profiles

The `docker` profile is activated by default in containers via `SPRING_PROFILES_ACTIVE=docker`. Create `application-docker.properties` or `application-docker.yml` to override settings for the containerised environment.

### Graceful Shutdown

The Dockerfile sets `STOPSIGNAL SIGTERM` and uses `exec java ...` in the entrypoint to ensure the JVM receives SIGTERM directly, enabling Spring Boot's graceful shutdown mechanism.

To enable graceful shutdown in Spring Boot 2.3+:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

### H2 In-Memory Database

The current configuration uses H2 in-memory database (`jdbc:h2:mem:resortdb`). For production:
- Replace with Amazon RDS (PostgreSQL/MySQL)
- Store connection details in AWS Secrets Manager
- Update `spring.datasource.*` environment variables accordingly
