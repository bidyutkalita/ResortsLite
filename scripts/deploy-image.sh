#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from repository root directory
# ============================================================

echo "============================================"
echo "  ResortsLite — AWS EKS Deployment"
echo "============================================"
echo ""

# ── Collect deployment parameters ───────────────────────────
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
echo "── Optional: Application Environment Variables ──"
echo "   Press Enter to skip any variable."
echo ""

read -rp "Enter SPRING_REDIS_HOST (ElastiCache endpoint): " SPRING_REDIS_HOST_VAL
read -rp "Enter SPRING_REDIS_PORT (default: 6379): " SPRING_REDIS_PORT_VAL
read -rsp "Enter SPRING_REDIS_PASSWORD (leave blank if auth disabled): " SPRING_REDIS_PASSWORD_VAL
echo ""
read -rp "Enter PAYMENT_API_URL (e.g. http://payment-svc:9090/payments/charge): " PAYMENT_API_URL_VAL
read -rp "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT_VAL
read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT_VAL
read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT_VAL

# Apply defaults
SPRING_REDIS_HOST_VAL="${SPRING_REDIS_HOST_VAL:-localhost}"
SPRING_REDIS_PORT_VAL="${SPRING_REDIS_PORT_VAL:-6379}"
SPRING_REDIS_PASSWORD_VAL="${SPRING_REDIS_PASSWORD_VAL:-}"
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-svc:9090/payments/charge}"
APP_PAYMENT_ENDPOINT_VAL="${APP_PAYMENT_ENDPOINT_VAL:-http://payment-svc.internal:9090/charge}"
APP_INVENTORY_ENDPOINT_VAL="${APP_INVENTORY_ENDPOINT_VAL:-http://inventory-svc.internal:8081/rooms}"
APP_NOTIFICATION_ENDPOINT_VAL="${APP_NOTIFICATION_ENDPOINT_VAL:-http://notify.internal:7070/send}"

echo ""
echo "── Deployment Summary ──────────────────────────"
echo "  AWS Region   : $AWS_REGION"
echo "  EKS Cluster  : $CLUSTER_NAME"
echo "  Image URI    : $IMAGE_URI"
echo "────────────────────────────────────────────────"
echo ""

# ── Configure kubectl ────────────────────────────────────────
echo "Configuring kubectl for EKS cluster..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ── Substitute placeholders in manifests ────────────────────
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp -r kubernetes kubernetes_deploy_tmp

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SPRING_REDIS_HOST}}|${SPRING_REDIS_HOST_VAL}|g"               kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SPRING_REDIS_PORT}}|${SPRING_REDIS_PORT_VAL}|g"               kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SPRING_REDIS_PASSWORD}}|${SPRING_REDIS_PASSWORD_VAL}|g"       kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT_VAL}|g"         kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT_VAL}|g"     kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT_VAL}|g" kubernetes_deploy_tmp/deployment.yaml

# ── Apply manifests ──────────────────────────────────────────
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

# ── Clean up temp manifests ──────────────────────────────────
rm -rf kubernetes_deploy_tmp

# ── Wait for rollout ─────────────────────────────────────────
echo ""
echo "Waiting for deployment rollout to complete..."
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s

# ── Verify resources ─────────────────────────────────────────
echo ""
echo "── Deployed Resources ──────────────────────────"
kubectl get pods,svc,ingress -n resortslite

# ── Display access URL ───────────────────────────────────────
echo ""
echo "── Application Access ──────────────────────────"
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")
if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  Application URL : http://$INGRESS_HOST"
  echo "  Health Check    : http://$INGRESS_HOST/actuator/health"
else
  echo "  Ingress hostname is still provisioning. Run:"
  echo "  kubectl get ingress resortslite-ingress -n resortslite"
fi

echo ""
echo "============================================"
echo "  Deployment complete!"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/resortslite -n resortslite"
