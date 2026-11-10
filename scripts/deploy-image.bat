@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from repository root directory
:: Prerequisites: aws-cli, kubectl
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ==============================================
echo   ResortsLite -- AWS EKS Deployment
echo ==============================================
echo.

:: ---- AWS / EKS configuration ----
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
echo --- Application Environment Variables ---
echo Press Enter to keep the default value shown in brackets.
echo.

set /p INPUT_REDIS_HOST="Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!INPUT_REDIS_HOST!"=="" ( set "REDIS_HOST=localhost" ) else ( set "REDIS_HOST=!INPUT_REDIS_HOST!" )

set /p INPUT_REDIS_PORT="Enter REDIS_PORT [6379]: "
if "!INPUT_REDIS_PORT!"=="" ( set "REDIS_PORT=6379" ) else ( set "REDIS_PORT=!INPUT_REDIS_PORT!" )

set /p INPUT_SESSION_TIMEOUT="Enter SESSION_TIMEOUT_SECONDS [1800]: "
if "!INPUT_SESSION_TIMEOUT!"=="" ( set "SESSION_TIMEOUT_SECONDS=1800" ) else ( set "SESSION_TIMEOUT_SECONDS=!INPUT_SESSION_TIMEOUT!" )

set /p INPUT_PAYMENT_API="Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!INPUT_PAYMENT_API!"=="" ( set "PAYMENT_API_URL=http://payment-service:9090/payments/charge" ) else ( set "PAYMENT_API_URL=!INPUT_PAYMENT_API!" )

set /p INPUT_REPORT_PATH="Enter REPORT_BASE_PATH [/var/reports]: "
if "!INPUT_REPORT_PATH!"=="" ( set "REPORT_BASE_PATH=/var/reports" ) else ( set "REPORT_BASE_PATH=!INPUT_REPORT_PATH!" )

set /p INPUT_BACKUP_PATH="Enter BACKUP_PATH [/var/backups/nightly]: "
if "!INPUT_BACKUP_PATH!"=="" ( set "BACKUP_PATH=/var/backups/nightly" ) else ( set "BACKUP_PATH=!INPUT_BACKUP_PATH!" )

echo.
echo Summary:
echo   AWS Region       : !AWS_REGION!
echo   EKS Cluster      : !CLUSTER_NAME!
echo   Image URI        : !IMAGE_URI!
echo   Redis Host       : !REDIS_HOST!
echo   Redis Port       : !REDIS_PORT!
echo   Session Timeout  : !SESSION_TIMEOUT_SECONDS!
echo   Payment API URL  : !PAYMENT_API_URL!
echo   Report Base Path : !REPORT_BASE_PATH!
echo   Backup Path      : !BACKUP_PATH!
echo.

set /p CONFIRM="Proceed with deployment? [y/N]: "
if /i not "!CONFIRM!"=="y" (
    echo Deployment cancelled.
    exit /b 0
)

:: ---- Configure kubectl ----
echo.
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

:: ---- Patch Kubernetes manifests ----
echo.
echo Updating Kubernetes manifests with deployment values...

:: Copy manifests to temp directory
if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp
xcopy /s /e /i /q kubernetes kubernetes_deploy_tmp >nul

:: Use PowerShell to perform sed-like replacements
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' -replace '\{\{SESSION_TIMEOUT_SECONDS\}\}', '!SESSION_TIMEOUT_SECONDS!' -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH!' -replace '\{\{BACKUP_PATH\}\}', '!BACKUP_PATH!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

:: ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

:: ---- Clean up temp manifests ----
rmdir /s /q kubernetes_deploy_tmp

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed or timed out.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment complete!
echo   Health check: kubectl get pods -n !NAMESPACE!
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
