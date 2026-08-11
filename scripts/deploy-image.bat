@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ============================================
echo   ResortsLite — Deploy to AWS EKS
echo ============================================
echo.

:: ---- Collect deployment inputs ----
set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required.
    exit /b 1
)

set /p CLUSTER_NAME="Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to skip any optional variable.
echo.

set /p REDIS_HOST_VAL="Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!REDIS_HOST_VAL!"=="" set "REDIS_HOST_VAL=localhost"

set /p REDIS_PORT_VAL="Enter REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6379"

set /p PAYMENT_API_URL_VAL="Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-service:9090/payments/charge"

set /p REPORT_BASE_PATH_VAL="Enter REPORT_BASE_PATH [/reports]: "
if "!REPORT_BASE_PATH_VAL!"=="" set "REPORT_BASE_PATH_VAL=/reports"

set /p BACKUP_PATH_VAL="Enter BACKUP_PATH [/backups]: "
if "!BACKUP_PATH_VAL!"=="" set "BACKUP_PATH_VAL=/backups"

:: ---- Configure kubectl for EKS ----
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! ...
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

:: ---- Create temp directory for manifests ----
set "DEPLOY_DIR=%TEMP%\resortslite-deploy-%RANDOM%"
mkdir "!DEPLOY_DIR!"
xcopy /E /I /Q kubernetes "!DEPLOY_DIR!" >nul

:: ---- Substitute placeholders using PowerShell ----
echo.
echo Substituting manifest placeholders...

powershell -Command "(Get-Content '!DEPLOY_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}','!IMAGE_URI!' | Set-Content '!DEPLOY_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!DEPLOY_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}','!REDIS_HOST_VAL!' | Set-Content '!DEPLOY_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!DEPLOY_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}','!REDIS_PORT_VAL!' | Set-Content '!DEPLOY_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!DEPLOY_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}','!PAYMENT_API_URL_VAL!' | Set-Content '!DEPLOY_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!DEPLOY_DIR!\deployment.yaml') -replace '{{REPORT_BASE_PATH}}','!REPORT_BASE_PATH_VAL!' | Set-Content '!DEPLOY_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!DEPLOY_DIR!\deployment.yaml') -replace '{{BACKUP_PATH}}','!BACKUP_PATH_VAL!' | Set-Content '!DEPLOY_DIR!\deployment.yaml'"

:: ---- Apply Kubernetes manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!DEPLOY_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!DEPLOY_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!DEPLOY_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!DEPLOY_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment Complete!
echo   Health Check: http://^<INGRESS_HOST^>/actuator/health
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup
rmdir /s /q "!DEPLOY_DIR!" >nul 2>&1

endlocal
