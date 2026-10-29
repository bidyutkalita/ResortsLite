@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push ResortsLite Docker image
:: Usage: scripts\build-push.bat
:: Run from repository root directory
:: ============================================================

set "PROJECT_NAME=resortslite"
set "IMAGE_NAME=resortslite"

echo ============================================
echo   ResortsLite - Docker Build and Push
echo ============================================
echo.

:: ── Registry selection ──────────────────────────────────────
echo Select container registry:
echo   1. AWS ECR
echo   2. Docker Hub
echo.
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

:: ── Image tag ───────────────────────────────────────────────
set /p RAW_TAG="Enter image tag (press Enter for 'latest'): "
if "!RAW_TAG!"=="" (
    set "IMAGE_TAG=latest"
) else (
    set "IMAGE_TAG=!RAW_TAG!"
)

echo.
echo Image name : !IMAGE_NAME!
echo Image tag  : !IMAGE_TAG!
echo.

:: ── Registry-specific logic ─────────────────────────────────
if "!REGISTRY_CHOICE!"=="1" (
    :: ── AWS ECR ──────────────────────────────────────────────
    set /p AWS_REGION="Enter AWS region (e.g. us-east-1): "
    set /p AWS_ACCOUNT_ID="Enter AWS account ID: "
    set /p ECR_REPO_INPUT="Enter ECR repository name (default: !IMAGE_NAME!): "
    if "!ECR_REPO_INPUT!"=="" (
        set "ECR_REPO=!IMAGE_NAME!"
    ) else (
        set "ECR_REPO=!ECR_REPO_INPUT!"
    )

    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo.
    echo Authenticating with AWS ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    echo Ensuring ECR repository exists...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )

) else if "!REGISTRY_CHOICE!"=="2" (
    :: ── Docker Hub ───────────────────────────────────────────
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    set /p DOCKER_NAMESPACE_INPUT="Enter Docker Hub namespace/org (default: !DOCKER_USERNAME!): "
    if "!DOCKER_NAMESPACE_INPUT!"=="" (
        set "DOCKER_NAMESPACE=!DOCKER_USERNAME!"
    ) else (
        set "DOCKER_NAMESPACE=!DOCKER_NAMESPACE_INPUT!"
    )

    set "FULL_IMAGE_NAME=!DOCKER_NAMESPACE!/!IMAGE_NAME!:!IMAGE_TAG!"

    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid choice. Please enter 1 or 2.
    exit /b 1
)

echo.
echo Full image : !FULL_IMAGE_NAME!
echo.

:: ── Build ────────────────────────────────────────────────────
echo Building Docker image...
docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)
echo Build complete.

:: ── Push ─────────────────────────────────────────────────────
echo.
echo Pushing image to registry...
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ============================================

endlocal
