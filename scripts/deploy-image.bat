@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS
:: Usage: scripts\deploy-image.bat
:: Run from repository root directory
:: ============================================================

echo ============================================
echo   ResortsLite - AWS EKS Deployment
echo ============================================
echo.

:: ── Collect deployment parameters ───────────────────────────
set /p AWS_REGION="Enter AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS region is required.
    exit /b 1
)

set /p CLUSTER_NAME="Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo -- Optional: Application Environment Variables --
echo    Press Enter to skip any variable.
echo.

set /p SPRING_REDIS_HOST_VAL="Enter SPRING_REDIS_HOST (ElastiCache endpoint): "
set /p SPRING_REDIS_PORT_VAL="Enter SPRING_REDIS_PORT (default: 6379): "
set /p SPRING_REDIS_PASSWORD_VAL="Enter SPRING_REDIS_PASSWORD (leave blank if auth disabled): "
set /p PAYMENT_API_URL_VAL="Enter PAYMENT_API_URL: "
set /p APP_PAYMENT_ENDPOINT_VAL="Enter APP_PAYMENT_ENDPOINT: "
set /p APP_INVENTORY_ENDPOINT_VAL="Enter APP_INVENTORY_ENDPOINT: "
set /p APP_NOTIFICATION_ENDPOINT_VAL="Enter APP_NOTIFICATION_ENDPOINT: "

:: Apply defaults
if "!SPRING_REDIS_HOST_VAL!"=="" set "SPRING_REDIS_HOST_VAL=localhost"
if "!SPRING_REDIS_PORT_VAL!"=="" set "SPRING_REDIS_PORT_VAL=6379"
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-svc:9090/payments/charge"
if "!APP_PAYMENT_ENDPOINT_VAL!"=="" set "APP_PAYMENT_ENDPOINT_VAL=http://payment-svc.internal:9090/charge"
if "!APP_INVENTORY_ENDPOINT_VAL!"=="" set "APP_INVENTORY_ENDPOINT_VAL=http://inventory-svc.internal:8081/rooms"
if "!APP_NOTIFICATION_ENDPOINT_VAL!"=="" set "APP_NOTIFICATION_ENDPOINT_VAL=http://notify.internal:7070/send"

echo.
echo -- Deployment Summary --
echo   AWS Region   : !AWS_REGION!
echo   EKS Cluster  : !CLUSTER_NAME!
echo   Image URI    : !IMAGE_URI!
echo.

:: ── Configure kubectl ────────────────────────────────────────
echo Configuring kubectl for EKS cluster...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ── Create temp manifest directory ──────────────────────────
echo.
echo Updating Kubernetes manifests with deployment values...
if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp
xcopy /e /i /q kubernetes kubernetes_deploy_tmp >nul

:: ── Substitute placeholders using PowerShell ─────────────────
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{SPRING_REDIS_HOST\}\}', '!SPRING_REDIS_HOST_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{SPRING_REDIS_PORT\}\}', '!SPRING_REDIS_PORT_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{SPRING_REDIS_PASSWORD\}\}', '!SPRING_REDIS_PASSWORD_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{APP_PAYMENT_ENDPOINT\}\}', '!APP_PAYMENT_ENDPOINT_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{APP_INVENTORY_ENDPOINT\}\}', '!APP_INVENTORY_ENDPOINT_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}', '!APP_NOTIFICATION_ENDPOINT_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"

:: ── Apply manifests ──────────────────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply namespace.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

echo   [2/4] Applying deployment...
kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply deployment.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

echo   [3/4] Applying service...
kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply service.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

echo   [4/4] Applying ingress...
kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply ingress.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

:: ── Clean up temp manifests ──────────────────────────────────
rmdir /s /q kubernetes_deploy_tmp

:: ── Wait for rollout ─────────────────────────────────────────
echo.
echo Waiting for deployment rollout to complete...
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed or timed out.
    echo Rollback command: kubectl rollout undo deployment/resortslite -n resortslite
    exit /b 1
)

:: ── Verify resources ─────────────────────────────────────────
echo.
echo -- Deployed Resources --
kubectl get pods,svc,ingress -n resortslite

echo.
echo ============================================
echo   Deployment complete!
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/resortslite -n resortslite

endlocal
