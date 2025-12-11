#!/bin/bash

# [변수 설정]
APP_DIR="/home/ubuntu/ktb-chat-backend"
TEMP_DIR="$APP_DIR/temp"
SERVICE_NAME="ktb-chat"
ENV_FILE="$APP_DIR/.env"

echo ">>> [Deploy] 배포 프로세스 시작..."

# ----------------------------------------------------
# 1. 환경 변수 파일 검증 (YAML에 있던 로직 이동)
# ----------------------------------------------------
echo ">>> [Check] 환경 변수 점검 중..."

if [ ! -f "$ENV_FILE" ]; then
    echo ">>> [Error] .env 파일이 없습니다! ($ENV_FILE)"
    exit 1
fi

# 필수 변수 체크 함수
check_env_var() {
    local var_name=$1
    if ! grep -q "^${var_name}=" "$ENV_FILE"; then
        echo ">>> [Error] $var_name 환경 변수가 설정되지 않았습니다."
        exit 1
    else
        echo ">>> [OK] $var_name 설정 확인됨."
    fi
}

check_env_var "MONGO_URI"
check_env_var "REDIS_HOST"
# REDIS_PORT는 없으면 기본값 쓰므로 에러 처리 안 함 (Warning만)
if ! grep -q "^REDIS_PORT=" "$ENV_FILE"; then
    echo ">>> [Warning] REDIS_PORT가 없습니다. 기본값(6379)을 사용합니다."
fi

# .env에서 PORT 읽기 (없으면 8080)
# grep 결과가 없으면 || 뒤에 것이 실행됨
APP_PORT=$(grep "^PORT=" "$ENV_FILE" | cut -d'=' -f2 | tr -d '"' | tr -d "'" || echo "8080")

# ----------------------------------------------------
# 2. JAR 파일 찾기 및 이동
# ----------------------------------------------------
JAR_FILE=$(find "$TEMP_DIR" -name "*.jar" | head -n 1)

if [ -z "$JAR_FILE" ]; then
    echo ">>> [Error] temp 폴더에 JAR 파일이 없습니다!"
    ls -R "$TEMP_DIR"
    exit 1
fi

echo ">>> 발견된 JAR 파일: $JAR_FILE"

# 기존 파일 백업
if [ -f "$APP_DIR/app.jar" ]; then
    cp "$APP_DIR/app.jar" "$APP_DIR/app.jar.bak"
    echo ">>> 백업 완료: app.jar.bak"
fi

# 새 파일 이동
mv "$JAR_FILE" "$APP_DIR/app.jar"
echo ">>> 새 JAR 파일 적용 완료"

# temp 폴더 청소
rm -rf "$TEMP_DIR"/*

# ----------------------------------------------------
# 3. 서비스 재시작 (Systemd Only)
# ----------------------------------------------------
echo ">>> 서비스 재시작 ($SERVICE_NAME)..."

sudo systemctl restart $SERVICE_NAME

# 서비스가 떴는지 프로세스 레벨에서 1차 확인
if ! sudo systemctl is-active --quiet $SERVICE_NAME; then
    echo ">>> [Error] 서비스 시작 실패 (Systemd)"
    sudo journalctl -u $SERVICE_NAME -n 30 --no-pager
    exit 1
fi

# ----------------------------------------------------
# 4. 헬스체크
# ----------------------------------------------------
HEALTH_URL="http://localhost:${APP_PORT}/api/health"
echo ">>> 헬스체크 시작: $HEALTH_URL (최대 30초)"

for i in {1..30}; do
    sleep 3
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$HEALTH_URL" || echo "000")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        echo ">>> [Success] 배포 성공! (HTTP 200)"
        exit 0
    fi
    echo ">>> 대기 중... ($i/30) - Res: $HTTP_CODE"
done

echo ">>> [Fail] 배포 실패. 헬스체크 응답 없음."
sudo journalctl -u $SERVICE_NAME -n 50 --no-pager
exit 1