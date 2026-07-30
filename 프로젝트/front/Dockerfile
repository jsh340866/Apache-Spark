### 1단계: React 앱 빌드 (지금은 순수 정적 HTML/CSS/JS라 미사용 — React 전환 시 주석 해제) ###
# FROM node:20-alpine AS build
#
# WORKDIR /app
#
# COPY package*.json ./
# RUN npm install
#
# COPY . .
# RUN npm run build


### 2단계: Nginx 이미지로 정적 파일 배포 ###
FROM nginx:alpine

# 지금은 빌드 과정 없이 정적 파일을 그대로 복사 (React 전환 시 아래 줄로 교체)
COPY . /usr/share/nginx/html
# COPY --from=build /app/build /usr/share/nginx/html

# (선택) 커스텀 Nginx 설정 파일이 있을 경우 복사
COPY nginx.conf /etc/nginx/nginx.conf

# 웹 루트로 함께 복사된 nginx 설정/Dockerfile은 정적 파일로 노출되지 않도록 제거
RUN rm -f /usr/share/nginx/html/nginx.conf \
          /usr/share/nginx/html/nginx-local.conf \
          /usr/share/nginx/html/Dockerfile \
          /usr/share/nginx/html/Dockerfile.local

# 포트 노출
EXPOSE 80 443

# Nginx 실행
CMD ["nginx", "-g", "daemon off;"]


