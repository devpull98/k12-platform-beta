<?php

namespace App\Http\Controllers;

use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;
use Prometheus\CollectorRegistry;
use Prometheus\RenderTextFormat;
use Prometheus\Storage\APC;

class MetricsController extends Controller
{
    private function registry(): CollectorRegistry
    {
        return new CollectorRegistry(new APC());
    }

    public function metrics(): Response
    {
        $registry = $this->registry();
        $renderer = new RenderTextFormat();

        return response($renderer->render($registry->getMetricFamilySamples()))
            ->header('Content-Type', RenderTextFormat::MIME_TYPE);
    }

    public function work(Request $request): JsonResponse
    {
        $registry = $this->registry();

        $counter = $registry->getOrRegisterCounter(
            'laravel_metrics_demo',
            'http_requests_total',
            'Total demo requests',
            ['route', 'status']
        );
        $histogram = $registry->getOrRegisterHistogram(
            'laravel_metrics_demo',
            'http_request_duration_seconds',
            'Demo request duration in seconds',
            ['route'],
            [0.01, 0.05, 0.1, 0.25, 0.5, 1, 2]
        );

        $start = microtime(true);
        usleep(random_int(10_000, 300_000));
        $status = random_int(1, 10) === 1 ? 500 : 200;
        $duration = microtime(true) - $start;

        $histogram->observe($duration, ['/work']);
        $counter->inc(['/work', (string) $status]);

        return response()->json(
            ['status' => $status, 'durationMs' => round($duration * 1000, 2)],
            $status
        );
    }

    public function db(): JsonResponse
    {
        $registry = $this->registry();

        $counter = $registry->getOrRegisterCounter(
            'laravel_metrics_demo',
            'db_queries_total',
            'Total demo DB queries',
            ['status']
        );
        $histogram = $registry->getOrRegisterHistogram(
            'laravel_metrics_demo',
            'db_query_duration_seconds',
            'Demo DB query duration in seconds',
            ['status'],
            [0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1]
        );

        $start = microtime(true);
        try {
            $row = DB::selectOne('SELECT 1 + 1 AS result');
            $duration = microtime(true) - $start;

            $histogram->observe($duration, ['200']);
            $counter->inc(['200']);

            return response()->json(['status' => 200, 'durationMs' => round($duration * 1000, 2), 'result' => $row->result]);
        } catch (\Throwable $e) {
            $duration = microtime(true) - $start;

            $histogram->observe($duration, ['500']);
            $counter->inc(['500']);

            return response()->json(['status' => 500, 'error' => $e->getMessage()], 500);
        }
    }
}
