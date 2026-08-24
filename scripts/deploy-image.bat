@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat  -  Deploy ResortsLite to AWS ECS Fargate
:: =============================================================

set "SERVICE_NAME=resortsLite-service"
set "TASK_FAMILY=resortsLite-task"
set "LOG_GROUP=/ecs/resortsLite"
set "TASK_DEF_FILE=ecs\task-definition.json"
set "SVC_DEF_FILE=ecs\service-definition.json"

echo =============================================
echo   ResortsLite - ECS Fargate Deployment
echo =============================================
echo.

:: ── Collect configuration ───────────────────────────────────
set /p AWS_REGION="AWS Region [e.g. us-east-1]: "
set /p CLUSTER_INPUT="ECS Cluster name [default: resortsLite-cluster]: "
if "!CLUSTER_INPUT!"=="" (
    set "CLUSTER_NAME=resortsLite-cluster"
) else (
    set "CLUSTER_NAME=!CLUSTER_INPUT!"
)
set /p IMAGE_URI="ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): "
set /p VPC_ID="VPC ID: "
set /p SUBNETS_RAW="Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): "
set /p SECURITY_GROUP="Security Group ID: "

:: Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_RAW!") do (
    set "SUBNET_1=%%a"
    set "SUBNET_2=%%b"
)
:: Trim spaces
for /f "tokens=*" %%a in ("!SUBNET_1!") do set "SUBNET_1=%%a"
for /f "tokens=*" %%a in ("!SUBNET_2!") do set "SUBNET_2=%%a"
if "!SUBNET_2!"=="" set "SUBNET_2=!SUBNET_1!"

:: ── AWS Account ID ──────────────────────────────────────────
echo.
echo Retrieving AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%i"
echo Account ID: !ACCOUNT_ID!

:: ── Ensure CloudWatch log group exists ──────────────────────
echo Ensuring CloudWatch log group exists: !LOG_GROUP!
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1

:: ── Ensure ECS cluster exists ───────────────────────────────
echo Checking ECS cluster: !CLUSTER_NAME!
for /f "delims=" %%i in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%i"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)

:: ── Load balancer prompt ─────────────────────────────────────
echo.
set /p NEED_LB="Do you need an Application Load Balancer for this service? (y/n): "
set "USE_LB=false"

if /i "!NEED_LB!"=="y" (
    echo Creating Application Load Balancer...
    set "ALB_NAME=resortsLite-alb"
    set "TG_NAME=resortsLite-tg"

    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name "!ALB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "ALB_ARN=%%i"
    echo ALB ARN: !ALB_ARN!

    for /f "delims=" %%i in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%i"
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul

    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "ALB_DNS=%%i"

    set "USE_LB=true"
)

:: ── Prepare task definition ──────────────────────────────────
echo.
echo Preparing task definition...
copy /y "!TASK_DEF_FILE!" "%TEMP%\task-definition-deploy.json" >nul

powershell -Command "(Get-Content '%TEMP%\task-definition-deploy.json') -replace '{{IMAGE_URI}}','!IMAGE_URI!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' | Set-Content '%TEMP%\task-definition-deploy.json'"

:: ── Register task definition ─────────────────────────────────
echo Registering task definition...
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://%TEMP%\task-definition-deploy.json --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%i"
echo Task Definition ARN: !TASK_DEF_ARN!
if "!TASK_DEF_ARN!"=="" (
    echo ERROR: Failed to register task definition.
    exit /b 1
)

:: ── Prepare service definition ───────────────────────────────
echo Preparing service definition...
copy /y "!SVC_DEF_FILE!" "%TEMP%\service-definition-deploy.json" >nul

powershell -Command "(Get-Content '%TEMP%\service-definition-deploy.json') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '%TEMP%\service-definition-deploy.json'"

if "!USE_LB!"=="true" (
    powershell -Command "$svc = Get-Content '%TEMP%\service-definition-deploy.json' | ConvertFrom-Json; $lb = @{targetGroupArn='!TARGET_GROUP_ARN!'; containerName='resortsLite'; containerPort=8080}; $svc | Add-Member -NotePropertyName 'loadBalancers' -NotePropertyValue @($lb) -Force; $svc | Add-Member -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300 -Force; $svc | ConvertTo-Json -Depth 10 | Set-Content '%TEMP%\service-definition-deploy.json'"
)

:: ── Create or update service ─────────────────────────────────
echo Checking if ECS service exists...
for /f "delims=" %%i in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status!='INACTIVE'].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%i"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service: !SERVICE_NAME!
    aws ecs create-service --cli-input-json file://%TEMP%\service-definition-deploy.json --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service: !SERVICE_NAME!
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
)

:: ── Wait for stability ───────────────────────────────────────
echo.
echo Waiting for service to stabilise (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"

:: ── Verify deployment ────────────────────────────────────────
echo.
echo Verifying deployment...
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,Pending:pendingCount}"

echo.
echo =============================================
echo   Deployment complete!
echo   Service : !SERVICE_NAME!
echo   Cluster : !CLUSTER_NAME!
echo   Region  : !AWS_REGION!
echo   Logs    : !LOG_GROUP!
if "!USE_LB!"=="true" echo   App URL : http://!ALB_DNS!
echo =============================================
echo.
echo Troubleshooting tips:
echo   View logs : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   List tasks: aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!

endlocal
