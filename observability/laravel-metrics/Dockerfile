# Scaffold a throwaway Laravel skeleton at build time (no app code of our own
# needed beyond routes/web.php + MetricsController below) and wire in the
# Prometheus client — this image exists only to prove the observability
# pipeline works for a PHP/Laravel service, not to host real business logic.
FROM composer:2 AS composer-stage
WORKDIR /app
RUN composer create-project laravel/laravel . --prefer-dist --no-interaction --no-progress \
    && composer require promphp/prometheus_client_php --no-interaction

FROM php:8.4-cli-alpine
RUN docker-php-ext-install pdo_mysql
RUN apk add --no-cache --virtual .build-deps $PHPIZE_DEPS \
    && pecl install apcu \
    && docker-php-ext-enable apcu \
    && apk del .build-deps
RUN { \
      echo 'apc.enabled=1'; \
      echo 'apc.enable_cli=1'; \
    } > /usr/local/etc/php/conf.d/apcu.ini

WORKDIR /app
COPY --from=composer-stage /app /app
COPY routes/web.php /app/routes/web.php
COPY app/Http/Controllers/MetricsController.php /app/app/Http/Controllers/MetricsController.php
RUN cp .env.example .env && php artisan key:generate --force

EXPOSE 8000
CMD ["php", "artisan", "serve", "--host=0.0.0.0", "--port=8000"]
