#!/usr/bin/env bash
# =============================================================
# build-push.sh  –  Build & push ResortsLite Docker image
# Supports: AWS ECR  |  Docker Hub
# =============================================================
set -e
set -o pipefail

PROJECT_NAME="resortsLite"
DOCKERFILE_PATH="Dockerfile"
BUILD_CONTEXT="."

# ── Sanitise image name (lowercase, hyphens only) ────────────
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "============================================="
echo "  ResortsLite – Docker Build & Push"
echo "============================================="
echo ""

# ── Registry selection ───────────────────────────────────────
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1-2]: " REGISTRY_CHOICE

# ── Image tag ────────────────────────────────────────────────
read -rp "Enter image tag [default: latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${IMAGE_TAG:-latest}"
echo "Using tag: $IMAGE_TAG"

# ── Registry-specific setup ──────────────────────────────────
if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ── AWS ECR ──────────────────────────────────────────────
  echo ""
  echo "--- AWS ECR Configuration ---"
  read -rp "AWS Region [e.g. us-east-1]: " AWS_REGION
  read -rp "AWS Account ID: " AWS_ACCOUNT_ID
  read -rp "ECR Repository name [default: ${IMAGE_NAME}]: " ECR_REPO_INPUT
  ECR_REPO="${ECR_REPO_INPUT:-$IMAGE_NAME}"

  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with ECR..."
  aws ecr get-login-password --region "$AWS_REGION" \
    | docker login --username AWS --password-stdin "$REGISTRY_URL"

  echo "Ensuring ECR repository exists..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" \
    >/dev/null 2>&1 \
    || aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ── Docker Hub ───────────────────────────────────────────
  echo ""
  echo "--- Docker Hub Configuration ---"
  read -rp "Docker Hub username: " DOCKER_USERNAME
  read -rsp "Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Docker Hub namespace/org [default: ${DOCKER_USERNAME}]: " DOCKER_NAMESPACE_INPUT
  DOCKER_NAMESPACE="${DOCKER_NAMESPACE_INPUT:-$DOCKER_USERNAME}"

  FULL_IMAGE_NAME="${DOCKER_NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid registry choice. Exiting."
  exit 1
fi

# ── Build ────────────────────────────────────────────────────
echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
docker build \
  -f "$DOCKERFILE_PATH" \
  -t "$FULL_IMAGE_NAME" \
  "$BUILD_CONTEXT"

echo ""
echo "Build successful."

# ── Push ─────────────────────────────────────────────────────
echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"

echo ""
echo "============================================="
echo "  Image pushed successfully!"
echo "  $FULL_IMAGE_NAME"
echo "============================================="
