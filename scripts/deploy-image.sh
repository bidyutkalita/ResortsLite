#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from repository root directory
# Prerequisites: aws-cli, kubectl
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment"
echo "=============================================="
echo ""

# ---- AWS / EKS configuration ----
read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS region is required."
  exit 1
fi

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to keep the default value shown in brackets."
echo ""

read -rp "Enter REDIS_HOST (ElastiCache endpoint) [localhost]: " INPUT_REDIS_HOST
REDIS_HOST="${INPUT_REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " INPUT_REDIS_PORT
REDIS_PORT="${INPUT_REDIS_PORT:-6379}"

read -rp "Enter SESSION_TIMEOUT_SECONDS [1800]: " INPUT_SESSION_TIMEOUT
SESSION_TIMEOUT_SECONDS="${INPUT_SESSION_TIMEOUT:-1800}"

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " INPUT_PAYMENT_API
PAYMENT_API_URL="${INPUT_PAYMENT_API:-http://payment-service:9090/payments/charge}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " INPUT_REPORT_PATH
REPORT_BASE_PATH="${INPUT_REPORT_PATH:-/var/reports}"

read -rp "Enter BACKUP_PATH [/var/backups/nightly]: " INPUT_BACKUP_PATH
BACKUP_PATH="${INPUT_BACKUP_PATH:-/var/backups/nightly}"

echo ""
echo "Summary:"
echo "  AWS Region       : $AWS_REGION"
echo "  EKS Cluster      : $CLUSTER_NAME"
echo "  Image URI        : $IMAGE_URI"
echo "  Redis Host       : $REDIS_HOST"
echo "  Redis Port       : $REDIS_PORT"
echo "  Session Timeout  : $SESSION_TIMEOUT_SECONDS"
echo "  Payment API URL  : $PAYMENT_API_URL"
echo "  Report Base Path : $REPORT_BASE_PATH"
echo "  Backup Path      : $BACKUP_PATH"
echo ""

read -rp "Proceed with deployment? [y/N]: " CONFIRM
if [[ ! "$CONFIRM" =~ ^[Yy]$ ]]; then
  echo "Deployment cancelled."
  exit 0
fi

# ---- Configure kubectl ----
echo ""
echo "Configuring kubectl for EKS cluster..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---- Patch Kubernetes manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals permanently
cp -r kubernetes kubernetes_deploy_tmp

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                         kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                       kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                       kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SESSION_TIMEOUT_SECONDS}}|${SESSION_TIMEOUT_SECONDS}|g" kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"             kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"           kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH}|g"                     kubernetes_deploy_tmp/deployment.yaml

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f kubernetes_deploy_tmp/namespace.yaml

echo "  [2/4] Applying deployment..."
kubectl apply -f kubernetes_deploy_tmp/deployment.yaml

echo "  [3/4] Applying service..."
kubectl apply -f kubernetes_deploy_tmp/service.yaml

echo "  [4/4] Applying ingress..."
kubectl apply -f kubernetes_deploy_tmp/ingress.yaml

# ---- Clean up temp manifests ----
rm -rf kubernetes_deploy_tmp

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ---- Display access URL ----
echo ""
echo "Fetching application ingress URL..."
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

echo ""
echo "=============================================="
echo "  Deployment complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health check   : http://${INGRESS_HOST}/actuator/health"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
