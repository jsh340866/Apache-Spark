# MySQL 8.0 공식 이미지를 기반으로 설정
FROM mysql:8.0

# 환경 변수는 이미지에 굽지 않고 docker-compose의 env_file(./DB/.env)로 런타임에 주입

# 3306 포트 노출
EXPOSE 3306 

# MySQL 서버 실행
CMD ["mysqld"]
