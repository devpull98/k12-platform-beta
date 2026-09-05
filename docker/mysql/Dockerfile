FROM mysql:8.4

COPY conf.d/mysql.cnf /etc/mysql/conf.d/mysql.cnf

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
  CMD mysqladmin ping -h localhost -u root -p"${MYSQL_ROOT_PASSWORD}" --silent
