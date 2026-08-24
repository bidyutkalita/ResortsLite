@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: build-push.bat  -  Build & push ResortsLite Docker image
:: Supports: AWS ECR  |  Docker Hub
:: =============================================================

set "PROJECT_NAME=resortsLite"
set "DOCKERFILE_PATH=Dockerfile"
set "BUILD_CONTEXT=."

echo =============================================
echo   ResortsLite - Docker Build ^& Push
echo =============================================
echo.

:: ── Registry selection ──────────────────────────────────────
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1-2]: "

:: ── Image tag ───────────────────────────────────────────────
set /p IMAGE_TAG_INPUT="Enter image tag [default: latest]: "
if "!IMAGE_TAG_INPUT!"=="" (
    set "IMAGE_TAG=latest"
) else (
    set "IMAGE_TAG=!IMAGE_TAG_INPUT!"
)

:: Sanitise image name to lowercase using PowerShell
for /f "delims=" %%i in ('powershell -Command "\"!PROJECT_NAME!\".ToLower() -replace '[^a-z0-9]','-' -replace '^-+','' -replace '-+$',''"') do set "IMAGE_NAME=%%i"
echo Using image name: !IMAGE_NAME!
echo Using tag: !IMAGE_TAG!

:: ── Registry-specific setup ─────────────────────────────────
if "!REGISTRY_CHOICE!"=="1" (
    :: ── AWS ECR ──────────────────────────────────────────────
    echo.
    echo --- AWS ECR Configuration ---
    set /p AWS_REGION="AWS Region [e.g. us-east-1]: "
    set /p AWS_ACCOUNT_ID="AWS Account ID: "
    set /p ECR_REPO_INPUT="ECR Repository name [default: !IMAGE_NAME!]: "
    if "!ECR_REPO_INPUT!"=="" (
        set "ECR_REPO=!IMAGE_NAME!"
    ) else (
        set "ECR_REPO=!ECR_REPO_INPUT!"
    )

    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo.
    echo Authenticating with ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    echo Ensuring ECR repository exists...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository: !ECR_REPO!
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )

) else if "!REGISTRY_CHOICE!"=="2" (
    :: ── Docker Hub ───────────────────────────────────────────
    echo.
    echo --- Docker Hub Configuration ---
    set /p DOCKER_USERNAME="Docker Hub username: "
    set /p DOCKER_PASSWORD="Docker Hub password/token: "
    set /p DOCKER_NAMESPACE_INPUT="Docker Hub namespace/org [default: !DOCKER_USERNAME!]: "
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
    echo ERROR: Invalid registry choice. Exiting.
    exit /b 1
)

:: ── Build ───────────────────────────────────────────────────
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" "!BUILD_CONTEXT!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Build successful.

:: ── Push ────────────────────────────────────────────────────
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo =============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo =============================================

endlocal
