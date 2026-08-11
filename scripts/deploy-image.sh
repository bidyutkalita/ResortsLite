#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "============================================"
echo "  ResortsLite — Deploy to AWS EKS"
echo "============================================"
echo ""

# ---- Collect deployment inputs ----
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS Region is required."
  exit 1
fi

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "---- Application Environment Variables ----"
echo "Press Enter to skip any optional variable."
echo ""

read -rp "Enter REDIS_HOST (ElastiCache endpoint) [localhost]: " REDIS_HOST_VAL
REDIS_HOST_VAL="${REDIS_HOST_VAL:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6379}"

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL_VAL
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-service:9090/payments/charge}"

read -rp "Enter REPORT_BASE_PATH [/reports]: " REPORT_BASE_PATH_VAL
REPORT_BASE_PATH_VAL="${REPORT_BASE_PATH_VAL:-/reports}"

read -rp "Enter BACKUP_PATH [/backups]: " BACKUP_PATH_VAL
BACKUP_PATH_VAL="${BACKUP_PATH_VAL:-/backups}"

# ---- Configure kubectl for EKS ----
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---- Prepare manifest copies ----
DEPLOY_DIR="$(mktemp -d)"
cp -r "$PROJECT_ROOT/kubernetes/"* "$DEPLOY_DIR/"

# ---- Substitute placeholders ----
echo ""
echo "Substituting manifest placeholders..."

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                       "$DEPLOY_DIR/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                 "$DEPLOY_DIR/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                 "$DEPLOY_DIR/deployment.yaml"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"       "$DEPLOY_DIR/deployment.yaml"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH_VAL}|g"     "$DEPLOY_DIR/deployment.yaml"
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH_VAL}|g"               "$DEPLOY_DIR/deployment.yaml"

# ---- Apply Kubernetes manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "$DEPLOY_DIR/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "$DEPLOY_DIR/deployment.yaml"

echo "  [3/4] Applying service..."
kubectl apply -f "$DEPLOY_DIR/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "$DEPLOY_DIR/ingress.yaml"

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
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")
echo "============================================"
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health Check:    http://${INGRESS_HOST}/actuator/health"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"

# Cleanup temp dir
rm -rf "$DEPLOY_DIR"
